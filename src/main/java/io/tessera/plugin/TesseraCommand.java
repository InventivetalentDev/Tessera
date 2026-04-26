package io.tessera.plugin;

import io.tessera.assemble.BlockGeometry;
import io.tessera.assemble.DebugGridSpawner;
import io.tessera.assemble.FaceRotations;
import io.tessera.assemble.FakeBlockFactory;
import io.tessera.assemble.HeadRotations;
import io.tessera.core.BlockKey;
import io.tessera.core.FakeBlock;
import io.tessera.core.HeadFace;
import io.tessera.effect.EffectContext;
import io.tessera.effect.builtin.DirectionalShrinkEffect;
import io.tessera.skin.HeadsRegistry;
import io.tessera.skin.TileRotations;
import io.tessera.skin.bake.BlockBaker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.joml.Vector3f;

import java.util.Locale;

/**
 * v1 command surface for testing the splitting/effect pipeline. Subcommands:
 *
 * <pre>
 *   /tessera test [material] [static]   bake (if needed) + spawn FakeBlock; "static" = no shrink
 *   /tessera reload                     reload config.yml
 *
 *   /tessera debug grid [material]      preview cube lattice with default Steve skin
 *   /tessera debug face   &lt;face&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;    override a FaceRotations Euler triple
 *   /tessera debug face   reset [face]
 *   /tessera debug center &lt;x&gt; &lt;y&gt; &lt;z&gt;            override BlockGeometry.CUBE_CENTER_PRE
 *   /tessera debug center reset
 *   /tessera debug headrot &lt;face&gt; &lt;0|90|180|270&gt; runtime spin around outward axis
 *   /tessera debug headrot reset [face]
 *   /tessera debug tilerot &lt;face&gt; &lt;0|90|180|270&gt; bake-time tile rotation (auto-invalidates registry)
 *   /tessera debug tilerot reset [face]
 *   /tessera debug rebake [material]    invalidate registry so next test re-bakes
 * </pre>
 *
 * <p>headrot vs tilerot: both visually rotate the texture on the visible
 * face. headrot does it at runtime by spinning the cube — instant, no
 * re-bake. tilerot rotates the source tile before paint — auto-invalidates
 * the registry so the next test re-uploads PNGs to MineSkin. Use headrot
 * for fast empirical tuning; fold the working values into
 * TileRotations.DEFAULTS once stable to drop the runtime quaternion mul.
 *
 * v2 will add /tessera physics presets.
 */
public final class TesseraCommand implements CommandExecutor {

    private final TesseraPlugin plugin;
    private final FakeBlockFactory factory;
    private final HeadsRegistry registry;
    private final BlockBaker baker;

    public TesseraCommand(TesseraPlugin plugin, FakeBlockFactory factory,
                          HeadsRegistry registry, BlockBaker baker) {
        this.plugin = plugin;
        this.factory = factory;
        this.registry = registry;
        this.baker = baker;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7/tessera test [material] | reload | debug face|center …");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "test"   -> handleTest(sender, args);
            case "reload" -> handleReload(sender);
            case "debug"  -> handleDebug(sender, args);
            default -> {
                sender.sendMessage("§cUnknown subcommand: " + args[0]);
                yield true;
            }
        };
    }

    private boolean handleTest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cTest must be run by a player.");
            return true;
        }
        Material mat = args.length > 1 ? Material.matchMaterial(args[1]) : Material.STONE;
        if (mat == null) {
            sender.sendMessage("§cUnknown material: " + args[1]);
            return true;
        }
        // Optional trailing "static" flag → spawn without the shrink effect.
        // Useful when debugging texture / rotation - the FakeBlock lingers
        // for STATIC_LIFETIME_TICKS so you can rotate around and inspect.
        boolean staticMode = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("static")) staticMode = true;
        }
        BlockKey key = BlockKey.of(mat.getKey().getNamespace() + ":" + mat.getKey().getKey());
        Location target = pickTargetLocation(p);
        boolean st = staticMode;

        if (!registry.has(key)) {
            if (plugin.tesseraConfig().mineskinApiKey().isBlank()) {
                sender.sendMessage("§c" + key + " not baked and no MineSkin API key configured.");
                sender.sendMessage("§7Set §fmineskinApiKey§7 in config.yml, /tessera reload, then retry.");
                return true;
            }
            sender.sendMessage("§e" + key + " not yet baked - uploading skins to MineSkin (a few seconds)...");
            baker.bake(key).whenComplete((ok, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (ex != null) {
                    sender.sendMessage("§cBake threw: " + ex.getMessage());
                    return;
                }
                if (ok == null || !ok) {
                    sender.sendMessage("§cBake failed: unsupported block, or upload error (see console).");
                    return;
                }
                sender.sendMessage("§aBake complete; spawning" + (st ? " (static)" : "") + ".");
                spawnTest(sender, p, key, target, st);
            }));
            return true;
        }

        spawnTest(sender, p, key, target, st);
        return true;
    }

    /**
     * If {@code staticMode}, spawn and let it linger for ~30s without running
     * any effect — useful when debugging texture/rotation issues without
     * being rushed by the shrink animation.
     */
    private void spawnTest(CommandSender sender, Player p, BlockKey key, Location target, boolean staticMode) {
        FakeBlock fb = factory.create(target, key);
        if (fb.chunks().isEmpty()) {
            sender.sendMessage("§cFakeBlock has 0 chunks - heads.json entry for " + key + " is empty.");
            return;
        }
        if (staticMode) {
            sender.sendMessage("§aSpawned static FakeBlock for " + key + " at " + formatLoc(target)
                    + " (auto-removed in 30s)");
            Bukkit.getScheduler().runTaskLater(plugin, fb::despawn, 30L * 20L);
            return;
        }
        EffectContext ctx = new EffectContext(
                p.getEyeLocation().getDirection(),
                System.currentTimeMillis(),
                plugin.tesseraConfig().effectDurationMs(),
                plugin);
        new DirectionalShrinkEffect().apply(fb, ctx);
        sender.sendMessage("§aSpawned FakeBlock for " + key + " at " + formatLoc(target));
    }

    private boolean handleReload(CommandSender sender) {
        plugin.reloadTesseraConfig();
        sender.sendMessage("§aTessera config reloaded.");
        return true;
    }

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§7/tessera debug face|center …");
            return true;
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "face"    -> handleDebugFace(sender, args);
            case "center"  -> handleDebugCenter(sender, args);
            case "grid"    -> handleDebugGrid(sender, args);
            case "tilerot" -> handleDebugTilerot(sender, args);
            case "headrot" -> handleDebugHeadrot(sender, args);
            case "rebake"  -> handleDebugRebake(sender, args);
            default -> {
                sender.sendMessage("§cUnknown debug target: " + args[1]);
                yield true;
            }
        };
    }

    private boolean handleDebugHeadrot(CommandSender sender, String[] args) {
        // /tessera debug headrot <face> <0|90|180|270>
        // /tessera debug headrot reset [face]
        // Runtime cube spin around the visible face's outward axis - no
        // re-bake needed. Use this to figure out whether textures are off
        // because of cube rotation (in which case headrot fixes it) vs.
        // texture orientation (in which case tilerot is the right fix).
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            if (args.length == 3) {
                HeadRotations.resetAll();
                sender.sendMessage("§aReset all head rotations.");
            } else {
                HeadFace f = parseFace(args[3]);
                if (f == null) { sender.sendMessage("§cUnknown face: " + args[3]); return true; }
                HeadRotations.reset(f);
                sender.sendMessage("§aReset head rotation for " + f + " to "
                        + HeadRotations.defaultOf(f) + "°.");
            }
            sender.sendMessage("§7Re-spawn the FakeBlock to see the change (no re-bake needed).");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage("§c/tessera debug headrot <face> <0|90|180|270> | reset [face]");
            return true;
        }
        HeadFace face = parseFace(args[2]);
        if (face == null) { sender.sendMessage("§cUnknown face: " + args[2]); return true; }
        try {
            int deg = Integer.parseInt(args[3]);
            HeadRotations.set(face, deg);
            sender.sendMessage("§aSet head rotation for " + face + " = " + HeadRotations.of(face) + "°.");
            sender.sendMessage("§7Re-spawn the FakeBlock (e.g. /tessera test " + face.name().toLowerCase() + ") to see it.");
        } catch (NumberFormatException nfe) {
            sender.sendMessage("§cExpected an integer multiple of 90.");
        } catch (IllegalArgumentException iae) {
            sender.sendMessage("§c" + iae.getMessage());
        }
        return true;
    }

    private boolean handleDebugTilerot(CommandSender sender, String[] args) {
        // /tessera debug tilerot <face> <degrees>   (degrees = 0|90|180|270)
        // /tessera debug tilerot reset [face]
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            if (args.length == 3) {
                TileRotations.resetAll();
                sender.sendMessage("§aReset all tile rotations.");
            } else {
                HeadFace f = parseFace(args[3]);
                if (f == null) { sender.sendMessage("§cUnknown face: " + args[3]); return true; }
                TileRotations.reset(f);
                sender.sendMessage("§aReset tile rotation for " + f + " to "
                        + TileRotations.defaultOf(f) + "°.");
            }
            int cleared = registry.invalidateAll();
            sender.sendMessage("§7Cleared " + cleared + " registry entr"
                    + (cleared == 1 ? "y" : "ies") + "; next /tessera test will re-bake.");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage("§c/tessera debug tilerot <face> <0|90|180|270> | reset [face]");
            return true;
        }
        HeadFace face = parseFace(args[2]);
        if (face == null) { sender.sendMessage("§cUnknown face: " + args[2]); return true; }
        try {
            int deg = Integer.parseInt(args[3]);
            TileRotations.set(face, deg);
            // Invalidate everything so the next /tessera test or BlockBreak
            // triggers a fresh bake. Without this the registry hit short-
            // circuits the baker, leaving the rotation change invisible.
            int cleared = registry.invalidateAll();
            sender.sendMessage("§aSet tile rotation for " + face + " = " + TileRotations.of(face) + "°.");
            sender.sendMessage("§7Cleared " + cleared + " registry entr"
                    + (cleared == 1 ? "y" : "ies") + "; next /tessera test will re-bake (a few seconds).");
        } catch (NumberFormatException nfe) {
            sender.sendMessage("§cExpected an integer multiple of 90.");
        } catch (IllegalArgumentException iae) {
            sender.sendMessage("§c" + iae.getMessage());
        }
        return true;
    }

    private boolean handleDebugRebake(CommandSender sender, String[] args) {
        // /tessera debug rebake [material]   (no arg → invalidate all)
        if (args.length < 3) {
            int n = registry.invalidateAll();
            sender.sendMessage("§aCleared " + n + " registry entries; next /tessera test will re-bake.");
            return true;
        }
        Material mat = Material.matchMaterial(args[2]);
        if (mat == null) { sender.sendMessage("§cUnknown material: " + args[2]); return true; }
        BlockKey key = BlockKey.of(mat.getKey().getNamespace() + ":" + mat.getKey().getKey());
        boolean removed = registry.invalidate(key);
        sender.sendMessage(removed
                ? "§aCleared " + key + " from registry; next /tessera test will re-bake."
                : "§7" + key + " was not in the registry.");
        return true;
    }

    private boolean handleDebugGrid(CommandSender sender, String[] args) {
        // /tessera debug grid [material] - spawn a BlockDisplay lattice
        // at the player's target block to verify geometry without
        // needing any baked skins.
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cdebug grid must be run by a player.");
            return true;
        }
        Material mat = Material.DIAMOND_ORE;
        if (args.length >= 3) {
            Material m = Material.matchMaterial(args[2]);
            if (m == null || !m.isBlock()) {
                sender.sendMessage("§cNot a block material: " + args[2]);
                return true;
            }
            mat = m;
        }
        Location target = pickTargetLocation(p);
        var spawned = DebugGridSpawner.spawn(plugin, target, plugin.tesseraConfig().chunkGridSize(), mat);
        sender.sendMessage("§aSpawned " + spawned.size() + " " + mat
                + " cells at " + formatLoc(target) + " (auto-removed in 30s).");
        sender.sendMessage("§7Use §f/tessera debug center <x> <y> <z>§7 if cubes don't meet flush.");
        return true;
    }

    private boolean handleDebugFace(CommandSender sender, String[] args) {
        // /tessera debug face <face> <x> <y> <z>
        // /tessera debug face reset [face]
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            if (args.length == 3) {
                FaceRotations.resetAll();
                sender.sendMessage("§aReset all face rotations.");
            } else {
                HeadFace face = parseFace(args[3]);
                if (face == null) { sender.sendMessage("§cUnknown face: " + args[3]); return true; }
                FaceRotations.reset(face);
                sender.sendMessage("§aReset " + face + " rotation.");
            }
            return true;
        }
        if (args.length < 6) {
            sender.sendMessage("§c/tessera debug face <face> <xDeg> <yDeg> <zDeg>");
            return true;
        }
        HeadFace face = parseFace(args[2]);
        if (face == null) { sender.sendMessage("§cUnknown face: " + args[2]); return true; }
        try {
            float x = Float.parseFloat(args[3]);
            float y = Float.parseFloat(args[4]);
            float z = Float.parseFloat(args[5]);
            FaceRotations.set(face, x, y, z);
            sender.sendMessage("§aSet " + face + " = (" + x + ", " + y + ", " + z + ")°");
        } catch (NumberFormatException nfe) {
            sender.sendMessage("§cExpected three numeric degrees.");
        }
        return true;
    }

    private boolean handleDebugCenter(CommandSender sender, String[] args) {
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            BlockGeometry.cubeCenterPre(BlockGeometry.CUBE_CENTER_PRE_DEFAULT);
            sender.sendMessage("§aReset CUBE_CENTER_PRE to default " + formatVec(BlockGeometry.cubeCenterPre()));
            return true;
        }
        if (args.length < 5) {
            sender.sendMessage("§c/tessera debug center <x> <y> <z> | reset");
            return true;
        }
        try {
            float x = Float.parseFloat(args[2]);
            float y = Float.parseFloat(args[3]);
            float z = Float.parseFloat(args[4]);
            BlockGeometry.cubeCenterPre(new Vector3f(x, y, z));
            sender.sendMessage("§aSet CUBE_CENTER_PRE = (" + x + ", " + y + ", " + z + ")");
        } catch (NumberFormatException nfe) {
            sender.sendMessage("§cExpected three numeric components.");
        }
        return true;
    }

    /**
     * Pick a sensible spawn cell for debug commands: the air block on the
     * player-facing side of whatever they're looking at, falling back to
     * 3 blocks in front of the player if no block is in range.
     */
    private static Location pickTargetLocation(Player p) {
        var ray = p.rayTraceBlocks(8);
        if (ray != null && ray.getHitBlock() != null && ray.getHitBlockFace() != null) {
            return ray.getHitBlock()
                    .getRelative(ray.getHitBlockFace())
                    .getLocation();
        }
        return p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(3));
    }

    private static HeadFace parseFace(String s) {
        try { return HeadFace.valueOf(s.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static String formatLoc(Location l) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", l.getX(), l.getY(), l.getZ());
    }

    private static String formatVec(Vector3f v) {
        return String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", v.x, v.y, v.z);
    }
}
