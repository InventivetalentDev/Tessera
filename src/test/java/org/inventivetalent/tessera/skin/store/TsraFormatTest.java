package org.inventivetalent.tessera.skin.store;

import org.inventivetalent.tessera.assets.model.ModelResolver.VariantRotation;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tight unit tests for the binary {@code .tsra} envelope. Cover what is
 * load-bearing for backwards compatibility: magic / version / type bytes,
 * round-tripping each payload type, and rejecting tampered headers.
 */
class TsraFormatTest {

    @Test
    void manifestRoundTrips() throws IOException {
        TsraFormat.Manifest in = new TsraFormat.Manifest(4, "1.21.4", "test-producer");
        byte[] bytes = TsraFormat.writeManifest(in);
        assertEquals(TsraFormat.TYPE_MANIFEST, TsraFormat.payloadType(bytes));
        TsraFormat.Manifest out = TsraFormat.readManifest(bytes);
        assertEquals(in, out);
    }

    @Test
    void blockRoundTripsWithTintAndVariants() throws IOException {
        BakeKey key = new BakeKey(BlockKey.of("minecraft:grass_block"), 0xFF7fbf2e);
        Map<ChunkCoord, String> chunks = new TreeMap<>();
        chunks.put(new ChunkCoord(0, 0, 0), "h-corner");
        chunks.put(new ChunkCoord(1, 0, 0), "h-edge");
        chunks.put(new ChunkCoord(3, 3, 3), "h-far-corner");
        Map<String, VariantRotation> variants = new LinkedHashMap<>();
        variants.put("axis=x", new VariantRotation(90, 90));
        variants.put("facing=west", new VariantRotation(0, -90));

        byte[] bytes = TsraFormat.writeBlock(new TsraFormat.Block(key, chunks, variants));
        TsraFormat.Block out = TsraFormat.readBlock(bytes);
        assertEquals(key, out.key());
        assertEquals(new TreeMap<>(chunks), new TreeMap<>(out.chunkHashes()));
        assertEquals(new TreeMap<>(variants), new TreeMap<>(out.variants()));
    }

    @Test
    void skinRoundTripsWithAndWithoutUuid() throws IOException {
        TsraFormat.Skin withUuid = new TsraFormat.Skin("h", "value-bytes", "sig-bytes", "uuid-bytes");
        TsraFormat.Skin withoutUuid = new TsraFormat.Skin("h2", "v2", "s2", null);
        assertEquals(withUuid, TsraFormat.readSkin("h", TsraFormat.writeSkin(withUuid)));
        assertEquals(withoutUuid, TsraFormat.readSkin("h2", TsraFormat.writeSkin(withoutUuid)));
    }

    @Test
    void rejectsBadMagic() {
        byte[] bytes = new byte[]{0, 0, 0, 0, 1, TsraFormat.TYPE_MANIFEST, 0, 0};
        assertThrows(TsraFormat.MalformedTsraException.class,
                () -> TsraFormat.readManifest(bytes));
    }

    @Test
    void rejectsUnsupportedFormatVersion() throws IOException {
        byte[] bytes = TsraFormat.writeManifest(new TsraFormat.Manifest(4, "1.21", "x"));
        bytes[4] = (byte) 99; // bump version
        assertThrows(TsraFormat.MalformedTsraException.class,
                () -> TsraFormat.readManifest(bytes));
    }

    @Test
    void rejectsWrongPayloadType() throws IOException {
        byte[] bytes = TsraFormat.writeManifest(new TsraFormat.Manifest(4, "1.21", "x"));
        assertThrows(TsraFormat.MalformedTsraException.class,
                () -> TsraFormat.readBlock(bytes));
    }

    @Test
    void blockFilenameRoundTrips() {
        BakeKey untinted = BakeKey.untinted(BlockKey.of("minecraft:oak_log"));
        BakeKey tinted = new BakeKey(BlockKey.of("minecraft:grass_block"), 0xFF7fbf2e);
        assertEquals(untinted, TsraFormat.parseBlockFilename(TsraFormat.blockFilename(untinted)));
        assertEquals(tinted, TsraFormat.parseBlockFilename(TsraFormat.blockFilename(tinted)));
    }

    @Test
    void skinPathShardsByFirstTwoChars() {
        assertEquals("skins/ab/abcdef.tsra", TsraFormat.skinPath("abcdef"));
        // Single-char hashes still get a shard (the wildcard) so the layout
        // works for the test fixtures' "h-obs"-style human hashes.
        assertEquals("skins/_/x.tsra", TsraFormat.skinPath("x"));
    }

    @Test
    void payloadBytesDifferBetweenTypes() throws IOException {
        byte[] manifestBytes = TsraFormat.writeManifest(new TsraFormat.Manifest(4, "1.21", "p"));
        byte[] skinBytes = TsraFormat.writeSkin(new TsraFormat.Skin("h", "v", "s", null));
        // Magic + version are identical; type byte at offset 5 must differ.
        assertArrayEquals(new byte[]{'T', 'S', 'R', 'A'},
                new byte[]{manifestBytes[0], manifestBytes[1], manifestBytes[2], manifestBytes[3]});
        assertEquals((byte) 1, manifestBytes[4]);
        assertEquals((byte) 1, skinBytes[4]);
        assertEquals(TsraFormat.TYPE_MANIFEST, manifestBytes[5]);
        assertEquals(TsraFormat.TYPE_SKIN, skinBytes[5]);
    }
}
