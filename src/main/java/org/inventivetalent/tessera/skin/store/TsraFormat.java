package org.inventivetalent.tessera.skin.store;

import org.inventivetalent.tessera.assets.model.BlockModel;
import org.inventivetalent.tessera.assets.model.ModelResolver.VariantRotation;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.core.FaceDir;
import org.inventivetalent.tessera.skin.HeadsRegistry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
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
 *   [1] format version   = {@value #FORMAT_VERSION} (latest); v1 also readable
 *   [1] payload type     1 = manifest, 2 = block, 3 = skin
 *   [2] reserved (0)
 *   [...] type-specific body
 * </pre>
 *
 * <p><b>Manifest</b>: {@code [1 gridN][1 reserved][u16 mcVersionLen][bytes
 * mcVersion][u16 producerLen][bytes producer]}. Only the bundled
 * {@code .ztsra} ships a manifest; folder stores write one on first use so
 * the directory is self-describing.
 *
 * <p><b>Block (v2)</b>: a multi-shape index keyed by model path.
 * <pre>
 *   [u16 bakeKeyLen][bytes bakeKey]
 *   [u16 shapeCount]{shapeCount × shapeRecord}
 *   [u16 variantCount]{variantCount × variantRecord}
 *
 *   shapeRecord:
 *     [u16 shapeKeyLen][bytes shapeKey]
 *     [u16 chunkCount]{chunkCount × [1 x][1 y][1 z][1 outwardMask][u16 hashLen][bytes hash]}
 *
 *   variantRecord:
 *     [u16 variantKeyLen][bytes variantKey]
 *     [u16 shapeKeyLen][bytes shapeKey]
 *     [s16 xDeg][s16 yDeg]
 * </pre>
 * The {@code outwardMask} byte's low 6 bits encode which {@link FaceDir}s
 * the chunk has outward in this shape's lattice (bit positions match the
 * {@link FaceDir} enum's {@code ordinal()}: DOWN, UP, NORTH, SOUTH, WEST,
 * EAST). Pre-baked so runtime can populate {@code ChunkRef.outwardFaces}
 * without computing from cube bounds.
 *
 * <p><b>Block (v1)</b>: legacy single-shape layout.
 * <pre>
 *   [u16 bakeKeyLen][bytes bakeKey]
 *   [u16 chunkCount]{chunkCount × [1 x][1 y][1 z][u16 hashLen][bytes hash]}
 *   [u16 variantCount]{variantCount × [u16 keyLen][bytes key][s16 xDeg][s16 yDeg]}
 * </pre>
 * Readers synthesize a v2 record with a single {@link BlockModel#DEFAULT_SHAPE_KEY}
 * shape, computing per-chunk outward masks from {@code FaceDir.isOutwardAt}
 * (cube assumption — correct for all v1 bakes, which are all cubes), and
 * binding every variant to the default shape.
 *
 * <p><b>Skin</b>: {@code [1 flags][1 reserved][optional u16+bytes uuid]
 * [u32 valueLen][bytes value][u32 signatureLen][bytes signature]}. Bit 0
 * of {@code flags} is set when a MineSkin UUID is present.
 *
 * <p>Forward-compatibility: readers reject unknown magic or unknown
 * format-version with {@link MalformedTsraException}. v1 and v2 are both
 * accepted; writers always emit the latest version.
 */
public final class TsraFormat {

    public static final int MAGIC = 0x54535241; // 'TSRA'
    public static final byte FORMAT_VERSION_V1 = 1;
    public static final byte FORMAT_VERSION = 2;

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

    /**
     * Encoded per-chunk record: the skin content hash plus the chunk's
     * outward-face mask in this shape's lattice. {@link #outwardFaces()}
     * decodes the byte mask back into an {@link EnumSet} for runtime use.
     */
    public record ChunkRecord(String hash, byte outwardMask) {
        public EnumSet<FaceDir> outwardFaces() {
            EnumSet<FaceDir> out = EnumSet.noneOf(FaceDir.class);
            for (FaceDir d : FaceDir.values()) {
                if ((outwardMask & (1 << d.ordinal())) != 0) out.add(d);
            }
            return out;
        }

        public static byte mask(EnumSet<FaceDir> faces) {
            int m = 0;
            for (FaceDir d : faces) m |= (1 << d.ordinal());
            return (byte) m;
        }
    }

    /** One named shape: a sparse {@code coord → (hash, outwardMask)} index. */
    public record Shape(Map<ChunkCoord, ChunkRecord> chunks) {
        public Shape {
            chunks = Collections.unmodifiableMap(new LinkedHashMap<>(chunks));
        }
    }

    /** Variant binding: which shape this variant key uses + the runtime rotation. */
    public record ShapeVariantBinding(String shapeKey, VariantRotation rotation) {}

    /**
     * Block record. Multi-shape blocks (stairs) hold one entry per model
     * path in {@link #shapes}; single-shape blocks (cubes, slabs) hold one.
     * The {@link #variants} table maps each blockstate variant key to its
     * (shape, rotation) binding. For v1 files read back as v2, the
     * synthesized shape key is {@link BlockModel#DEFAULT_SHAPE_KEY} and
     * every variant binds to it.
     */
    public record Block(BakeKey key,
                        Map<String, Shape> shapes,
                        Map<String, ShapeVariantBinding> variants) {
        public Block {
            shapes = Collections.unmodifiableMap(new LinkedHashMap<>(shapes));
            variants = Collections.unmodifiableMap(new LinkedHashMap<>(variants));
        }

        /**
         * Single-shape convenience constructor for tests and v1-style callers.
         * All chunks go under {@link BlockModel#DEFAULT_SHAPE_KEY}; all
         * variants bind to that shape. Outward face masks are computed via
         * {@link FaceDir#isOutwardAt} with the gridN encoded by the
         * caller-passed coords (defaults to gridN=4 — every existing fixture
         * + the bundled bake use that value).
         */
        public static Block singleShape(BakeKey key,
                                         Map<ChunkCoord, String> chunkHashes,
                                         Map<String, VariantRotation> variants) {
            return singleShape(key, chunkHashes, variants, 4);
        }

        public static Block singleShape(BakeKey key,
                                         Map<ChunkCoord, String> chunkHashes,
                                         Map<String, VariantRotation> variants,
                                         int gridN) {
            LinkedHashMap<ChunkCoord, ChunkRecord> chunks = new LinkedHashMap<>(chunkHashes.size() * 2);
            for (Map.Entry<ChunkCoord, String> e : chunkHashes.entrySet()) {
                ChunkCoord c = e.getKey();
                EnumSet<FaceDir> outward = EnumSet.noneOf(FaceDir.class);
                for (FaceDir d : FaceDir.values()) {
                    if (d.isOutwardAt(c.x(), c.y(), c.z(), gridN)) outward.add(d);
                }
                chunks.put(c, new ChunkRecord(e.getValue(), ChunkRecord.mask(outward)));
            }
            LinkedHashMap<String, Shape> shapes = new LinkedHashMap<>(2);
            shapes.put(BlockModel.DEFAULT_SHAPE_KEY, new Shape(chunks));
            LinkedHashMap<String, ShapeVariantBinding> bound = new LinkedHashMap<>(variants.size() * 2);
            for (Map.Entry<String, VariantRotation> ve : variants.entrySet()) {
                bound.put(ve.getKey(), new ShapeVariantBinding(BlockModel.DEFAULT_SHAPE_KEY, ve.getValue()));
            }
            return new Block(key, shapes, bound);
        }

        /**
         * Hashes for the {@link BlockModel#DEFAULT_SHAPE_KEY default shape},
         * or the first shape if "default" isn't present. Backward-compat
         * accessor for callers that predate multi-shape (e.g. diagnostic
         * code).
         */
        public Map<ChunkCoord, String> chunkHashes() {
            Shape s = shapes.get(BlockModel.DEFAULT_SHAPE_KEY);
            if (s == null && !shapes.isEmpty()) s = shapes.values().iterator().next();
            if (s == null) return Collections.emptyMap();
            LinkedHashMap<ChunkCoord, String> out = new LinkedHashMap<>(s.chunks().size() * 2);
            s.chunks().forEach((c, r) -> out.put(c, r.hash()));
            return out;
        }

        /**
         * Synthesized {@code variantKey → VariantRotation} table for callers
         * that predate the shape binding. Drops the shape information.
         */
        public Map<String, VariantRotation> variantRotations() {
            LinkedHashMap<String, VariantRotation> out = new LinkedHashMap<>();
            variants.forEach((k, v) -> out.put(k, v.rotation()));
            return out;
        }
    }

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
        DataOutputStream out = writeHeader(baos, TYPE_MANIFEST, FORMAT_VERSION);
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
        DataOutputStream out = writeHeader(baos, TYPE_BLOCK, FORMAT_VERSION);
        writeShortString(out, b.key().toString());

        Map<String, Shape> orderedShapes = new TreeMap<>(b.shapes());
        if (orderedShapes.size() > 0xFFFF) {
            throw new IllegalArgumentException("too many shapes: " + orderedShapes.size());
        }
        out.writeShort(orderedShapes.size());
        for (Map.Entry<String, Shape> se : orderedShapes.entrySet()) {
            writeShortString(out, se.getKey());
            Map<ChunkCoord, ChunkRecord> ordered = new TreeMap<>(se.getValue().chunks());
            if (ordered.size() > 0xFFFF) {
                throw new IllegalArgumentException("too many chunks in shape " + se.getKey() + ": " + ordered.size());
            }
            out.writeShort(ordered.size());
            for (Map.Entry<ChunkCoord, ChunkRecord> ce : ordered.entrySet()) {
                ChunkCoord c = ce.getKey();
                requireByte("chunk.x", c.x());
                requireByte("chunk.y", c.y());
                requireByte("chunk.z", c.z());
                out.writeByte(c.x());
                out.writeByte(c.y());
                out.writeByte(c.z());
                out.writeByte(ce.getValue().outwardMask());
                writeShortString(out, ce.getValue().hash());
            }
        }

        Map<String, ShapeVariantBinding> orderedVariants = new TreeMap<>(b.variants());
        if (orderedVariants.size() > 0xFFFF) {
            throw new IllegalArgumentException("too many variants: " + orderedVariants.size());
        }
        out.writeShort(orderedVariants.size());
        for (Map.Entry<String, ShapeVariantBinding> ve : orderedVariants.entrySet()) {
            writeShortString(out, ve.getKey());
            writeShortString(out, ve.getValue().shapeKey());
            out.writeShort(ve.getValue().rotation().xDeg());
            out.writeShort(ve.getValue().rotation().yDeg());
        }
        return baos.toByteArray();
    }

    /**
     * Test/convenience overload — assumes gridN=4. Production callers use
     * the explicit-gridN form so v1 outward-mask reconstruction is correct
     * for any grid size.
     */
    public static Block readBlock(byte[] bytes) throws IOException {
        return readBlock(bytes, 4);
    }

    /**
     * Read a Block in v1 or v2 layout. {@code gridN} is required so v1 files
     * can synthesize per-chunk outward masks via {@link FaceDir#isOutwardAt}.
     * Stores cache their manifest's gridN and pass it in.
     */
    public static Block readBlock(byte[] bytes, int gridN) throws IOException {
        DataInputStream in = openHeader(bytes, TYPE_BLOCK);
        byte version = bytes[4];
        if (version == FORMAT_VERSION_V1) return readBlockV1(in, gridN);
        if (version == FORMAT_VERSION)    return readBlockV2(in);
        throw new MalformedTsraException("unsupported format version: " + version);
    }

    private static Block readBlockV2(DataInputStream in) throws IOException {
        BakeKey key;
        try {
            key = BakeKey.parse(readShortString(in));
        } catch (RuntimeException re) {
            throw new MalformedTsraException("bad bake key: " + re.getMessage());
        }
        int shapeCount = in.readUnsignedShort();
        LinkedHashMap<String, Shape> shapes = new LinkedHashMap<>(Math.max(2, shapeCount * 2));
        for (int s = 0; s < shapeCount; s++) {
            String shapeKey = readShortString(in);
            int chunkCount = in.readUnsignedShort();
            LinkedHashMap<ChunkCoord, ChunkRecord> chunks = new LinkedHashMap<>(Math.max(8, chunkCount * 2));
            for (int i = 0; i < chunkCount; i++) {
                int x = in.readUnsignedByte();
                int y = in.readUnsignedByte();
                int z = in.readUnsignedByte();
                byte outwardMask = in.readByte();
                String hash = readShortString(in);
                chunks.put(new ChunkCoord(x, y, z), new ChunkRecord(hash, outwardMask));
            }
            shapes.put(shapeKey, new Shape(chunks));
        }
        int variantCount = in.readUnsignedShort();
        LinkedHashMap<String, ShapeVariantBinding> variants = new LinkedHashMap<>(Math.max(4, variantCount * 2));
        for (int i = 0; i < variantCount; i++) {
            String vKey = readShortString(in);
            String shapeKey = readShortString(in);
            short xDeg = in.readShort();
            short yDeg = in.readShort();
            variants.put(vKey, new ShapeVariantBinding(shapeKey, new VariantRotation(xDeg, yDeg)));
        }
        return new Block(key, shapes, variants);
    }

    private static Block readBlockV1(DataInputStream in, int gridN) throws IOException {
        BakeKey key;
        try {
            key = BakeKey.parse(readShortString(in));
        } catch (RuntimeException re) {
            throw new MalformedTsraException("bad bake key: " + re.getMessage());
        }
        int chunkCount = in.readUnsignedShort();
        LinkedHashMap<ChunkCoord, ChunkRecord> chunks = new LinkedHashMap<>(Math.max(8, chunkCount * 2));
        for (int i = 0; i < chunkCount; i++) {
            int x = in.readUnsignedByte();
            int y = in.readUnsignedByte();
            int z = in.readUnsignedByte();
            String hash = readShortString(in);
            // Synthesize outwardMask using cube assumption — works for every
            // block in v1 because v1 only baked cubes.
            EnumSet<FaceDir> outward = EnumSet.noneOf(FaceDir.class);
            for (FaceDir d : FaceDir.values()) {
                if (d.isOutwardAt(x, y, z, gridN)) outward.add(d);
            }
            chunks.put(new ChunkCoord(x, y, z), new ChunkRecord(hash, ChunkRecord.mask(outward)));
        }
        int variantCount = in.readUnsignedShort();
        LinkedHashMap<String, ShapeVariantBinding> variants = new LinkedHashMap<>(Math.max(4, variantCount * 2));
        for (int i = 0; i < variantCount; i++) {
            String vKey = readShortString(in);
            short xDeg = in.readShort();
            short yDeg = in.readShort();
            variants.put(vKey, new ShapeVariantBinding(
                    BlockModel.DEFAULT_SHAPE_KEY, new VariantRotation(xDeg, yDeg)));
        }
        LinkedHashMap<String, Shape> shapes = new LinkedHashMap<>(2);
        shapes.put(BlockModel.DEFAULT_SHAPE_KEY, new Shape(chunks));
        return new Block(key, shapes, variants);
    }

    // ── Skin ─────────────────────────────────────────────────────────────────

    public static byte[] writeSkin(Skin s) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
        DataOutputStream out = writeHeader(baos, TYPE_SKIN, FORMAT_VERSION);
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
     * carries only the payload bytes. Skin format is unchanged between
     * v1 and v2.
     */
    public static Skin readSkin(String hash, byte[] bytes) throws IOException {
        DataInputStream in = openHeader(bytes, TYPE_SKIN);
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
     * Sharded path for a skin hash: {@code skins/<first2>/<hash>.tsra}.
     */
    public static String skinPath(String hash) {
        String shard = hash.length() >= 2 ? hash.substring(0, 2) : "_";
        return SKINS_DIR + "/" + shard + "/" + hash + FILE_EXTENSION;
    }

    // ── Header helpers ───────────────────────────────────────────────────────

    private static DataOutputStream writeHeader(ByteArrayOutputStream baos, byte type, byte version) throws IOException {
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(MAGIC);
        out.writeByte(version);
        out.writeByte(type);
        out.writeShort(0);
        return out;
    }

    /** v2-only header reader, used internally by {@link #readManifest} / {@link #readSkin}. */
    private static DataInputStream readHeader(byte[] bytes, byte expectedType) throws IOException {
        DataInputStream in = openHeader(bytes, expectedType);
        byte ver = bytes[4];
        if (ver != FORMAT_VERSION && ver != FORMAT_VERSION_V1) {
            throw new MalformedTsraException("unsupported format version: " + ver);
        }
        return in;
    }

    /**
     * Common header consumer: checks magic + payload type, returns a stream
     * positioned at the body start. Format version is left for the caller
     * to inspect (so multi-version block readers can dispatch on it).
     */
    private static DataInputStream openHeader(byte[] bytes, byte expectedType) throws IOException {
        if (bytes.length < 8) throw new MalformedTsraException("too short: " + bytes.length);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new MalformedTsraException(
                    String.format("bad magic: 0x%08x (expected 0x%08x)", magic, MAGIC));
        }
        in.readByte();  // format version — handled by callers
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
        if (ver != FORMAT_VERSION && ver != FORMAT_VERSION_V1) {
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
