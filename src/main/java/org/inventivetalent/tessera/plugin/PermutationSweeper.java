package org.inventivetalent.tessera.plugin;

import org.inventivetalent.tessera.assemble.FakeBlockFactory;
import org.inventivetalent.tessera.assemble.HeadRotations;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.FaceDir;
import org.inventivetalent.tessera.core.FakeBlock;
import org.inventivetalent.tessera.core.HeadFace;
import org.inventivetalent.tessera.skin.HeadSkinPacker;
import org.inventivetalent.tessera.skin.HeadsRegistry;
import org.inventivetalent.tessera.skin.TileFlips;
import org.inventivetalent.tessera.skin.TileRotations;
import org.inventivetalent.tessera.skin.bake.BlockBaker;
import org.inventivetalent.tessera.split.SourceFlips;
import org.inventivetalent.tessera.split.SourceRotations;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Sweeps through rotation/flip configurations for one (BlockKey, FaceDir)
 * pair, spawning a static FakeBlock for each combination in a line so the
 * user can compare. Below each FakeBlock: a vanilla {@link BlockDisplay} of
 * the same material for direct comparison. Above each: a {@link TextDisplay}
 * label showing its config.
 *
 * <p>Bake-time knobs ({@link SourceRotations}, {@link SourceFlips},
 * {@link TileRotations}) require re-uploading skins to MineSkin. Combos are
 * enumerated so bake-varying dimensions change in the outer loop, with
 * {@link HeadRotations} (runtime, free) in the innermost loop. Each "bake
 * group" then needs 1 upload + N quick spawns. The originally-set
 * overrides are restored at the end.
 *
 * <p>Layout: blocks are placed along a horizontal axis perpendicular to the
 * tested face, starting at {@code anchor}. Spacing is 2 blocks (one for the
 * cube, one of air). The vanilla comparison block sits 2 blocks below each
 * permutation. Labels float 2 blocks above. Everything auto-despawns after
 * 5 minutes.
 */
public final class PermutationSweeper {

    public enum Kind { HEAD, TILE, SOURCE, ALL }

    private static final long LIFETIME_TICKS = 5L * 60L * 20L;
    private static final int[] DEG_OPTIONS = { 0, 90, 180, 270 };
    private static final SourceFlips.Flip[] FLIP_OPTIONS = SourceFlips.Flip.values();

    private final Plugin plugin;
    private final FakeBlockFactory factory;
    private final HeadsRegistry registry;
    private final BlockBaker baker;

    public PermutationSweeper(Plugin plugin, FakeBlockFactory factory,
                              HeadsRegistry registry, BlockBaker baker) {
        this.plugin = plugin;
        this.factory = factory;
        this.registry = registry;
        this.baker = baker;
    }

    public void sweep(Player p, CommandSender sender, BlockKey key, Material mat,
                      FaceDir face, Location anchor, Kind kind) {
        World world = anchor.getWorld();
        if (world == null) return;

        HeadFace headFace = HeadSkinPacker.faceDirToHeadFace(face);
        List<Combo> combos = enumerate(kind);
        if (combos.isEmpty()) {
            sender.sendMessage("§cNo combinations to sweep.");
            return;
        }

        // Lay out along world +X by default, swap to +Z if testing east/west
        // (so the line doesn't run parallel to the face we're examining).
        Vector3f lineDir = (face == FaceDir.EAST || face == FaceDir.WEST)
                ? new Vector3f(0, 0, 1) : new Vector3f(1, 0, 0);

        Snapshot before = Snapshot.capture(face, headFace);
        long bakes = countBakes(combos);
        sender.sendMessage("§eSweeping " + combos.size() + " combination"
                + (combos.size() == 1 ? "" : "s") + " for " + key + " "
                + face + " (" + kind + "). " + bakes + " rebake"
                + (bakes == 1 ? "" : "s") + " expected.");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runSweep(
                p, world, anchor, lineDir, key, mat, face, headFace, combos, before, sender));
    }

    private void runSweep(Player viewer, World world, Location anchor, Vector3f lineDir,
                          BlockKey key, Material mat, FaceDir face, HeadFace headFace,
                          List<Combo> combos, Snapshot before, CommandSender sender) {
        try {
            Combo lastBake = null;
            for (int idx = 0; idx < combos.size(); idx++) {
                Combo c = combos.get(idx);
                setBakeOverrides(face, headFace, c);

                boolean rebakeNeeded = lastBake == null
                        || lastBake.sourceRot != c.sourceRot
                        || lastBake.sourceFlip != c.sourceFlip
                        || lastBake.tileRot != c.tileRot
                        || lastBake.tileFlip != c.tileFlip;

                if (rebakeNeeded) {
                    registry.invalidate(key);
                    try {
                        Boolean ok = baker.bake(key).get(2, TimeUnit.MINUTES);
                        if (ok == null || !ok) {
                            plugin.getLogger().warning("[sweep] bake failed at " + c.label());
                            continue;
                        }
                        lastBake = c;
                    } catch (ExecutionException | InterruptedException | TimeoutException ex) {
                        plugin.getLogger().log(Level.WARNING, "[sweep] bake threw at " + c.label(), ex);
                        continue;
                    }
                }

                final int slot = idx;
                final Combo combo = c;
                // Block the async thread until the main-thread spawn finishes.
                // Otherwise the next iteration's registry.invalidate(key) races
                // ahead and clears the registry while this iteration's queued
                // spawn is still pending — factory.create then sees an empty
                // chunks map and spawns 0 entities. This was visible as "only
                // the first 4 hr variants spawn anything": iter 0 baked +
                // populated registry, but iters 4..N invalidated before
                // iters 1..N-1's spawns ran.
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        spawnOne(viewer, world, anchor, lineDir, slot, key, mat, headFace, combo);
                    } finally {
                        latch.countDown();
                    }
                });
                try {
                    if (!latch.await(30, TimeUnit.SECONDS)) {
                        plugin.getLogger().warning("[sweep] spawn timed out at " + c.label());
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            Bukkit.getScheduler().runTask(plugin, () -> {
                before.restore();
                registry.invalidate(key);
                sender.sendMessage("§aSweep done; original overrides restored. The sweep entries linger 5 min.");
            });
        }
    }

    private void spawnOne(Player viewer, World world, Location anchor, Vector3f lineDir, int slot,
                          BlockKey key, Material mat, HeadFace headFace, Combo c) {
        // Set this combo's HeadRotation just before factory.create so the
        // captured runtime spin matches the labeled config.
        HeadRotations.set(headFace, c.headRot);

        // Strip the player's yaw/pitch from the anchor before computing
        // line positions: BlockDisplay and TextDisplay both rotate with
        // entity yaw/pitch, which would tilt the comparison cubes and
        // labels at the same angle the player was looking when they ran
        // the command. FakeBlock dodges this because FakeBlockFactory
        // reconstructs a fresh Location internally; the other entities
        // need it explicit.
        Location flat = new Location(world, anchor.getX(), anchor.getY(), anchor.getZ());
        Location pos = flat.clone().add(lineDir.x * slot * 2.0,
                lineDir.y * slot * 2.0, lineDir.z * slot * 2.0);
        FakeBlock fb;
        try {
            fb = factory.create(viewer, pos, key);
        } catch (RuntimeException re) {
            plugin.getLogger().warning("[sweep] spawn failed at " + c.label() + ": " + re.getMessage());
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, fb::despawn, LIFETIME_TICKS);

        // Vanilla comparison BlockDisplay 2 blocks below.
        Location below = pos.clone().add(0, -2, 0);
        BlockDisplay vanilla = world.spawn(below, BlockDisplay.class, d -> {
            d.setBlock(mat.createBlockData());
            d.setBrightness(new Display.Brightness(15, 15));
            d.setPersistent(false);
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!vanilla.isDead()) vanilla.remove();
        }, LIFETIME_TICKS);

        // Floating label 2 blocks above. Smaller scale so it doesn't dwarf
        // the cubes; setSeeThrough makes it readable through walls.
        Location labelPos = pos.clone().add(0.5, 2, 0.5);
        TextDisplay label = world.spawn(labelPos, TextDisplay.class, d -> {
            d.text(Component.text(c.label()));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(0xC0, 0x00, 0x00, 0x00));
            d.setSeeThrough(true);
            d.setPersistent(false);
            d.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(),
                    new Vector3f(0.6f, 0.6f, 0.6f),
                    new AxisAngle4f()));
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!label.isDead()) label.remove();
        }, LIFETIME_TICKS);
    }

    /**
     * Combo enumeration order matters: bake-varying dimensions in the outer
     * loops, headRot innermost so consecutive combos can re-use the same
     * baked skins.
     */
    private List<Combo> enumerate(Kind kind) {
        List<Combo> out = new ArrayList<>();
        switch (kind) {
            case HEAD -> {
                for (int hr : DEG_OPTIONS) out.add(new Combo(0, SourceFlips.Flip.NONE, 0, TileFlips.Flip.NONE, hr));
            }
            case TILE -> {
                // Full tile-level dihedral: 4 rotations × 4 flips = 16.
                for (int tr : DEG_OPTIONS)
                    for (TileFlips.Flip tf : TileFlips.Flip.values())
                        out.add(new Combo(0, SourceFlips.Flip.NONE, tr, tf, 0));
            }
            case SOURCE -> {
                for (int sr : DEG_OPTIONS)
                    for (SourceFlips.Flip sf : FLIP_OPTIONS)
                        out.add(new Combo(sr, sf, 0, TileFlips.Flip.NONE, 0));
            }
            case ALL -> {
                // 4*4*4*4 source-and-tile bake combos × 4 head runtime = 1024.
                // Skipping tileflip here would still be 256 — the wide
                // version is what you need when nothing else has worked.
                for (int sr : DEG_OPTIONS)
                    for (SourceFlips.Flip sf : FLIP_OPTIONS)
                        for (int tr : DEG_OPTIONS)
                            for (TileFlips.Flip tf : TileFlips.Flip.values())
                                for (int hr : DEG_OPTIONS)
                                    out.add(new Combo(sr, sf, tr, tf, hr));
            }
        }
        return out;
    }

    private static long countBakes(List<Combo> combos) {
        // A "bake group" is a consecutive run of combos sharing the same
        // bake-time settings (everything but headRot). Our enumeration
        // order keeps headRot innermost so adjacent combos in the same
        // group differ only in headRot and re-use the bake.
        long n = 0;
        Combo prev = null;
        for (Combo c : combos) {
            if (prev == null || prev.sourceRot != c.sourceRot
                    || prev.sourceFlip != c.sourceFlip
                    || prev.tileRot != c.tileRot
                    || prev.tileFlip != c.tileFlip) {
                n++;
            }
            prev = c;
        }
        return n;
    }

    private static void setBakeOverrides(FaceDir face, HeadFace headFace, Combo c) {
        SourceRotations.set(face, c.sourceRot);
        SourceFlips.set(face, c.sourceFlip);
        TileRotations.set(headFace, c.tileRot);
        TileFlips.set(headFace, c.tileFlip);
    }

    private record Combo(int sourceRot, SourceFlips.Flip sourceFlip,
                         int tileRot, TileFlips.Flip tileFlip, int headRot) {
        String label() {
            return "sr=" + sourceRot + " sf=" + sourceFlip
                    + " tr=" + tileRot + " tf=" + tileFlip
                    + " hr=" + headRot;
        }
    }

    /**
     * Captures the active rotation/flip overrides for the (FaceDir, HeadFace)
     * pair being swept, so they can be restored after the sweep without
     * touching the other faces.
     */
    private static final class Snapshot {
        final FaceDir face;
        final HeadFace headFace;
        final int sourceRot;
        final SourceFlips.Flip sourceFlip;
        final int tileRot;
        final TileFlips.Flip tileFlip;
        final int headRot;

        Snapshot(FaceDir face, HeadFace headFace, int sourceRot, SourceFlips.Flip sourceFlip,
                 int tileRot, TileFlips.Flip tileFlip, int headRot) {
            this.face = face;
            this.headFace = headFace;
            this.sourceRot = sourceRot;
            this.sourceFlip = sourceFlip;
            this.tileRot = tileRot;
            this.tileFlip = tileFlip;
            this.headRot = headRot;
        }

        static Snapshot capture(FaceDir face, HeadFace headFace) {
            return new Snapshot(face, headFace,
                    SourceRotations.of(face),
                    SourceFlips.of(face),
                    TileRotations.of(headFace),
                    TileFlips.of(headFace),
                    HeadRotations.of(headFace));
        }

        void restore() {
            SourceRotations.set(face, sourceRot);
            SourceFlips.set(face, sourceFlip);
            TileRotations.set(headFace, tileRot);
            TileFlips.set(headFace, tileFlip);
            HeadRotations.set(headFace, headRot);
        }
    }
}
