package org.inventivetalent.tessera.util;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Matches the "(MC: x.y.z)" tail of {@code Bukkit.getVersion()}. */
    private static final Pattern MC_VERSION_IN_VERSION_STRING =
            Pattern.compile("\\(MC: ([\\d.]+)\\)");

    private PlatformDetector() {}

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, PlatformDetector.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Returns the running Minecraft version (e.g. {@code "1.21.4"}). On Paper
     * we use {@code Bukkit.getMinecraftVersion()}, which is reflective here
     * because the method doesn't exist on Spigot at all — directly calling
     * it would NoSuchMethodError at class-load time when the JIT links the
     * caller. On Spigot we fall through to parsing {@code Bukkit.getVersion()}
     * (format {@code "git-Spigot-... (MC: 1.21.4)"}) and finally
     * {@code Bukkit.getBukkitVersion()} ({@code "1.21.4-R0.1-SNAPSHOT"}).
     */
    public static String minecraftVersion() {
        try {
            Method m = Bukkit.class.getMethod("getMinecraftVersion");
            Object v = m.invoke(null);
            if (v instanceof String s && !s.isBlank()) return s;
        } catch (NoSuchMethodException ignored) {
            // Spigot path — fall through.
        } catch (ReflectiveOperationException ignored) {
            // Method present but threw — fall through.
        }
        String full = safeCall(Bukkit::getVersion);
        if (full != null) {
            Matcher m = MC_VERSION_IN_VERSION_STRING.matcher(full);
            if (m.find()) return m.group(1);
        }
        String bukkit = safeCall(Bukkit::getBukkitVersion);
        if (bukkit != null) {
            int dash = bukkit.indexOf('-');
            return dash > 0 ? bukkit.substring(0, dash) : bukkit;
        }
        // Last-resort: should never happen on a real server.
        return "unknown";
    }

    private static String safeCall(java.util.function.Supplier<String> s) {
        try { return s.get(); }
        catch (RuntimeException e) { return null; }
    }
}
