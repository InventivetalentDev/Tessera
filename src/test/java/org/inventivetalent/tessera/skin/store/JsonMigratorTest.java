package org.inventivetalent.tessera.skin.store;

import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the JSON → tsra migration so existing runtime caches survive
 * a plugin upgrade. The shapes here mirror what the legacy
 * {@code heads-{N}.json} schema actually wrote, so adapting the migrator
 * to a different layout will surface as a test failure.
 */
class JsonMigratorTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    @Test
    void migratesBundledShape(@TempDir Path tmp) throws Exception {
        String legacy = """
                {
                  "version": "1.21.4",
                  "gridN": 4,
                  "skins": {
                    "h-stone": { "value": "v1", "signature": "s1", "mineskinUuid": "uuid-1" },
                    "h-edge":  { "value": "v2", "signature": "s2" }
                  },
                  "blocks": {
                    "minecraft:stone": {
                      "chunks": {
                        "0,0,0": { "skinHash": "h-stone" },
                        "1,0,0": { "skinHash": "h-edge"  }
                      }
                    }
                  }
                }
                """;
        Path jsonFile = tmp.resolve("heads-4.json");
        Files.writeString(jsonFile, legacy, StandardCharsets.UTF_8);
        TsraFolderStore target = new TsraFolderStore(LOG, tmp.resolve("out.tsra"));

        int migrated = JsonMigrator.migrate(LOG, jsonFile, target, 4, "1.21.4");
        assertEquals(1, migrated);

        assertEquals(4, target.manifest().orElseThrow().gridN());

        BakeKey stone = BakeKey.untinted(BlockKey.of("minecraft:stone"));
        TsraFormat.Block b = target.readBlock(stone).orElseThrow();
        assertEquals("h-stone", b.chunkHashes().get(new ChunkCoord(0, 0, 0)));
        assertEquals("h-edge", b.chunkHashes().get(new ChunkCoord(1, 0, 0)));
        assertEquals("uuid-1", target.readSkin("h-stone").orElseThrow().mineskinUuid());
        // Signature-only skin (no UUID) migrates cleanly.
        assertEquals(null, target.readSkin("h-edge").orElseThrow().mineskinUuid());
    }

    @Test
    void migratesRuntimeShapeWithTintedKeysAndVariants(@TempDir Path tmp) throws Exception {
        // Runtime caches use the BakeKey toString form (".../<block>#<hex>")
        // for tinted entries and ship variant rotations.
        String legacy = """
                {
                  "version": "1.21.4",
                  "gridN": 4,
                  "skins": {
                    "h-x": { "value": "v", "signature": "s" }
                  },
                  "blocks": {
                    "minecraft:oak_log": {
                      "chunks": { "0,0,0": { "skinHash": "h-x" } },
                      "variants": {
                        "axis=x": { "x": 90, "y": 90 },
                        "axis=z": { "x": 90 }
                      }
                    },
                    "minecraft:grass_block#7fbf2e": {
                      "chunks": { "0,0,0": { "skinHash": "h-x" } }
                    }
                  }
                }
                """;
        Path jsonFile = tmp.resolve("heads-4.json");
        Files.writeString(jsonFile, legacy, StandardCharsets.UTF_8);
        TsraFolderStore target = new TsraFolderStore(LOG, tmp.resolve("out.tsra"));

        assertEquals(2, JsonMigrator.migrate(LOG, jsonFile, target, 4, "1.21.4"));

        BakeKey logKey = BakeKey.untinted(BlockKey.of("minecraft:oak_log"));
        TsraFormat.Block log = target.readBlock(logKey).orElseThrow();
        assertEquals(90, log.variantRotations().get("axis=x").xDeg());
        assertEquals(90, log.variantRotations().get("axis=x").yDeg());
        // y omitted in legacy JSON → defaults to 0.
        assertEquals(0, log.variantRotations().get("axis=z").yDeg());

        BakeKey grassTinted = BakeKey.parse("minecraft:grass_block#7fbf2e");
        assertTrue(target.readBlock(grassTinted).isPresent(),
                "tinted runtime entries must round-trip via BakeKey.parse");
    }

    @Test
    void skipsMalformedBlockKeyWithoutAborting(@TempDir Path tmp) throws Exception {
        String legacy = """
                {
                  "version": "1.21.4",
                  "gridN": 4,
                  "skins": { "h": { "value": "v", "signature": "s" } },
                  "blocks": {
                    "::bogus::": { "chunks": { "0,0,0": { "skinHash": "h" } } },
                    "minecraft:stone": { "chunks": { "0,0,0": { "skinHash": "h" } } }
                  }
                }
                """;
        Path jsonFile = tmp.resolve("heads-4.json");
        Files.writeString(jsonFile, legacy, StandardCharsets.UTF_8);
        TsraFolderStore target = new TsraFolderStore(LOG, tmp.resolve("out.tsra"));

        // Migration should succeed for the well-formed entry and skip the bogus one.
        int migrated = JsonMigrator.migrate(LOG, jsonFile, target, 4, "1.21.4");
        assertEquals(1, migrated);
        assertTrue(target.readBlock(BakeKey.untinted(BlockKey.of("minecraft:stone"))).isPresent());
    }
}
