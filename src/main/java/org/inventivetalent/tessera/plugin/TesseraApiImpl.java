package org.inventivetalent.tessera.plugin;

import org.bukkit.block.data.BlockData;
import org.inventivetalent.tessera.api.BakeOutcome;
import org.inventivetalent.tessera.api.ChunkLayout;
import org.inventivetalent.tessera.api.SkinPayload;
import org.inventivetalent.tessera.api.TesseraApi;
import org.inventivetalent.tessera.assemble.FakeBlockFactory;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.core.VariantKey;
import org.inventivetalent.tessera.skin.HeadsRegistry;
import org.inventivetalent.tessera.skin.bake.BlockBaker;
import org.joml.Quaternionf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Default {@link TesseraApi} implementation, registered with Bukkit's
 * {@code ServicesManager} by {@link TesseraPlugin}. A thin read-only facade over
 * {@link HeadsRegistry} (queries), {@link FakeBlockFactory} (layout math) and
 * {@link BlockBaker} (on-demand bakes) — it adds no state of its own.
 */
final class TesseraApiImpl implements TesseraApi {

    private final TesseraPlugin plugin;
    private final HeadsRegistry registry;
    private final BlockBaker baker;
    private final FakeBlockFactory blockFactory;

    TesseraApiImpl(TesseraPlugin plugin, HeadsRegistry registry, BlockBaker baker,
                   FakeBlockFactory blockFactory) {
        this.plugin = plugin;
        this.registry = registry;
        this.baker = baker;
        this.blockFactory = blockFactory;
    }

    @Override
    public int apiVersion() {
        return TesseraApi.VERSION;
    }

    @Override
    public int gridN() {
        return registry.gridN();
    }

    @Override
    public boolean isAvailable(BlockKey block) {
        return registry.has(block);
    }

    @Override
    public Set<BlockKey> availableBlocks() {
        return Set.copyOf(registry.knownBlockKeys());
    }

    @Override
    public Map<ChunkCoord, SkinPayload> heads(BakeKey key) {
        Map<ChunkCoord, HeadsRegistry.Entry> chunks = registry.chunksFor(key);
        Map<ChunkCoord, SkinPayload> out = new LinkedHashMap<>(Math.max(8, chunks.size() * 2));
        for (Map.Entry<ChunkCoord, HeadsRegistry.Entry> e : chunks.entrySet()) {
            HeadsRegistry.Entry v = e.getValue();
            out.put(e.getKey(), new SkinPayload(v.skinHash(), v.textureValue(),
                    v.textureSignature(), v.mineskinUuid()));
        }
        return out;
    }

    @Override
    public List<ChunkLayout> layout(BlockKey block, BlockData state) {
        // Resolve the placed blockstate's variant rotation exactly as the break
        // listener does, so logs/stairs/etc. come out correctly oriented.
        String fullStateKey = VariantKey.fromBlockData(state);
        String matchedKey = VariantKey.pickMatching(fullStateKey, registry.variantsFor(block).keySet());
        Quaternionf rotation = registry.rotationFor(block, matchedKey);
        return blockFactory.layout(BakeKey.untinted(block), rotation);
    }

    @Override
    public List<ChunkLayout> layout(BakeKey key, Quaternionf blockRotation) {
        return blockFactory.layout(key, blockRotation == null ? new Quaternionf() : blockRotation);
    }

    @Override
    public CompletableFuture<BakeOutcome> requestBake(BlockKey block) {
        BakeKey key = BakeKey.untinted(block);
        if (registry.has(key)) {
            return CompletableFuture.completedFuture(BakeOutcome.SUCCESS);
        }
        if (!canBakeNewBlocks()) {
            return CompletableFuture.completedFuture(BakeOutcome.NOT_CONFIGURED);
        }
        return baker.bake(key).handle((ok, ex) -> {
            if (ex != null) return BakeOutcome.FAILED;
            return ok ? BakeOutcome.SUCCESS : BakeOutcome.UNBAKEABLE;
        });
    }

    @Override
    public boolean canBakeNewBlocks() {
        TesseraConfig cfg = plugin.tesseraConfig();
        return cfg.hasLicense() || !cfg.mineskinApiKey().isBlank();
    }
}
