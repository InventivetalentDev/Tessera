package io.tessera.plugin;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Smoke test for the MockBukkit harness itself. Confirms the in-JVM Paper
 * server boots and tears down cleanly so later integration tests can build
 * on it.
 *
 * <p>Loading {@link TesseraPlugin} directly under MockBukkit-v1.20 currently
 * fails — our {@code plugin.yml} declares {@code api-version: '1.21'} and
 * {@link TesseraPlugin#onEnable()} touches Paper-only APIs (ItemDisplay,
 * {@code Bukkit.getMinecraftVersion}) plus a bundled {@code /heads.json}.
 * Once a MockBukkit-v1.21 artifact is available we should add a
 * full-lifecycle test ({@code MockBukkit.load(TesseraPlugin.class)} +
 * onEnable/onDisable assertions). Until then, this smoke test verifies the
 * harness wiring without booting the plugin.
 */
class MockBukkitSmokeTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void serverMockIsRegisteredAsBukkitServer() {
        assertNotNull(server);
        assertSame(server, Bukkit.getServer());
    }

    @Test
    void schedulerIsAvailable() {
        // The scheduler is the most common surface plugin code touches in
        // tests — making sure it's wired here catches harness regressions
        // early.
        assertNotNull(server.getScheduler());
    }
}
