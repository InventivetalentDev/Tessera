package org.inventivetalent.tessera.util;

/**
 * One-shot detection of the runtime server flavor. Probed once at class-load
 * by looking for marker classes — no Bukkit API calls, no instantiation, so
 * this is safe to consult from anywhere including static initializers.
 *
 * <p>Tessera compiles against the Paper dev bundle (so Paper-specific symbols
 * resolve at compile time), but at runtime some of those symbols don't exist
 * on Spigot. Each Paper-only call site that's reachable on Spigot must
 * either be class-isolated (loaded by reflection only when {@link #PAPER}
 * is true) or wrapped in reflection itself.
 *
 * <p>{@link #PACKET_EVENTS} reports whether the PacketEvents plugin is on
 * the classpath. It's declared as a softdepend; on Spigot the plugin is
 * effectively required (the Paper progress event is unavailable), and on
 * Paper it's optional (Paper has the event natively).
 */
public final class PlatformDetector {

    public static final boolean PAPER = classPresent(
            "io.papermc.paper.event.block.BlockBreakProgressUpdateEvent");

    public static final boolean PACKET_EVENTS = classPresent(
            "com.github.retrooper.packetevents.PacketEvents");

    private PlatformDetector() {}

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, PlatformDetector.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
