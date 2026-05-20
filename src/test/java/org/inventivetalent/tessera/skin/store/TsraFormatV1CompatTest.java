package org.inventivetalent.tessera.skin.store;

import org.inventivetalent.tessera.assets.model.BlockModel;
import org.inventivetalent.tessera.assets.model.ModelResolver.VariantRotation;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.core.FaceDir;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bundled {@code heads-4.ztsra} pre-multishape shipped in v1 layout: one
 * chunk map per BakeKey, no per-chunk outward mask, variant rotations
 * without shape binding. The v2 reader must accept it transparently and
 * synthesize the missing pieces (default shape key, outward masks via cube
 * boundary, variant → default-shape bindings) so servers don't lose their
 * existing catalogs on plugin upgrade.
 */
class TsraFormatV1CompatTest {

    @Test
    void readsV1BlockAsSingleDefaultShape() throws IOException {
        // Build a v1-format Block by hand: write the canonical header with
        // version=1 then the v1 body.
        BakeKey key = BakeKey.untinted(BlockKey.of("minecraft:stone"));
        byte[] v1Body = buildV1BlockBody(key,
                Map.of(new ChunkCoord(0, 0, 0), "hash-corner",
                        new ChunkCoord(3, 3, 3), "hash-far"),
                Map.of("axis=x", new VariantRotation(90, 90)));

        TsraFormat.Block block = TsraFormat.readBlock(v1Body, 4);
        assertEquals(key, block.key());
        assertEquals(1, block.shapes().size(),
                "v1 blocks synthesize a single default shape");
        assertTrue(block.shapes().containsKey(BlockModel.DEFAULT_SHAPE_KEY));

        TsraFormat.Shape shape = block.shapes().get(BlockModel.DEFAULT_SHAPE_KEY);
        assertEquals(2, shape.chunks().size());

        // Outward masks computed via cube boundary: (0,0,0) is the
        // DOWN/NORTH/WEST corner; (3,3,3) is the UP/SOUTH/EAST corner.
        EnumSet<FaceDir> corner = shape.chunks().get(new ChunkCoord(0, 0, 0)).outwardFaces();
        assertEquals(EnumSet.of(FaceDir.DOWN, FaceDir.NORTH, FaceDir.WEST), corner);
        EnumSet<FaceDir> farCorner = shape.chunks().get(new ChunkCoord(3, 3, 3)).outwardFaces();
        assertEquals(EnumSet.of(FaceDir.UP, FaceDir.SOUTH, FaceDir.EAST), farCorner);

        // Variants synthesize a binding to the default shape with the v1 rotation.
        TsraFormat.ShapeVariantBinding axisX = block.variants().get("axis=x");
        assertEquals(BlockModel.DEFAULT_SHAPE_KEY, axisX.shapeKey());
        assertEquals(new VariantRotation(90, 90), axisX.rotation());
    }

    @Test
    void v1BackwardCompatThroughBackwardAccessors() throws IOException {
        BakeKey key = BakeKey.untinted(BlockKey.of("minecraft:oak_log"));
        byte[] v1Body = buildV1BlockBody(key,
                Map.of(new ChunkCoord(1, 1, 1), "h"),
                Map.of("axis=z", new VariantRotation(90, 0)));
        TsraFormat.Block block = TsraFormat.readBlock(v1Body, 4);

        // Legacy projection still works on v1 input.
        assertEquals("h", block.chunkHashes().get(new ChunkCoord(1, 1, 1)));
        assertEquals(new VariantRotation(90, 0), block.variantRotations().get("axis=z"));
    }

    /**
     * Hand-roll a v1 Block body: header with {@code version=1}, then the
     * single-shape v1 layout (no outward masks, no shape key).
     */
    private static byte[] buildV1BlockBody(BakeKey key,
                                            Map<ChunkCoord, String> chunkHashes,
                                            Map<String, VariantRotation> variants) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(TsraFormat.MAGIC);
        out.writeByte(TsraFormat.FORMAT_VERSION_V1);
        out.writeByte(TsraFormat.TYPE_BLOCK);
        out.writeShort(0);

        byte[] keyBytes = key.toString().getBytes(StandardCharsets.UTF_8);
        out.writeShort(keyBytes.length);
        out.write(keyBytes);

        out.writeShort(chunkHashes.size());
        for (Map.Entry<ChunkCoord, String> e : chunkHashes.entrySet()) {
            ChunkCoord c = e.getKey();
            out.writeByte(c.x());
            out.writeByte(c.y());
            out.writeByte(c.z());
            byte[] h = e.getValue().getBytes(StandardCharsets.UTF_8);
            out.writeShort(h.length);
            out.write(h);
        }

        out.writeShort(variants.size());
        for (Map.Entry<String, VariantRotation> e : variants.entrySet()) {
            byte[] k = e.getKey().getBytes(StandardCharsets.UTF_8);
            out.writeShort(k.length);
            out.write(k);
            out.writeShort(e.getValue().xDeg());
            out.writeShort(e.getValue().yDeg());
        }
        return baos.toByteArray();
    }
}
