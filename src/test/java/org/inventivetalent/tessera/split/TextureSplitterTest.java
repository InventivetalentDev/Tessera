package org.inventivetalent.tessera.split;

import org.inventivetalent.tessera.assets.model.BlockModel;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkSpec;
import org.inventivetalent.tessera.core.FaceDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextureSplitterTest {

    /**
     * The splitter is the entry point that turns a configurable gridN into a
     * concrete chunk lattice — every other stage of the bake pipeline takes
     * the {@link ChunkSpec} list it produces. The visible-cell counts here
     * mirror {@code FaceDirTest#visibleChunkCountMatchesFormula}: keeping both
     * tests prevents either layer from drifting silently.
     */
    @ParameterizedTest
    @CsvSource({
            "1,    1",
            "2,    8",
            "4,   56",
            "8,  296",
            "16, 1352",
    })
    void splitProducesExpectedVisibleChunkCount(int gridN, int expected) {
        List<ChunkSpec> chunks = new TextureSplitter().split(uniformModel(Color.WHITE), gridN);
        assertEquals(expected, chunks.size());
    }

    @ParameterizedTest
    @CsvSource({"1, 16", "2, 8", "4, 4", "8, 2", "16, 1"})
    void tileSizeIs16OverGridN(int gridN, int expectedTilePx) {
        for (ChunkSpec spec : new TextureSplitter().split(uniformModel(Color.WHITE), gridN)) {
            for (FaceDir d : spec.outwardFaces()) {
                BufferedImage tile = spec.tile(d);
                assertEquals(expectedTilePx, tile.getWidth(), "wrong tile width for " + d);
                assertEquals(expectedTilePx, tile.getHeight(), "wrong tile height for " + d);
            }
        }
    }

    @Test
    void singleChunkAtGridN1HasAllSixOutwardFaces() {
        List<ChunkSpec> chunks = new TextureSplitter().split(uniformModel(Color.WHITE), 1);
        assertEquals(1, chunks.size());
        assertEquals(6, chunks.get(0).outwardCount());
    }

    @Test
    void rejectsGridNThatDoesNotDivideTextureSize() {
        BlockModel model = uniformModel(Color.WHITE);
        TextureSplitter splitter = new TextureSplitter();
        assertThrows(IllegalArgumentException.class, () -> splitter.split(model, 3));
        assertThrows(IllegalArgumentException.class, () -> splitter.split(model, 5));
        assertThrows(IllegalArgumentException.class, () -> splitter.split(model, 7));
    }

    @Test
    void rejectsZeroOrNegativeGridN() {
        BlockModel model = uniformModel(Color.WHITE);
        TextureSplitter splitter = new TextureSplitter();
        assertThrows(IllegalArgumentException.class, () -> splitter.split(model, 0));
        assertThrows(IllegalArgumentException.class, () -> splitter.split(model, -1));
    }

    private static BlockModel uniformModel(Color color) {
        EnumMap<FaceDir, BufferedImage> faces = new EnumMap<>(FaceDir.class);
        for (FaceDir d : FaceDir.values()) {
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    img.setRGB(x, y, color.getRGB());
                }
            }
            faces.put(d, img);
        }
        return new BlockModel(BlockKey.of("minecraft:test"), faces, false, "test");
    }
}
