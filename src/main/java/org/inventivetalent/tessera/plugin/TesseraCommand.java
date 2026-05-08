package org.inventivetalent.tessera.plugin;

import org.inventivetalent.tessera.assemble.BlockGeometry;
import org.inventivetalent.tessera.assemble.DebugGridSpawner;
import org.inventivetalent.tessera.assemble.FaceRotations;
import org.inventivetalent.tessera.assemble.FakeBlockFactory;
import org.inventivetalent.tessera.assemble.HeadRotations;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.FaceDir;
import org.inventivetalent.tessera.core.FakeBlock;
import org.inventivetalent.tessera.core.HeadFace;
import org.inventivetalent.tessera.effect.EffectContext;
import org.inventivetalent.tessera.effect.builtin.DirectionalShrinkEffect;
import org.inventivetalent.tessera.skin.FaceDebugTint;
import org.inventivetalent.tessera.skin.HeadsRegistry;
import org.inventivetalent.tessera.skin.TileFlips;
import org.inventivetalent.tessera.skin.TileRotations;
import org.inventivetalent.tessera.skin.bake.BlockBaker;
import org.inventivetalent.tessera.split.SourceFlips;
import org.inventivetalent.tessera.split.SourceRotations;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.Locale;

/**
 * v1 command surface for testing the splitting/effect pipeline. Subcommands:
 *
 * <pre>
 *   /tessera test [material] [static]   bake (if needed) + spawn FakeBlock; "static" = no shrink
 *   /tessera bake &lt;material&gt; [tint:#RRGGBB]
 *                                       bake without spawning; reports upload count + completion.
 *                                       Tint is required for biome-tinted blocks (grass, leaves,
 *                                       water) — supply the resolved biome multiplier as 6 hex digits.
 *   /tessera config                     list all editable config options
 *   /tessera config &lt;key&gt;               show current value of a config option
 *   /tessera config &lt;key&gt; &lt;value&gt;       set a config option, save to disk, and reload immediately.
 *                                       Keys: chunkGridSize, animation.mode, animation.style,
 *                                       animation.durationMs, animation.waveWindow,
 *                                       animation.fillInterior, transport, debug,
 *                                       limits.maxConcurrentFakeBlocks,
 *                                       progress.clientHideRealBlock, progress.smoothInterpolation,
 *                                       interaction.startOnLeftClick, interaction.eagerPreload,
 *                                       interaction.minBreakDurationMs, interaction.leftClickGraceMs.
 *                                       chunkGridSize and transport require a server restart.
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
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7/tessera test [material] | bake <material> | config <key> [value] | reload | debug …");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "test"   -> handleTest(sender, args);
            case "bake"   -> handleBake(sender, args);
            case "config" -> handleConfig(sender, args);
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
            if (args[i].equalsIgnoreCase("static")) {
                staticMode = true;
                break;
            }
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
            baker.bake(BakeKey.untinted(key)).whenComplete((ok, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
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

    /**
     * Trigger a bake for {@code <material>} without spawning anything. Useful
     * for warming the registry / disk cache (e.g. after editing
     * {@code bake-blocks.txt} on a running server) without disturbing the
     * world. Reports the upload count once the splitter/packer has decided
     * how many MineSkin uploads are actually required, and a completion
     * message when the bake finishes (success or failure).
     *
     * <p>Biome-tinted blocks (grass, leaves, water) need a colour multiplier
     * to bake — pass {@code tint:#RRGGBB} with the resolved hex (the same
     * value {@code BlockTintReader} would read off a player breaking the
     * block in the target biome). Without it the bake fails the same way the
     * runtime listener does.
     */
    private boolean handleBake(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c/tessera bake <material> [tint:#RRGGBB]");
            return true;
        }
        Material mat = Material.matchMaterial(args[1]);
        if (mat == null) {
            sender.sendMessage("§cUnknown material: " + args[1]);
            return true;
        }
        BlockKey block = BlockKey.of(mat.getKey().getNamespace() + ":" + mat.getKey().getKey());
        int tintArgb = 0;
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            String lower = a.toLowerCase(Locale.ROOT);
            if (lower.startsWith("tint:")) {
                String hex = a.substring(5);
                if (hex.startsWith("#")) hex = hex.substring(1);
                if (hex.length() != 6) {
                    sender.sendMessage("§cInvalid tint '" + a + "': expected 6 hex digits (tint:#RRGGBB).");
                    return true;
                }
                try {
                    int rgb = Integer.parseInt(hex, 16);
                    // Match BlockTintReader's stable-alpha convention so the
                    // same biome colour produces the same BakeKey regardless
                    // of whether the user typed the leading FF.
                    tintArgb = 0xFF000000 | (rgb & 0xFFFFFF);
                } catch (NumberFormatException nfe) {
                    sender.sendMessage("§cInvalid tint '" + a + "': not a hex colour.");
                    return true;
                }
            } else {
                sender.sendMessage("§cUnknown argument: " + a);
                return true;
            }
        }
        BakeKey bakeKey = new BakeKey(block, tintArgb);
        if (registry.has(bakeKey)) {
            sender.sendMessage("§a" + bakeKey + " is already baked; nothing to do.");
            sender.sendMessage("§7Use §f/tessera debug rebake " + mat.getKey().getKey()
                    + "§7 to invalidate first if you want to re-upload.");
            return true;
        }
        if (plugin.tesseraConfig().mineskinApiKey().isBlank()) {
            sender.sendMessage("§c" + bakeKey + " not baked and no MineSkin API key configured.");
            sender.sendMessage("§7Set §fmineskinApiKey§7 in config.yml, /tessera reload, then retry.");
            return true;
        }
        sender.sendMessage("§eBaking " + bakeKey + " ...");
        long start = System.currentTimeMillis();
        baker.bake(bakeKey, plan -> Bukkit.getScheduler().runTask(plugin, () -> {
            int cacheHits = plan.uniqueHeads() - plan.needUpload();
            if (plan.needUpload() == 0) {
                sender.sendMessage("§7" + bakeKey + ": " + plan.uniqueHeads() + " unique head"
                        + (plan.uniqueHeads() == 1 ? "" : "s") + " across "
                        + plan.totalChunks() + " chunks - all served from cache, no uploads.");
            } else {
                sender.sendMessage("§7" + bakeKey + ": uploading " + plan.needUpload() + " new skin"
                        + (plan.needUpload() == 1 ? "" : "s") + " to MineSkin §8(" + plan.uniqueHeads()
                        + " unique head" + (plan.uniqueHeads() == 1 ? "" : "s") + " across "
                        + plan.totalChunks() + " chunks; " + cacheHits + " cache hit"
                        + (cacheHits == 1 ? "" : "s") + ").");
            }
        })).whenComplete((ok, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            long elapsed = System.currentTimeMillis() - start;
            if (ex != null) {
                sender.sendMessage("§cBake threw: " + ex.getMessage());
            } else if (ok == null || !ok) {
                if (!bakeKey.isTinted()) {
                    sender.sendMessage("§cBake failed for " + bakeKey
                            + " (unsupported block, missing biome tint, partial upload, or error - see console).");
                    sender.sendMessage("§7If this is a tinted block (grass, leaves, water), pass §ftint:#RRGGBB§7.");
                } else {
                    sender.sendMessage("§cBake failed for " + bakeKey
                            + " (unsupported block, partial upload, or error - see console).");
                }
            } else {
                sender.sendMessage("§aBake complete for " + bakeKey + " §8(" + elapsed + " ms)");
            }
        }));
        return true;
    }

    private boolean handleConfig(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§6Tessera config options §8(§7/tessera config <key> [value]§8):");
            sender.sendMessage("§f  chunkGridSize              §71|2|4|8|16  §8(restart required)");
            sender.sendMessage("§f  animation.mode             §7progress|post-break");
            sender.sendMessage("§f  animation.style            §7shrink|pop");
            sender.sendMessage("§f  animation.durationMs       §7int (ms)");
            sender.sendMessage("§f  animation.waveWindow       §70.0–1.0");
            sender.sendMessage("§f  animation.fillInterior     §7true|false");
            sender.sendMessage("§f  transport                  §7packet|bukkit  §8(restart required)");
            sender.sendMessage("§f  debug                      §7true|false");
            sender.sendMessage("§f  limits.maxConcurrentFakeBlocks  §7int");
            sender.sendMessage("§f  progress.clientHideRealBlock    §7true|false");
            sender.sendMessage("§f  progress.smoothInterpolation    §7true|false");
            sender.sendMessage("§f  interaction.startOnLeftClick    §7true|false");
            sender.sendMessage("§f  interaction.eagerPreload        §7true|false");
            sender.sendMessage("§f  interaction.minBreakDurationMs  §7int (ms)");
            sender.sendMessage("§f  interaction.leftClickGraceMs    §7int (ms)");
            return true;
        }

        String key = args[1].toLowerCase(Locale.ROOT);
        TesseraConfig cfg = plugin.tesseraConfig();

        if (args.length < 3) {
            String current = configCurrentValue(key, cfg);
            if (current == null) {
                sender.sendMessage("§cUnknown config key: " + args[1]);
                sender.sendMessage("§7Run §f/tessera config§7 to list all options.");
                return true;
            }
            sender.sendMessage("§7" + args[1] + " §8= §f" + current);
            return true;
        }

        String value = args[2];
        try {
            applyConfigValue(key, value);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§c" + e.getMessage());
            return true;
        }
        plugin.saveConfig();
        try {
            plugin.reloadTesseraConfig();
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cValue saved but config reload failed: " + e.getMessage());
            return true;
        }
        sender.sendMessage("§aSet §f" + args[1] + " §a= §f" + value + "§a, config reloaded.");
        if (key.equals("chunkgridsize") || key.equals("transport")) {
            sender.sendMessage("§e" + args[1] + " requires a server restart to fully take effect.");
        }
        return true;
    }

    private String configCurrentValue(String key, TesseraConfig cfg) {
        return switch (key) {
            case "chunkgridsize"                    -> String.valueOf(cfg.chunkGridSize());
            case "animation.mode"                   -> cfg.animationMode().name().toLowerCase(Locale.ROOT);
            case "animation.style"                  -> cfg.collapseStyle().name().toLowerCase(Locale.ROOT);
            case "animation.durationms"             -> String.valueOf(cfg.effectDurationMs());
            case "animation.wavewindow"             -> String.valueOf(cfg.waveWindow());
            case "animation.fillinterior"           -> String.valueOf(cfg.fillInterior());
            case "transport"                        -> cfg.transport().name().toLowerCase(Locale.ROOT);
            case "debug"                            -> String.valueOf(cfg.debug());
            case "limits.maxconcurrentfakeblocks"   -> String.valueOf(cfg.maxConcurrentFakeBlocks());
            case "progress.clienthiderealblock"     -> String.valueOf(cfg.clientHideRealBlock());
            case "progress.smoothinterpolation"     -> String.valueOf(cfg.smoothInterpolation());
            case "interaction.startonleftclick"     -> String.valueOf(cfg.startOnLeftClick());
            case "interaction.eagerpreload"         -> String.valueOf(cfg.eagerPreload());
            case "interaction.minbreakdurationms"   -> String.valueOf(cfg.minBreakDurationMs());
            case "interaction.leftclickgracems"     -> String.valueOf(cfg.leftClickGraceMs());
            default                                 -> null;
        };
    }

    private void applyConfigValue(String key, String value) {
        var yaml = plugin.getConfig();
        switch (key) {
            case "chunkgridsize" -> {
                int v = cfgParseInt(value, "chunkGridSize");
                if (v < 1 || v > 16 || 16 % v != 0)
                    throw new IllegalArgumentException("chunkGridSize must be one of 1, 2, 4, 8, 16; got " + v);
                yaml.set("chunkGridSize", v);
            }
            case "animation.mode" -> {
                cfgValidateOneOf(value, "animation.mode", "progress", "post-break");
                yaml.set("animation.mode", value.toLowerCase(Locale.ROOT));
            }
            case "animation.style" -> {
                cfgValidateOneOf(value, "animation.style", "shrink", "pop");
                yaml.set("animation.style", value.toLowerCase(Locale.ROOT));
            }
            case "animation.durationms" -> {
                int v = cfgParseInt(value, "animation.durationMs");
                if (v < 0) throw new IllegalArgumentException("animation.durationMs must be ≥ 0");
                yaml.set("animation.durationMs", v);
            }
            case "animation.wavewindow" -> {
                double v = cfgParseDouble(value, "animation.waveWindow");
                if (v < 0 || v > 1) throw new IllegalArgumentException("animation.waveWindow must be between 0.0 and 1.0");
                yaml.set("animation.waveWindow", v);
            }
            case "animation.fillinterior"           -> yaml.set("animation.fillInterior",                cfgParseBool(value, "animation.fillInterior"));
            case "transport" -> {
                cfgValidateOneOf(value, "transport", "packet", "bukkit");
                yaml.set("transport", value.toLowerCase(Locale.ROOT));
            }
            case "debug"                            -> yaml.set("debug",                                 cfgParseBool(value, "debug"));
            case "limits.maxconcurrentfakeblocks" -> {
                int v = cfgParseInt(value, "limits.maxConcurrentFakeBlocks");
                if (v < 1) throw new IllegalArgumentException("limits.maxConcurrentFakeBlocks must be ≥ 1");
                yaml.set("limits.maxConcurrentFakeBlocks", v);
            }
            case "progress.clienthiderealblock"     -> yaml.set("progress.clientHideRealBlock",          cfgParseBool(value, "progress.clientHideRealBlock"));
            case "progress.smoothinterpolation"     -> yaml.set("progress.smoothInterpolation",          cfgParseBool(value, "progress.smoothInterpolation"));
            case "interaction.startonleftclick"     -> yaml.set("interaction.startOnLeftClick",          cfgParseBool(value, "interaction.startOnLeftClick"));
            case "interaction.eagerpreload"         -> yaml.set("interaction.eagerPreload",              cfgParseBool(value, "interaction.eagerPreload"));
            case "interaction.minbreakdurationms" -> {
                int v = cfgParseInt(value, "interaction.minBreakDurationMs");
                if (v < 0) throw new IllegalArgumentException("interaction.minBreakDurationMs must be ≥ 0");
                yaml.set("interaction.minBreakDurationMs", v);
            }
            case "interaction.leftclickgracems" -> {
                long v = cfgParseLong(value, "interaction.leftClickGraceMs");
                if (v < 0) throw new IllegalArgumentException("interaction.leftClickGraceMs must be ≥ 0");
                yaml.set("interaction.leftClickGraceMs", v);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown config key: " + key + ". Run /tessera config to list all options.");
        }
    }

    private static int cfgParseInt(String s, String key) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer; got: " + s); }
    }

    private static long cfgParseLong(String s, String key) {
        try { return Long.parseLong(s); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer; got: " + s); }
    }

    private static double cfgParseDouble(String s, String key) {
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be a number; got: " + s); }
    }

    private static boolean cfgParseBool(String s, String key) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1", "on"   -> true;
            case "false", "no", "0", "off"  -> false;
            default -> throw new IllegalArgumentException(key + " must be true or false; got: " + s);
        };
    }

    private static void cfgValidateOneOf(String value, String key, String... options) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String opt : options) if (lower.equals(opt)) return;
        throw new IllegalArgumentException(key + " must be one of: "
                + String.join(", ", options) + "; got: " + value);
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
            case "status"       -> handleDebugStatus(sender);
            case "debugtex"     -> handleDebugTex(sender, args);
            case "permutations" -> handleDebugPermutations(sender, args);
            case "dumppng"      -> handleDebugDumpPng(sender, args);
            default -> {
                sender.sendMessage("§cUnknown debug target: " + args[1]);
                yield true;
            }
        };
    }

    private boolean handleDebugStatus(CommandSender sender) {
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
