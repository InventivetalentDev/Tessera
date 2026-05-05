package org.inventivetalent.tessera.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import javax.imageio.ImageIO;

public final class Hashing {

    private Hashing() {}

    public static String sha256OfStrings(List<String> parts) {
        MessageDigest md = sha256();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) md.update((byte) '|');
            md.update(parts.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static String sha256OfImage(BufferedImage img) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return HexFormat.of().formatHex(sha256().digest(baos.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to hash image", e);
        }
    }

    public static String sha256OfBytes(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 should always be available", e);
        }
    }
}
