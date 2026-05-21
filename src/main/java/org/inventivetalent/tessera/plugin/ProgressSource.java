package org.inventivetalent.tessera.plugin;

/**
 * Platform-specific delivery channel for mining-progress events into
 * {@link BlockBreakProgressListener#handleProgress}. Concrete impls:
 *
 * <ul>
 *   <li>{@code PaperProgressEventBridge} — Paper's
 *       {@code BlockBreakProgressUpdateEvent}.</li>
 *   <li>{@code PacketEventsProgressSource} — PacketEvents-based digging-packet
 *       observer for non-Paper servers.</li>
 * </ul>
 *
 * <p>Impls are picked and instantiated via {@code Class.forName} in
 * {@link TesseraPlugin#installProgressBridge} so the JVM doesn't link
 * platform-specific symbols (Paper events, PacketEvents API) until we've
 * confirmed they're available. Once an instance is in hand we hold it
 * through this interface and call its methods directly — no further
 * reflection.
 */
interface ProgressSource {

    /** Hook up the event/packet subscriptions. Called once during plugin enable. */
    void register();

    /**
     * Tear down anything that won't unwind on its own when the plugin
     * disables. Bukkit-registered {@code Listener}s clean themselves up
     * automatically and need no work here; per-player scheduled tasks
     * (the PacketEvents path) do.
     */
    void shutdown();
}
