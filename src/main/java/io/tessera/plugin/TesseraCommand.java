package io.tessera.plugin;

import io.tessera.assemble.BlockGeometry;
import io.tessera.assemble.DebugGridSpawner;
import io.tessera.assemble.FaceRotations;
import io.tessera.assemble.FakeBlockFactory;
import io.tessera.assemble.HeadRotations;
import io.tessera.core.BlockKey;
import io.tessera.core.FaceDir;
import io.tessera.core.FakeBlock;
import io.tessera.core.HeadFace;
import io.tessera.effect.EffectContext;
import io.tessera.effect.builtin.DirectionalShrinkEffect;
import io.tessera.skin.FaceDebugTint;
import io.tessera.skin.HeadsRegistry;
import io.tessera.skin.TileFlips;
import io.tessera.skin.TileRotations;
import io.tessera.skin.bake.BlockBaker;
import io.tessera.split.SourceFlips;
import io.tessera.split.SourceRotations;
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
 *   /tessera debug tilerot   &lt;headface&gt; &lt;0|90|180|270&gt; per-chunk tile in-plane rotation
 *   /tessera debug tilerot   reset [headface]
 *   /tessera debug tileflip  &lt;headface&gt; &lt;none|h|v|hv&gt;  per-chunk tile mirror
 *   /tessera debug tileflip  reset [headface]
 *   /tessera debug sourcerot  &lt;facedir&gt; &lt;0|90|180|270&gt; whole block-face source rotation
 *   /tessera debug sourcerot  reset [facedir]
 *   /tessera debug sourceflip &lt;facedir&gt; &lt;none|h|v|hv&gt;  whole block-face source mirror
 *   /tessera debug sourceflip reset [facedir]
 *   /tessera debug rebake [material]    invalidate registry so next test re-bakes
 *   /tessera debug status               print all current rotation/flip overrides
 *   /tessera debug debugtex on|off|toggle  replace tiles with directional markers
 *   /tessera debug permutations &lt;head|tile|source|all&gt; &lt;facedir&gt; [material]
 *                                       sweep through configurations; spawns a static
 *                                       FakeBlock per combo with vanilla compare below
 *   /tessera debug dumppng &lt;material&gt;    split+paint locally and write one PNG per
 *                                       chunk named &lt;x&gt;-&lt;y&gt;-&lt;z&gt;.png to
 *                                       plugins/Tessera/dump-&lt;material&gt;/
 * </pre>
 *
 * <p>The three rotation knobs operate at different scales:
 * <ul>
 *   <li><b>headrot</b> — runtime cube spin around outward axis. Per-HeadFace,
 *       instant, no re-bake.</li>
 *   <li><b>tilerot</b> — rotate each chunk's tile in-plane within its head
 *       slot. Per-HeadFace, bake-time.</li>
 *   <li><b>sourcerot</b> — rotate the whole block-face source texture before
 *       splitting. Per-FaceDir (UP/DOWN/etc.), bake-time. Use this when an
 *       entire face looks rotated as a unit (vs. tilerot for individual
 *       tiles).</li>
 * </ul>
 * Both bake-time knobs auto-invalidate the registry so the next test
 * re-uploads. Once you find values that work, fold them into the
 * respective DEFAULTS map.
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
        org.bukkit.util.Vector eyeDir = p.getEyeLocation().getDirection();
        FakeBlock fb = factory.create(p, target, key, new org.joml.Quaternionf(),
                plugin.tesseraConfig().fillInterior(), eyeDir);
        if (fb.chunks().isEmpty()) {
            sender.sendMessage("§cFakeBlock has 0 chunks - heads.json entry for " + key + " is empty.");
            return;
        }
        if (staticMode) {
            sender.sendMessage("§aSpawned static FakeBlock for " + key + " at " + formatLoc(target)
                    + " (auto-removed in 5 min)");
            // 5 minutes of linger so the user has time to walk around, switch
            // angles, compare with neighbouring static spawns, etc. The
            // BlockBaker is async — re-running /tessera test stone static
            // after a /debug tilerot re-bakes and spawns the new one without
            // disturbing earlier ones.
            Bukkit.getScheduler().runTaskLater(plugin, fb::despawn, 5L * 60L * 20L);
            return;
        }
        EffectContext ctx = new EffectContext(
                p.getEyeLocation().getDirection(),
                System.currentTimeMillis(),
                plugin.tesseraConfig().effectDurationMs(),
                plugin);
        new DirectionalShrinkEffect(plugin.tesseraConfig().collapseStyle()).applyTimed(fb, ctx);
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
            case "face"         -> handleDebugFace(sender, args);
            case "center"       -> handleDebugCenter(sender, args);
            case "grid"         -> handleDebugGrid(sender, args);
            case "tilerot"      -> handleDebugTilerot(sender, args);
            case "tileflip"     -> handleDebugTileflip(sender, args);
            case "headrot"      -> handleDebugHeadrot(sender, args);
            case "sourcerot"    -> handleDebugSourcerot(sender, args);
            case "sourceflip"   -> handleDebugSourceflip(sender, args);
            case "rebake"       -> handleDebugRebake(sender, args);
            case "status"       -> handleDebugStatus(sender, args);
            case "debugtex"     -> handleDebugTex(sender, args);
            case "permutations" -> handleDebugPermutations(sender, args);
            case "dumppng"      -> handleDebugDumpPng(sender, args);
            default -> {
                sender.sendMessage("§cUnknown debug target: " + args[1]);
                yield true;
            }
        };
    }

    private boolean handleDebugStatus(CommandSender sender, String[] args) {
        sender.sendMessage("§6Tessera debug status");
        sender.sendMessage("§7Debug texture mode: §f" + (FaceDebugTint.isEnabled() ? "ON" : "off"));
        sender.sendMessage("§7BlockGeometry.CUBE_CENTER_PRE = §f" + formatVec(BlockGeometry.cubeCenterPre()));
        sender.sendMessage("§7FaceRotations (Euler X/Y/Z deg per HeadFace):");
        for (HeadFace f : HeadFace.values()) {
            FaceRotations.Euler e = FaceRotations.eulerOf(f);
            sender.sendMessage("§7  " + f + ": §f" + e.xDeg() + " / " + e.yDeg() + " / " + e.zDeg() + " §8(default "
                    + euler(FaceRotations.defaultEulerOf(f)) + ")");
        }
        sender.sendMessage("§7HeadRotations (deg per HeadFace, runtime spin):");
        for (HeadFace f : HeadFace.values()) {
            int v = HeadRotations.of(f);
            int d = HeadRotations.defaultOf(f);
            sender.sendMessage("§7  " + f + ": §f" + v + "° §8(default " + d + "°)");
        }
        sender.sendMessage("§7TileRotations (deg per HeadFace, bake-time):");
        for (HeadFace f : HeadFace.values()) {
            int v = TileRotations.of(f);
            int d = TileRotations.defaultOf(f);
            sender.sendMessage("§7  " + f + ": §f" + v + "° §8(default " + d + "°)");
        }
        sender.sendMessage("§7TileFlips (per HeadFace, bake-time):");
        for (HeadFace f : HeadFace.values()) {
            TileFlips.Flip v = TileFlips.of(f);
            TileFlips.Flip d = TileFlips.defaultOf(f);
            sender.sendMessage("§7  " + f + ": §f" + v + " §8(default " + d + ")");
        }
        sender.sendMessage("§7SourceRotations (deg per FaceDir, bake-time):");
        for (FaceDir d : FaceDir.values()) {
            int v = SourceRotations.of(d);
            int def = SourceRotations.defaultOf(d);
            sender.sendMessage("§7  " + d + ": §f" + v + "° §8(default " + def + "°)");
        }
        sender.sendMessage("§7SourceFlips (per FaceDir, bake-time):");
        for (FaceDir d : FaceDir.values()) {
            SourceFlips.Flip v = SourceFlips.of(d);
            SourceFlips.Flip def = SourceFlips.defaultOf(d);
            sender.sendMessage("§7  " + d + ": §f" + v + " §8(default " + def + ")");
        }
        return true;
    }

    private boolean handleDebugPermutations(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cpermutations must be run by a player.");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage("§c/tessera debug permutations <head|tile|source|all> <facedir> [material]");
            sender.sendMessage("§7  head=4 instant; tile=4 rebakes; source=16 rebakes; all=64 rebakes");
            return true;
        }
        PermutationSweeper.Kind kind;
        try {
            kind = PermutationSweeper.Kind.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("§cUnknown kind: " + args[2] + " (head|tile|source|all)");
            return true;
        }
        FaceDir face = parseFaceDir(args[3]);
        if (face == null) { sender.sendMessage("§cUnknown facedir: " + args[3]); return true; }
        Material mat = args.length > 4 ? Material.matchMaterial(args[4]) : Material.STONE;
        if (mat == null || !mat.isBlock()) {
            sender.sendMessage("§cNot a block material: " + args[4]); return true;
        }
        BlockKey key = BlockKey.of(mat.getKey().getNamespace() + ":" + mat.getKey().getKey());
        Location anchor = pickTargetLocation(p);
        new PermutationSweeper(plugin, factory, registry, baker)
                .sweep(p, sender, key, mat, face, anchor, kind);
        return true;
    }

    private static String euler(FaceRotations.Euler e) {
        return e.xDeg() + "/" + e.yDeg() + "/" + e.zDeg();
    }

    private boolean handleDebugSourceflip(CommandSender sender, String[] args) {
        // /tessera debug sourceflip <facedir> <none|h|v|hv>
        // /tessera debug sourceflip reset [facedir]
        // Mirrors the source block-face texture per axis. Combine with
        // sourcerot to express any of the 8 dihedral orientations of a
        // square. Use this when sourcerot alone can't fix wrap mismatches
        // (i.e. when rotation only moves the bad areas around).
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            if (args.length == 3) {
                SourceFlips.resetAll();
                sender.sendMessage("§aReset all source flips.");
            } else {
                FaceDir d = parseFaceDir(args[3]);
                if (d == null) { sender.sendMessage("§cUnknown facedir: " + args[3]); return true; }
                SourceFlips.reset(d);
                sender.sendMessage("§aReset source flip for " + d + " to " + SourceFlips.defaultOf(d) + ".");
            }
            int cleared = registry.invalidateAll();
            sender.sendMessage("§7Cleared " + cleared + " registry entr"
                    + (cleared == 1 ? "y" : "ies") + "; next /tessera test will re-bake.");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage("§c/tessera debug sourceflip <up|down|north|south|east|west> <none|h|v|hv> | reset [facedir]");
            return true;
        }
        FaceDir d = parseFaceDir(args[2]);
        if (d == null) { sender.sendMessage("§cUnknown facedir: " + args[2]); return true; }
        try {
            SourceFlips.Flip flip = SourceFlips.Flip.parse(args[3]);
            SourceFlips.set(d, flip);
            int cleared = registry.invalidateAll();
            sender.sendMessage("§aSet source flip for " + d + " = " + SourceFlips.of(d) + ".");
            sender.sendMessage("§7Cleared " + cleared + " registry entr"
                    + (cleared == 1 ? "y" : "ies") + "; next /tessera test will re-bake.");
        } catch (IllegalArgumentException iae) {
            sender.sendMessage("§c" + iae.getMessage());
        }
        return true;
    }

    private boolean handleDebugSourcerot(CommandSender sender, String[] args) {
        // /tessera debug sourcerot <facedir> <0|90|180|270>
        // /tessera debug sourcerot reset [facedir]
        // Rotates the source block-face texture before splitting. Use this
        // when an entire block face appears rotated/flipped (vs. tilerot
        // which rotates each chunk's tile in-plane). Bake-time, so the
        // registry is auto-invalidated and the next test re-bakes.
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            if (args.length == 3) {
                SourceRotations.resetAll();
                sender.sendMessage("§aReset all source rotations.");
            } else {
                FaceDir d = parseFaceDir(args[3]);
                if (d == null) { sender.sendMessage("§cUnknown facedir: " + args[3]); return true; }
                SourceRotations.reset(d);
                sender.sendMessage("§aReset source rotation for " + d + " to "
                        + SourceRotations.defaultOf(d) + "°.");
            }
            int cleared = registry.invalidateAll();
            sender.sendMessage("§7Cleared " + cleared + " registry entr"
                    + (cleared == 1 ? "y" : "ies") + "; next /tessera test will re-bake.");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage("§c/tessera debug sourcerot <up|down|north|south|east|west> <0|90|180|270> | reset [facedir]");
            return true;
        }
        FaceDir d = parseFaceDir(args[2]);
        if (d == null) { sender.sendMessage("§cUnknown facedir: " + args[2]); return true; }
        try {
            int deg = Integer.parseInt(args[3]);
            SourceRotations.set(d, deg);
            int cleared = registry.invalidateAll();
            sender.sendMessage("§aSet source rotation for " + d + " = " + SourceRotations.of(d) + "°.");
            sender.sendMessage("§7Cleared " + cleared + " registry entr"
                    + (cleared == 1 ? "y" : "ies") + "; next /tessera test will re-bake (a few seconds).");
        } catch (NumberFormatException nfe) {
            sender.sendMessage("§cExpected an integer multiple of 90.");
        } catch (IllegalArgumentException iae) {
            sender.sendMessage("§c" + iae.getMessage());
        }
        return true;
    }

    private static FaceDir parseFaceDir(String s) {
        try { return FaceDir.valueOf(s.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
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

    private boolean handleDebugTileflip(CommandSender sender, String[] args) {
        // /tessera debug tileflip <headface> <none|h|v|hv>
        // /tessera debug tileflip reset [headface]
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            if (args.length == 3) {
                TileFlips.resetAll();
                sender.sendMessage("§aReset all tile flips.");
            } else {
                HeadFace f = parseFace(args[3]);
                if (f == null) { sender.sendMessage("§cUnknown face: " + args[3]); return true; }
                TileFlips.reset(f);
                sender.sendMessage("§aReset tile flip for " + f + " to " + TileFlips.defaultOf(f) + ".");
            }
            int cleared = registry.invalidateAll();
            sender.sendMessage("§7Cleared " + cleared + " registry entr"
                    + (cleared == 1 ? "y" : "ies") + "; next /tessera test will re-bake.");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage("§c/tessera debug tileflip <headface> <none|h|v|hv> | reset [headface]");
            return true;
        }
        HeadFace face = parseFace(args[2]);
        if (face == null) { sender.sendMessage("§cUnknown face: " + args[2]); return true; }
        try {
            TileFlips.Flip flip = TileFlips.Flip.parse(args[3]);
            TileFlips.set(face, flip);
            int cleared = registry.invalidateAll();
            sender.sendMessage("§aSet tile flip for " + face + " = " + TileFlips.of(face) + ".");
            sender.sendMessage("§7Cleared " + cleared + " registry entr"
                    + (cleared == 1 ? "y" : "ies") + "; next /tessera test will re-bake.");
        } catch (IllegalArgumentException iae) {
            sender.sendMessage("§c" + iae.getMessage());
        }
        return true;
    }

    private boolean handleDebugTex(CommandSender sender, String[] args) {
        // /tessera debug debugtex on|off|toggle
        // When on, SkinAssembler replaces every chunk tile with a
        // directional marker (face letter + colored borders: red top,
        // green right, blue bottom, yellow left). Use this to read off
        // each rendered face's orientation directly instead of guessing
        // from texture content. Bake-time, so changing it invalidates
        // the registry.
        boolean on;
        if (args.length < 3 || args[2].equalsIgnoreCase("toggle")) {
            on = !FaceDebugTint.isEnabled();
        } else {
            String v = args[2].toLowerCase(Locale.ROOT);
            if (v.equals("on") || v.equals("true") || v.equals("1")) on = true;
            else if (v.equals("off") || v.equals("false") || v.equals("0")) on = false;
            else { sender.sendMessage("§c/tessera debug debugtex on|off|toggle"); return true; }
        }
        FaceDebugTint.setEnabled(on);
        int cleared = registry.invalidateAll();
        sender.sendMessage("§aDebug texture mode: " + (on ? "ON" : "off")
                + ". Cleared " + cleared + " registry entr"
                + (cleared == 1 ? "y" : "ies") + ".");
        sender.sendMessage("§7Edges: §cred§7=top §agreen§7=right §9blue§7=bottom §eyellow§7=left."
                + " Re-spawn / break a block to see.");
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

    private boolean handleDebugDumpPng(CommandSender sender, String[] args) {
        // /tessera debug dumppng <material>
        // Splits + paints the block locally and writes one PNG per chunk
        // named <x>-<y>-<z>.png so a developer can inspect what's actually
        // being painted into each slot without going through MineSkin.
        if (args.length < 3) {
            sender.sendMessage("§c/tessera debug dumppng <material>");
            return true;
        }
        Material mat = Material.matchMaterial(args[2]);
        if (mat == null) { sender.sendMessage("§cUnknown material: " + args[2]); return true; }
        BlockKey key = BlockKey.of(mat.getKey().getNamespace() + ":" + mat.getKey().getKey());
        java.nio.file.Path outDir = plugin.getDataFolder().toPath()
                .resolve("dump-" + mat.getKey().getKey());
        try {
            int n = baker.dumpPng(key, outDir);
            sender.sendMessage(n == 0
                    ? "§7No chunks dumped — block unsupported (non-cube/tinted/asset missing)."
                    : "§aWrote " + n + " chunk PNGs to " + outDir);
        } catch (java.io.IOException e) {
            sender.sendMessage("§cDump failed: " + e.getMessage());
        }
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
