package org.inventivetalent.tessera.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class HashingTest {

    @Test
    void sha256OfBytesMatchesKnownVector() {
        // Known SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        String hex = Hashing.sha256OfBytes("abc".getBytes());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex);
    }

    @Test
    void sha256OfStringsIsDeterministic() {
        List<String> parts = List.of("foo", "bar", "baz");
        assertEquals(Hashing.sha256OfStrings(parts), Hashing.sha256OfStrings(parts));
    }

    @Test
    void sha256OfStringsUsesPipeSeparator() {
        // ["a","b"] hashed as "a|b" is distinct from ["ab"] which has no separator.
        assertNotEquals(
                Hashing.sha256OfStrings(List.of("a", "b")),
                Hashing.sha256OfStrings(List.of("ab")));
    }

    @Test
    void sha256OfImageIsDeterministic() {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            img.setRGB(x, y, 0xFF000000 | ((x * 32) << 16) | ((y * 32) << 8));
        }
        String h1 = Hashing.sha256OfImage(img);
        String h2 = Hashing.sha256OfImage(img);
        assertEquals(h1, h2);
        assertEquals(64, h1.length()); // 32 bytes hex-encoded
    }

    @Test
    void sha256OfImageDistinguishesContent() {
        BufferedImage a = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        BufferedImage b = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        b.setRGB(0, 0, 0xFFFFFFFF);
        assertNotEquals(Hashing.sha256OfImage(a), Hashing.sha256OfImage(b));
    }
}
