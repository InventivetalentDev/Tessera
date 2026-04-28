package io.tessera.skin;

import io.tessera.core.FaceDir;
import io.tessera.core.HeadFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins down the canonical FaceDir ↔ HeadFace mapping. RIGHT↔WEST and LEFT↔EAST
 * are intentional — see HeadSkinPacker.headFaceToFaceDir's javadoc and PR #4.
 * If this test fails, non-uniform blocks like oak_log will mis-render their
 * east/west faces.
 */
class HeadSkinPackerMappingTest {

    @Test
    void headFaceToFaceDirCanonicalMapping() {
        assertEquals(FaceDir.UP, HeadSkinPacker.headFaceToFaceDir(HeadFace.TOP));
        assertEquals(FaceDir.DOWN, HeadSkinPacker.headFaceToFaceDir(HeadFace.BOTTOM));
        assertEquals(FaceDir.SOUTH, HeadSkinPacker.headFaceToFaceDir(HeadFace.FRONT));
        assertEquals(FaceDir.NORTH, HeadSkinPacker.headFaceToFaceDir(HeadFace.BACK));
        assertEquals(FaceDir.WEST, HeadSkinPacker.headFaceToFaceDir(HeadFace.RIGHT));
        assertEquals(FaceDir.EAST, HeadSkinPacker.headFaceToFaceDir(HeadFace.LEFT));
    }

    @Test
    void faceDirToHeadFaceIsInverseOfHeadFaceToFaceDir() {
        for (HeadFace hf : HeadFace.values()) {
            FaceDir d = HeadSkinPacker.headFaceToFaceDir(hf);
            assertEquals(hf, HeadSkinPacker.faceDirToHeadFace(d),
                    "round-trip failed for " + hf);
        }
        for (FaceDir d : FaceDir.values()) {
            HeadFace hf = HeadSkinPacker.faceDirToHeadFace(d);
            assertEquals(d, HeadSkinPacker.headFaceToFaceDir(hf),
                    "round-trip failed for " + d);
        }
    }
}
