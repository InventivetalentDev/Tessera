package io.tessera.skin;

import io.tessera.core.ChunkSpec;
import io.tessera.core.HeadFace;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One packed 64×64 player-head skin. Carries the 6 face tiles to be painted
 * by {@code SkinAssembler}, plus the list of {@link ChunkSpec}s that share
 * this head's content (multiple chunks dedupe to one upload via
 * {@link #contentHash}).
 *
 * <p>Mutable: the upload pipeline transitions {@link #state} between
 * {@link SkinState} values and writes {@link #textureValue}/{@link #textureSignature}
 * once MineSkin returns. Identity is the random {@link #id}; the
 * {@link #contentHash} is the dedup key (sha256 of the 6 tile content
 * hashes) — adapted from Mosaikin's SkinPacker.
 */
public final class HeadSkin {

    private final UUID id;
    private final String contentHash;
    private final EnumMap<HeadFace, BufferedImage> tiles;
    private final List<ChunkSpec> chunks = new ArrayList<>();

    private SkinState state = SkinState.PENDING;
    private String jobId;
    private String textureValue;
    private String textureSignature;
    private String mineskinUuid;

    /** Relative path to the per-head PNG written by {@code SkinAssembler}, e.g. {@code "heads/<uuid>.png"}. */
    private String pngFile;

    public HeadSkin(UUID id, String contentHash, Map<HeadFace, BufferedImage> tiles) {
        this.id = id;
        this.contentHash = contentHash;
        // EnumMap(Map) throws on empty input because it can't infer the key
        // class from a generic Map. Build via class then putAll so runtime
        // HeadSkins (which carry no tiles - bitmaps were baked away into the
        // MineSkin texture) construct cleanly.
        this.tiles = new EnumMap<>(HeadFace.class);
        this.tiles.putAll(tiles);
    }

    public UUID id() { return id; }
    public String contentHash() { return contentHash; }

    public Map<HeadFace, BufferedImage> tiles() {
        return Map.copyOf(tiles);
    }

    public BufferedImage tile(HeadFace face) {
        return tiles.get(face);
    }

    public SkinState state() { return state; }
    public void state(SkinState s) { this.state = s; }

    public String jobId() { return jobId; }
    public void jobId(String j) { this.jobId = j; }

    public String textureValue() { return textureValue; }
    public String textureSignature() { return textureSignature; }
    public String mineskinUuid() { return mineskinUuid; }

    public void texture(String value, String signature, String mineskinUuid) {
        this.textureValue = value;
        this.textureSignature = signature;
        this.mineskinUuid = mineskinUuid;
    }

    public String pngFile() { return pngFile; }
    public void pngFile(String p) { this.pngFile = p; }

    /** Register a chunk that uses this head. Multiple chunks may share one head via dedup. */
    public void addChunk(ChunkSpec chunk) {
        chunks.add(chunk);
    }

    public List<ChunkSpec> chunks() {
        return List.copyOf(chunks);
    }
}
