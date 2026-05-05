package org.inventivetalent.tessera.plugin;

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
 * <p>A full-lifecycle test that loads {@link TesseraPlugin} via
 * {@code MockBukkit.load(TesseraPlugin.class)} is the obvious next step;
 * splitting it out keeps this smoke test fast (no plugin.yml parse, no
 * {@code onEnable} side effects) and lets us isolate harness regressions
 * from plugin-internal regressions.
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
