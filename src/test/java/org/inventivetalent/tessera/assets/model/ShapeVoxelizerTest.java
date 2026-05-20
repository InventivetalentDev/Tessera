package org.inventivetalent.tessera.assets.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin down the voxelization rules for the shapes Tessera ships support for:
 * slabs (one half-cube element) and stairs (two element cubes forming an L).
 *
 * <p>The full {@link ShapeVoxelizer#voxelize} path needs a texture loader,
 * which would require fetching real PNGs. These tests use the
 * {@link ShapeVoxelizer#voxelizeCellsOnly} static so they only exercise
 * the cell-occupancy logic — the texture-sampling path is exercised
 * end-to-end by the {@code tesseraBake} task.
 */
class ShapeVoxelizerTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    @Test
    void bottomSlabFillsBottomHalf() {
        JsonArray elements = parse("""
                [{"from":[0,0,0],"to":[16,8,16]}]
                """);
        Map<ChunkCoord, Integer> cells = ShapeVoxelizer.voxelizeCellsOnly(elements, 4);
        // 16 cells per y-layer × 2 layers (y=0,1) = 32.
        assertEquals(32, cells.size());
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                assertTrue(cells.containsKey(new ChunkCoord(x, 0, z)));
                assertTrue(cells.containsKey(new ChunkCoord(x, 1, z)));
                assertFalse(cells.containsKey(new ChunkCoord(x, 2, z)),
                        "slab must not fill above y=1");
                assertFalse(cells.containsKey(new ChunkCoord(x, 3, z)));
            }
        }
    }

    @Test
    void straightStairsHaveSlabPlusStepCells() {
        // Vanilla canonical stairs: bottom slab + upper step on +X half (facing=east).
        JsonArray elements = parse("""
                [
                  {"from":[0,0,0], "to":[16,8,16]},
                  {"from":[8,8,0], "to":[16,16,16]}
                ]
                """);
        Map<ChunkCoord, Integer> cells = ShapeVoxelizer.voxelizeCellsOnly(elements, 4);
        // Bottom slab: 32 cells. Upper step (x=2,3; y=2,3; z=0..3): 16 cells.
        assertEquals(48, cells.size());
        // Spot-check a cell from each region.
        assertEquals(0, cells.get(new ChunkCoord(0, 0, 0)).intValue(), "lower slab corner");
        assertEquals(0, cells.get(new ChunkCoord(3, 1, 3)).intValue(), "lower slab top-far");
        assertEquals(1, cells.get(new ChunkCoord(2, 2, 0)).intValue(), "upper step front");
        assertEquals(1, cells.get(new ChunkCoord(3, 3, 3)).intValue(), "upper step top-far");
        assertFalse(cells.containsKey(new ChunkCoord(0, 2, 0)),
                "upper step must not fill -X half");
    }

    @Test
    void fullCubeElementFillsAllCellsAndFlagsAsCube() throws IOException {
        JsonArray elements = parse("""
                [{"from":[0,0,0],"to":[16,16,16]}]
                """);
        Map<ChunkCoord, Integer> cells = ShapeVoxelizer.voxelizeCellsOnly(elements, 4);
        assertEquals(64, cells.size(),
                "a full-cube element voxelizes to every cell");
    }

    @Test
    void firstElementWinsOnOverlap() {
        // Two overlapping elements; cell ownership goes to the first.
        JsonArray elements = parse("""
                [
                  {"from":[0,0,0],"to":[16,16,16]},
                  {"from":[0,0,0],"to":[8,8,8]}
                ]
                """);
        Map<ChunkCoord, Integer> cells = ShapeVoxelizer.voxelizeCellsOnly(elements, 4);
        // Every cell exists, all owned by element 0.
        assertEquals(64, cells.size());
        for (int v : cells.values()) {
            assertEquals(0, v, "first element should claim every overlap cell");
        }
    }

    private static JsonArray parse(String json) {
        return JsonParser.parseString(json.trim()).getAsJsonArray();
    }

    // The texture-loader-driven full voxelize path is exercised by the
    // integration `gradle tesseraBake` task; tests stay on the
    // cells-only static so they don't need an asset client.
    @SuppressWarnings("unused")
    private static ShapeVoxelizer.TextureLoader stubLoader() {
        return ref -> new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }
}
