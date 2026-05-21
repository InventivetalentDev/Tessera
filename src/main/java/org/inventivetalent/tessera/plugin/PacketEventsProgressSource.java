package org.inventivetalent.tessera.plugin;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PacketEvents-based progress source for non-Paper servers. Observes the
 * {@code PLAYER_DIGGING} packet and synthesizes the semantics of Paper's
 * {@code BlockBreakProgressUpdateEvent} by ticking a per-player progress
 * timer using {@link Block#getBreakSpeed(Player)} — the same per-tick
 * destroy-progress formula the vanilla server uses internally.
 *
 * <p>Loaded reflectively from {@link TesseraPlugin} only when
 * {@code com.github.retrooper.packetevents.PacketEvents} is on the
 * classpath, so the JVM never tries to link these imports on installs
 * without PacketEvents.
 *
 * <p>Emits at 0.1 stage boundaries to match Paper's event cadence. The
 * downstream listener's {@code smoothInterpolation} fills in the
 * between-event animation, so emitting sparsely is intentional.
 */
public final class PacketEventsProgressSource implements Listener, ProgressSource {

    private final TesseraPlugin plugin;
    private final BlockBreakProgressListener delegate;
    private final Map<UUID, MiningState> active = new ConcurrentHashMap<>();
    private boolean registered;

    private static final class MiningState {
        final Block block;
        BukkitTask task;
        double progress;
        double lastEmittedStage = -1d;
        MiningState(Block block) { this.block = block; }
    }

    public PacketEventsProgressSource(TesseraPlugin plugin, BlockBreakProgressListener delegate) {
        this.plugin = plugin;
        this.delegate = delegate;
    }

    @Override
    public void register() {
        if (registered) return;
        if (PacketEvents.getAPI() == null) {
            plugin.getLogger().warning("[progress-pe] PacketEvents API is null at registration");
            return;
        }
        PacketEvents.getAPI().getEventManager().registerListener(
                new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING) return;
                WrapperPlayClientPlayerDigging w = new WrapperPlayClientPlayerDigging(event);
                DiggingAction action = w.getAction();
                if (action != DiggingAction.START_DIGGING
                        && action != DiggingAction.CANCELLED_DIGGING
                        && action != DiggingAction.FINISHED_DIGGING) {
                    return;
                }
                Vector3i pos = w.getBlockPosition();
                UUID uuid = event.getUser().getUUID();
                if (uuid == null) return;
                Bukkit.getScheduler().runTask(plugin, () -> onAction(action, uuid, pos));
            }
        });
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registered = true;
        plugin.getLogger().info("Progress source: PacketEvents (Spigot fallback)");
    }

    @Override
    public void shutdown() {
        for (MiningState s : active.values()) {
            if (s.task != null) s.task.cancel();
        }
        active.clear();
    }

    private void onAction(DiggingAction action, UUID uuid, Vector3i pos) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        Block block = player.getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ());
        switch (action) {
            case START_DIGGING -> start(player, block);
            case CANCELLED_DIGGING -> cancelMining(player);
            case FINISHED_DIGGING -> finishMining(player);
            default -> {}
        }
    }

    private void start(Player player, Block block) {
        UUID uuid = player.getUniqueId();
        MiningState prev = active.remove(uuid);
        if (prev != null) {
            if (prev.task != null) prev.task.cancel();
            // Synthesize a cancel for the previous block so the downstream
            // listener can reverse + dispose its FakeBlock cleanly.
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) delegate.handleProgress(p, prev.block, 0d);
        }
        MiningState state = new MiningState(block);
        active.put(uuid, state);
        state.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(uuid, state), 1L, 1L);
    }

    private void tick(UUID uuid, MiningState state) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            cancelInternal(uuid, state);
            return;
        }
        // Block was broken out-from-under us (creative, external break, etc.)
        if (state.block.getType().isAir()) {
            cancelInternal(uuid, state);
            return;
        }
        float perTick;
        try {
            perTick = state.block.getBreakSpeed(player);
        } catch (RuntimeException re) {
            cancelInternal(uuid, state);
            return;
        }
        if (perTick <= 0f) return; // can't break: no tool / immovable / status effects
        state.progress = Math.min(1.0d, state.progress + perTick);

        double stage = Math.floor(state.progress * 10d) / 10d;
        if (stage > state.lastEmittedStage && state.progress > 0d) {
            state.lastEmittedStage = stage;
            delegate.handleProgress(player, state.block, state.progress);
        }
        if (state.progress >= 1.0d) {
            cancelInternal(uuid, state);
        }
    }

    private void cancelMining(Player player) {
        MiningState state = active.remove(player.getUniqueId());
        if (state == null) return;
        if (state.task != null) state.task.cancel();
        delegate.handleProgress(player, state.block, 0d);
    }

    private void finishMining(Player player) {
        MiningState state = active.remove(player.getUniqueId());
        if (state == null) return;
        if (state.task != null) state.task.cancel();
        // BlockBreakEvent handler in BlockBreakListener.onRealBreak finalizes
        // the FakeBlock; no synthetic progress=1 needed here (it would race
        // with the cleanup).
    }

    private void cancelInternal(UUID uuid, MiningState state) {
        active.remove(uuid);
        if (state.task != null) state.task.cancel();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        MiningState s = active.remove(event.getPlayer().getUniqueId());
        if (s != null && s.task != null) s.task.cancel();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        MiningState s = active.remove(event.getPlayer().getUniqueId());
        if (s != null && s.task != null) s.task.cancel();
    }
}
