package org.inventivetalent.tessera.skin.store;

import org.inventivetalent.tessera.assets.model.ModelResolver.VariantRotation;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.skin.HeadsRegistry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Wire format for {@code .tsra} (Tessera registry artifact) files. One file
 * carries one payload — manifest, block index, or skin entry — and the layout
 * is the same in a {@code heads-{N}/} folder (one file per payload) and inside a
 * {@code .ztsra} zip (one entry per payload). The compact binary form
 * replaces the old monolithic {@code heads-{N}.json} so the registry can
 * keep only the hash index in memory and stream skin payloads (the
 * value/signature blobs are 1–2 KB each) on demand through a Caffeine cache.
 *
 * <p>Layout (all multi-byte ints are big-endian, all strings are UTF-8
 * prefixed by their byte length):
 * <pre>
 *   [4] magic            'T' 'S' 'R' 'A'
 *   [1] format version   = {@value #FORMAT_VERSION}
 *   [1] payload type     1 = manifest, 2 = block, 3 = skin
 *   [2] reserved (0)
 *   [...] type-specific body
 * </pre>
 *
 * <p>Manifest body: {@code [1 gridN][1 reserved][u16 mcVersionLen][bytes
 * mcVersion][u16 producerLen][bytes producer]}. Only the bundled
 * {@code .ztsra} ships a manifest; folder stores write one on first use so
 * the directory is self-describing.
 *
 * <p>Block body: {@code [u16 bakeKeyLen][bytes bakeKey]
 * [u16 chunkCount]{chunkCount × [1 x][1 y][1 z][u16 hashLen][bytes hash]}
 * [u16 variantCount]{variantCount × [u16 keyLen][bytes key][s16 xDeg][s16 yDeg]}}.
 * The bake-key string round-trips through {@link BakeKey#toString()} /
 * {@link BakeKey#parse(String)} so tinted variants ({@code "...#7fbf2eff"})
 * coexist with untinted blocks in the same store.
 *
 * <p>Skin body: {@code [1 flags][1 reserved][optional u16+bytes uuid]
 * [u32 valueLen][bytes value][u32 signatureLen][bytes signature]}. Bit 0
 * of {@code flags} is set when a MineSkin UUID is present; the field is
 * skipped entirely otherwise so anonymous bakes stay compact.
 *
 * <p>Forward-compatibility: readers reject unknown magic or format-version
 * with {@link MalformedTsraException} so a hand-edited or partial file is
 * surfaced rather than silently treated as empty. Newer minor versions can
 * append fields to bodies; readers stop at the documented field set, so
 * trailing bytes are ignored when {@code FORMAT_VERSION} matches.
 */
public final class TsraFormat {

    public static final int MAGIC = 0x54535241; // 'TSRA'
    public static final byte FORMAT_VERSION = 1;

    public static final byte TYPE_MANIFEST = 1;
    public static final byte TYPE_BLOCK = 2;
    public static final byte TYPE_SKIN = 3;

    public static final String MANIFEST_NAME = "manifest.tsra";
    public static final String BLOCKS_DIR = "blocks";
    public static final String SKINS_DIR = "skins";

    /** Per-file extension used for every payload inside a store (folder or zip). */
    public static final String FILE_EXTENSION = ".tsra";
    /** Extension for the zipped form of the store (bundled jar resource). */
    public static final String ZIP_EXTENSION = ".ztsra";

    private TsraFormat() {}

    public record Manifest(int gridN, String mcVersion, String producer) {}

    public record Block(BakeKey key,
                        Map<ChunkCoord, String> chunkHashes,
                        Map<String, VariantRotation> variants) {}

    public record Skin(String hash, String value, String signature, String mineskinUuid) {
        public HeadsRegistry.Entry toEntry() {
            return new HeadsRegistry.Entry(hash, value, signature, mineskinUuid);
        }

        public static Skin from(HeadsRegistry.Entry e) {
            return new Skin(e.skinHash(), e.textureValue(), e.textureSignature(), e.mineskinUuid());
        }
    }

    public static class MalformedTsraException extends IOException {
        public MalformedTsraException(String msg) { super(msg); }
    }

    // ── Manifest ─────────────────────────────────────────────────────────────

    public static byte[] writeManifest(Manifest m) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        DataOutputStream out = writeHeader(baos, TYPE_MANIFEST);
        if (m.gridN() < 0 || m.gridN() > 255) {
            throw new IllegalArgumentException("gridN out of byte range: " + m.gridN());
        }
        out.writeByte(m.gridN());
        out.writeByte(0); // reserved
        writeShortString(out, m.mcVersion());
        writeShortString(out, m.producer() == null ? "" : m.producer());
        return baos.toByteArray();
    }

    public static Manifest readManifest(byte[] bytes) throws IOException {
        DataInputStream in = readHeader(bytes, TYPE_MANIFEST);
        int gridN = in.readUnsignedByte();
        in.readUnsignedByte(); // reserved
        String mcVersion = readShortString(in);
        String producer = readShortString(in);
        return new Manifest(gridN, mcVersion, producer);
    }

    // ── Block ────────────────────────────────────────────────────────────────

    public static byte[] writeBlock(Block b) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        DataOutputStream out = writeHeader(baos, TYPE_BLOCK);
        writeShortString(out, b.key().toString());
        // Stable order on disk so byte-for-byte diffs across bakes stay tight.
        Map<ChunkCoord, String> orderedChunks = new TreeMap<>(b.chunkHashes());
        if (orderedChunks.size() > 0xFFFF) {
            throw new IllegalArgumentException("too many chunks: " + orderedChunks.size());
        }
        out.writeShort(orderedChunks.size());
        for (Map.Entry<ChunkCoord, String> e : orderedChunks.entrySet()) {
            ChunkCoord c = e.getKey();
            requireByte("chunk.x", c.x());
            requireByte("chunk.y", c.y());
            requireByte("chunk.z", c.z());
            out.writeByte(c.x());
            out.writeByte(c.y());
            out.writeByte(c.z());
            writeShortString(out, e.getValue());
        }
        Map<String, VariantRotation> orderedVariants = new TreeMap<>(b.variants());
        if (orderedVariants.size() > 0xFFFF) {
            throw new IllegalArgumentException("too many variants: " + orderedVariants.size());
        }
        out.writeShort(orderedVariants.size());
        for (Map.Entry<String, VariantRotation> e : orderedVariants.entrySet()) {
            writeShortString(out, e.getKey());
            out.writeShort(e.getValue().xDeg());
            out.writeShort(e.getValue().yDeg());
        }
        return baos.toByteArray();
    }

    public static Block readBlock(byte[] bytes) throws IOException {
        DataInputStream in = readHeader(bytes, TYPE_BLOCK);
        BakeKey key;
        try {
            key = BakeKey.parse(readShortString(in));
        } catch (RuntimeException re) {
            throw new MalformedTsraException("bad bake key: " + re.getMessage());
        }
        int chunkCount = in.readUnsignedShort();
        Map<ChunkCoord, String> chunks = new LinkedHashMap<>(Math.max(8, chunkCount * 2));
        for (int i = 0; i < chunkCount; i++) {
            int x = in.readUnsignedByte();
            int y = in.readUnsignedByte();
            int z = in.readUnsignedByte();
            String hash = readShortString(in);
            chunks.put(new ChunkCoord(x, y, z), hash);
        }
        int variantCount = in.readUnsignedShort();
        Map<String, VariantRotation> variants = new LinkedHashMap<>(Math.max(4, variantCount * 2));
        for (int i = 0; i < variantCount; i++) {
            String vKey = readShortString(in);
            short xDeg = in.readShort();
            short yDeg = in.readShort();
            variants.put(vKey, new VariantRotation(xDeg, yDeg));
        }
        return new Block(key, chunks, variants);
    }

    // ── Skin ─────────────────────────────────────────────────────────────────

    public static byte[] writeSkin(Skin s) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
        DataOutputStream out = writeHeader(baos, TYPE_SKIN);
        boolean hasUuid = s.mineskinUuid() != null && !s.mineskinUuid().isEmpty();
        out.writeByte(hasUuid ? 0x01 : 0x00);
        out.writeByte(0); // reserved
        if (hasUuid) writeShortString(out, s.mineskinUuid());
        writeLongString(out, s.value() == null ? "" : s.value());
        writeLongString(out, s.signature() == null ? "" : s.signature());
        return baos.toByteArray();
    }

    /**
     * The hash is implicit (the filename), so callers pass it in; the body
     * carries only the payload bytes.
     */
    public static Skin readSkin(String hash, byte[] bytes) throws IOException {
        DataInputStream in = readHeader(bytes, TYPE_SKIN);
        int flags = in.readUnsignedByte();
        in.readUnsignedByte(); // reserved
        String uuid = ((flags & 0x01) != 0) ? readShortString(in) : null;
        String value = readLongString(in);
        String signature = readLongString(in);
        return new Skin(hash, value, signature, uuid);
    }

    // ── Filename helpers ─────────────────────────────────────────────────────

    /**
     * Filesystem-safe encoding for a {@link BakeKey}. Vanilla block paths use
     * {@code [a-z0-9_/.-]} so {@code ':'} (namespace separator) and {@code '#'}
     * (tint separator) are the only chars in {@link BakeKey#toString()} that
     * can clash with filesystem conventions on Windows. Encoded as
     * {@code "__"} for {@code ':'} and {@code "--"} for {@code '#'} —
     * neither sequence appears in vanilla block names, so decoding round-trips
     * cleanly via {@link #parseBlockFilename(String)}.
     */
    public static String blockFilename(BakeKey key) {
        String s = key.toString();
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case ':' -> b.append("__");
                case '#' -> b.append("--");
                default -> b.append(c);
            }
        }
        b.append(FILE_EXTENSION);
        return b.toString();
    }

    public static BakeKey parseBlockFilename(String filename) {
        if (!filename.endsWith(FILE_EXTENSION)) {
            throw new IllegalArgumentException("not a .tsra filename: " + filename);
        }
        String stripped = filename.substring(0, filename.length() - FILE_EXTENSION.length());
        StringBuilder b = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length();) {
            if (i + 1 < stripped.length()) {
                String pair = stripped.substring(i, i + 2);
                if (pair.equals("__")) { b.append(':'); i += 2; continue; }
                if (pair.equals("--")) { b.append('#'); i += 2; continue; }
            }
            b.append(stripped.charAt(i));
            i++;
        }
        return BakeKey.parse(b.toString());
    }

    /**
     * Sharded path for a skin hash: {@code skins/<first2>/<hash>.tsra}. Keeps
     * any one directory under a thousand entries even with tens of thousands
     * of skins, which is important on ext4 / NTFS where {@code File.list()}
     * latency grows with directory size.
     */
    public static String skinPath(String hash) {
        String shard = hash.length() >= 2 ? hash.substring(0, 2) : "_";
        return SKINS_DIR + "/" + shard + "/" + hash + FILE_EXTENSION;
    }

    // ── Header helpers ───────────────────────────────────────────────────────

    private static DataOutputStream writeHeader(ByteArrayOutputStream baos, byte type) throws IOException {
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(MAGIC);
        out.writeByte(FORMAT_VERSION);
        out.writeByte(type);
        out.writeShort(0);
        return out;
    }

    private static DataInputStream readHeader(byte[] bytes, byte expectedType) throws IOException {
        if (bytes.length < 8) throw new MalformedTsraException("too short: " + bytes.length);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new MalformedTsraException(
                    String.format("bad magic: 0x%08x (expected 0x%08x)", magic, MAGIC));
        }
        byte ver = in.readByte();
        if (ver != FORMAT_VERSION) {
            throw new MalformedTsraException("unsupported format version: " + ver);
        }
        byte type = in.readByte();
        if (type != expectedType) {
            throw new MalformedTsraException(
                    "wrong payload type: expected " + expectedType + " got " + type);
        }
        in.readShort(); // reserved
        return in;
    }

    public static byte payloadType(byte[] bytes) throws IOException {
        if (bytes.length < 8) throw new MalformedTsraException("too short: " + bytes.length);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new MalformedTsraException(
                    String.format("bad magic: 0x%08x", magic));
        }
        byte ver = in.readByte();
        if (ver != FORMAT_VERSION) {
            throw new MalformedTsraException("unsupported format version: " + ver);
        }
        return in.readByte();
    }

    private static void writeShortString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > 0xFFFF) throw new IllegalArgumentException("string too long: " + b.length);
        out.writeShort(b.length);
        out.write(b);
    }

    private static String readShortString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        return readUtf8(in, len);
    }

    private static void writeLongString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readLongString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) throw new MalformedTsraException("negative string length: " + len);
        return readUtf8(in, len);
    }

    private static String readUtf8(DataInputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(b, read, len - read);
            if (n < 0) throw new EOFException("unexpected EOF reading " + len + " bytes");
            read += n;
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void requireByte(String field, int v) {
        if (v < 0 || v > 255) throw new IllegalArgumentException(field + " not a byte: " + v);
    }
}
