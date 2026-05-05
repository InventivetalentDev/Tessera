package org.inventivetalent.tessera.plugin;

import be.seeseemelk.mockbukkit.MockBukkit;
import org.inventivetalent.tessera.plugin.TesseraConfig.AnimationMode;
import org.inventivetalent.tessera.plugin.TesseraConfig.CollapseStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TesseraConfig#from(org.bukkit.configuration.file.FileConfiguration)}
 * is the only path users have to influence runtime behaviour, so it's worth
 * locking down: defaults, the new nested layout, the legacy flat-key
 * fallback, enum aliases, and validation.
 *
 * <p>MockBukkit is started once for the class so {@link YamlConfiguration}'s
 * Bukkit-side serializer init runs cleanly. None of these tests touch the
 * server itself — we only need {@code Bukkit.setServer} to have happened.
 */
class TesseraConfigTest {

    @BeforeAll
    static void boot() {
        MockBukkit.mock();
    }

    @AfterAll
    static void shutdown() {
        MockBukkit.unmock();
    }

    private static TesseraConfig parse(String yaml) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.loadFromString(yaml);
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new AssertionError("Test YAML is malformed", e);
        }
        return TesseraConfig.from(cfg);
    }

    @Test
    void emptyConfigYieldsAllDefaults() {
        TesseraConfig cfg = parse("");
        assertEquals(4, cfg.chunkGridSize());
        assertEquals(8, cfg.maxConcurrentFakeBlocks());
        assertEquals(AnimationMode.PROGRESS, cfg.animationMode());
        assertEquals(CollapseStyle.POP, cfg.collapseStyle());
        assertEquals(600, cfg.effectDurationMs());
        assertEquals(0.25d, cfg.waveWindow());
        assertFalse(cfg.fillInterior());
        assertTrue(cfg.clientHideRealBlock());
        assertEquals(0.02d, cfg.progressMinDelta());
        assertTrue(cfg.smoothInterpolation());
        assertTrue(cfg.startOnLeftClick());
        assertEquals(500L, cfg.leftClickGraceMs());
        assertFalse(cfg.debug());
        assertEquals("", cfg.mineskinApiKey());
        assertTrue(cfg.enabledMaterials().contains("*"));
        assertTrue(cfg.disabledMaterials().contains("minecraft:water"));
    }

    @Test
    void nestedSectionsOverrideDefaults() {
        TesseraConfig cfg = parse("""
                chunkGridSize: 8
                animation:
                  mode: post-break
                  style: pop
                  durationMs: 1200
                  waveWindow: 0.5
                  fillInterior: true
                progress:
                  clientHideRealBlock: false
                  minDelta: 0.1
                  smoothInterpolation: false
                interaction:
                  startOnLeftClick: false
                  leftClickGraceMs: 250
                limits:
                  maxConcurrentFakeBlocks: 16
                mineskin:
                  apiKey: "abcdef"
                materials:
                  enabled:
                    - "minecraft:stone"
                    - "minecraft:dirt"
                  disabled:
                    - "minecraft:bedrock"
                debug: true
                """);
        assertEquals(8, cfg.chunkGridSize());
        assertEquals(AnimationMode.POST_BREAK, cfg.animationMode());
        assertEquals(CollapseStyle.POP, cfg.collapseStyle());
        assertEquals(1200, cfg.effectDurationMs());
        assertEquals(0.5d, cfg.waveWindow());
        assertTrue(cfg.fillInterior());
        assertFalse(cfg.clientHideRealBlock());
        assertEquals(0.1d, cfg.progressMinDelta());
        assertFalse(cfg.smoothInterpolation());
        assertFalse(cfg.startOnLeftClick());
        assertEquals(250L, cfg.leftClickGraceMs());
        assertEquals(16, cfg.maxConcurrentFakeBlocks());
        assertEquals("abcdef", cfg.mineskinApiKey());
        assertTrue(cfg.enabledMaterials().contains("minecraft:stone"));
        assertTrue(cfg.enabledMaterials().contains("minecraft:dirt"));
        assertTrue(cfg.disabledMaterials().contains("minecraft:bedrock"));
        assertTrue(cfg.debug());
    }

    @Test
    void legacyFlatKeysAreStillAccepted() {
        // The pre-restructure config layout. Existing user configs must keep
        // working until they migrate.
        TesseraConfig cfg = parse("""
                mineskinApiKey: "legacy"
                animationMode: post-break
                collapseStyle: pop
                effectDurationMs: 900
                waveWindow: 0.4
                fillInterior: true
                clientHideRealBlock: false
                progressMinDelta: 0.05
                smoothInterpolation: false
                startOnLeftClick: false
                leftClickGraceMs: 333
                maxConcurrentFakeBlocks: 12
                enabledMaterials: ["minecraft:gold_block"]
                disabledMaterials: ["minecraft:obsidian"]
                """);
        assertEquals("legacy", cfg.mineskinApiKey());
        assertEquals(AnimationMode.POST_BREAK, cfg.animationMode());
        assertEquals(CollapseStyle.POP, cfg.collapseStyle());
        assertEquals(900, cfg.effectDurationMs());
        assertEquals(0.4d, cfg.waveWindow());
        assertTrue(cfg.fillInterior());
        assertFalse(cfg.clientHideRealBlock());
        assertEquals(0.05d, cfg.progressMinDelta());
        assertFalse(cfg.smoothInterpolation());
        assertFalse(cfg.startOnLeftClick());
        assertEquals(333L, cfg.leftClickGraceMs());
        assertEquals(12, cfg.maxConcurrentFakeBlocks());
        assertTrue(cfg.enabledMaterials().contains("minecraft:gold_block"));
        assertTrue(cfg.disabledMaterials().contains("minecraft:obsidian"));
    }

    @Test
    void nestedKeysWinOverLegacyWhenBothSet() {
        // If a user adds nested keys without removing legacy ones (e.g. an
        // automated migration that just appends), nested wins so the user's
        // newer choice takes effect.
        TesseraConfig cfg = parse("""
                animationMode: progress
                animation:
                  mode: post-break
                  style: pop
                collapseStyle: shrink
                progressMinDelta: 0.5
                progress:
                  minDelta: 0.01
                """);
        assertEquals(AnimationMode.POST_BREAK, cfg.animationMode());
        assertEquals(CollapseStyle.POP, cfg.collapseStyle());
        assertEquals(0.01d, cfg.progressMinDelta());
    }

    @Test
    void animationModeAcceptsCommonAliases() {
        assertEquals(AnimationMode.POST_BREAK,
                parse("animation:\n  mode: post-break\n").animationMode());
        assertEquals(AnimationMode.POST_BREAK,
                parse("animation:\n  mode: post_break\n").animationMode());
        assertEquals(AnimationMode.POST_BREAK,
                parse("animation:\n  mode: postbreak\n").animationMode());
        assertEquals(AnimationMode.POST_BREAK,
                parse("animation:\n  mode: POST-BREAK\n").animationMode());
        assertEquals(AnimationMode.PROGRESS,
                parse("animation:\n  mode: progress\n").animationMode());
    }

    @Test
    void collapseStyleAcceptsCommonAliases() {
        assertEquals(CollapseStyle.SHRINK, parse("animation:\n  style: shrink\n").collapseStyle());
        assertEquals(CollapseStyle.POP,    parse("animation:\n  style: pop\n").collapseStyle());
        assertEquals(CollapseStyle.POP,    parse("animation:\n  style: instant\n").collapseStyle());
        assertEquals(CollapseStyle.POP,    parse("animation:\n  style: disappear\n").collapseStyle());
        assertEquals(CollapseStyle.POP,    parse("animation:\n  style: POP\n").collapseStyle());
    }

    @Test
    void invalidAnimationModeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("animation:\n  mode: spinny\n"));
    }

    @Test
    void invalidCollapseStyleThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("animation:\n  style: explode\n"));
    }

    @Test
    void invalidGridSizeThrows() {
        // 16 % 5 != 0 - texture splitter would fail downstream, so we reject
        // at parse time.
        assertThrows(IllegalArgumentException.class,
                () -> parse("chunkGridSize: 5\n"));
    }

    @Test
    void enablesAppliesWildcardAndExplicitDisable() {
        TesseraConfig cfg = parse("""
                materials:
                  enabled: ["*"]
                  disabled: ["minecraft:bedrock"]
                """);
        assertTrue(cfg.enables("minecraft:stone"));
        assertTrue(cfg.enables("minecraft:dirt"));
        assertFalse(cfg.enables("minecraft:bedrock"));
        // Disabled list is normalized to lowercase + namespaced
        assertFalse(cfg.enables("MINECRAFT:BEDROCK"));
    }

    @Test
    void enablesNamespacesPlainKeysToMinecraft() {
        TesseraConfig cfg = parse("""
                materials:
                  enabled: ["stone"]
                  disabled: []
                """);
        assertTrue(cfg.enables("minecraft:stone"));
        assertFalse(cfg.enables("minecraft:dirt"));
    }

    @Test
    void enablesIsCaseInsensitive() {
        TesseraConfig cfg = parse("""
                materials:
                  enabled: ["MINECRAFT:GOLD_BLOCK"]
                  disabled: []
                """);
        assertTrue(cfg.enables("minecraft:gold_block"));
        assertTrue(cfg.enables("MineCraft:Gold_Block"));
    }
}
