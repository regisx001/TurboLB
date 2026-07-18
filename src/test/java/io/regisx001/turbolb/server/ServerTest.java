package io.regisx001.turbolb.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and integration tests for the {@link Server} class.
 *
 * Ported from the original C++ test_server.cpp:
 * - Unit tests: construction, initialization, port reuse, stop safety
 * - Integration tests: accept connections, exchange data, multiple clients,
 * connect-send-close cycle, stop + restart
 *
 * Uses unique ports per test via an atomic counter to avoid collisions.
 */
class ServerTest {

    private static final AtomicInteger portCounter = new AtomicInteger(25000);

    private static int nextPort() {
        return portCounter.incrementAndGet();
    }

    // ── Unit Tests ─────────────────────────────────────────────────────────

    @Test
    void serverConstructionDoesNotThrow() {
        assertDoesNotThrow(() -> {
            try (Server s = new Server("127.0.0.1", nextPort())) {
                // constructor only — no resources opened yet
            }
        });
    }

    @Test
    void serverDestructorIsSafeWithoutInitialize() {
        // Create and let GC clean up — should not throw
        Server server = new Server("127.0.0.1", nextPort());
        server.close();
    }

    @Test
    void serverInitializeSucceedsOnFreePort() throws IOException {
        Server server = new Server("127.0.0.1", nextPort());
        try {
            assertDoesNotThrow(server::initialize);
        } finally {
            server.close();
        }
    }

    @Test
    void multipleServersCanBeCreatedAndDestroyed() throws IOException {
        // Create and destroy multiple servers in sequence
        for (int i = 0; i < 5; i++) {
            Server server = new Server("127.0.0.1", nextPort());
            server.initialize();
            server.close();
        }
    }

    @Test
    void stopIsSafeWithoutInitialize() {
        Server server = new Server("127.0.0.1", nextPort());
        assertDoesNotThrow(server::close);
    }

    @Test
    void stopIsSafeBeforeRun() throws IOException {
        Server server = new Server("127.0.0.1", nextPort());
        server.initialize();
        assertDoesNotThrow(server::close);
    }

    @Test
    void isRunningReturnsFalseInitially() {
        Server server = new Server("127.0.0.1", nextPort());
        assertFalse(server.isRunning());
        server.close();
    }

    @Test
    void getPortReturnsPortAfterInitialize() throws IOException {
        int port = nextPort();
        Server server = new Server("127.0.0.1", port);
        try {
            server.initialize();
            assertEquals(port, server.getPort());
        } finally {
            server.close();
        }
    }

    @Test
    void getPortReturnsZeroBeforeInitialize() {
        Server server = new Server("127.0.0.1", nextPort());
        assertEquals(0, server.getPort());
        server.close();
    }

    // ── Port Reuse (SO_REUSEADDR equivalent) ───────────────────────────────

    @Test
    void portCanBeReusedAfterClose() throws IOException {
        int port = nextPort();

        Server server = new Server("127.0.0.1", port);
        server.initialize();
        server.close();

        // Same port should be reusable immediately
        Server server2 = new Server("127.0.0.1", port);
        try {
            assertDoesNotThrow(server2::initialize);
        } finally {
            server2.close();
        }
    }

    @Test
    void portCanBeReusedRepeatedly() throws IOException {
        int port = nextPort();

        for (int i = 0; i < 5; i++) {
            Server server = new Server("127.0.0.1", port);
            try {
                server.initialize();
            } finally {
                server.close();
            }
        }
    }

    // ── Integration Tests ──────────────────────────────────────────────────

    @Test
    void acceptsConnectionAndResponds() throws IOException, InterruptedException {
        int port = nextPort();
        Server server = new Server("127.0.0.1", port);
        server.initialize();
        server.start();

        try {
            // Wait for server to be ready
            Thread.sleep(200);

            try (Socket socket = new Socket("127.0.0.1", port)) {
                assertTrue(socket.isConnected());

                // Send HTTP request
                String request = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
                socket.getOutputStream().write(request.getBytes());
                socket.getOutputStream().flush();

                // Read response
                byte[] buf = new byte[4096];
                int bytesRead = socket.getInputStream().read(buf);
                String response = new String(buf, 0, bytesRead);

                assertTrue(response.contains("200 OK") || response.contains("Request logged"),
                        "Response should contain success indicator, got: " + response);
            }
        } finally {
            server.close();
        }
    }

    @Test
    void handlesMultipleConcurrentClients() throws IOException, InterruptedException {
        int port = nextPort();
        Server server = new Server("127.0.0.1", port);
        server.initialize();
        server.start();

        try {
            Thread.sleep(200);

            int numClients = 5;
            CountDownLatch allDone = new CountDownLatch(numClients);

            for (int i = 0; i < numClients; i++) {
                int clientId = i;
                new Thread(() -> {
                    try (Socket socket = new Socket("127.0.0.1", port)) {
                        String request = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
                        socket.getOutputStream().write(request.getBytes());
                        socket.getOutputStream().flush();

                        byte[] buf = new byte[4096];
                        int bytesRead = socket.getInputStream().read(buf);
                        String response = new String(buf, 0, bytesRead);
                        assertNotNull(response);
                    } catch (IOException e) {
                        fail("Client " + clientId + " failed: " + e.getMessage());
                    } finally {
                        allDone.countDown();
                    }
                }).start();
            }

            assertTrue(allDone.await(5, TimeUnit.SECONDS),
                    "All " + numClients + " clients should complete within timeout");
        } finally {
            server.close();
        }
    }

    @Test
    void connectSendCloseCycleRepeats() throws IOException, InterruptedException {
        int port = nextPort();
        Server server = new Server("127.0.0.1", port);
        server.initialize();
        server.start();

        try {
            Thread.sleep(200);

            for (int cycle = 0; cycle < 3; cycle++) {
                try (Socket socket = new Socket("127.0.0.1", port)) {
                    String request = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
                    socket.getOutputStream().write(request.getBytes());
                    socket.getOutputStream().flush();

                    byte[] buf = new byte[4096];
                    int bytesRead = socket.getInputStream().read(buf);
                    String response = new String(buf, 0, bytesRead);
                    assertNotNull(response);
                }
            }
        } finally {
            server.close();
        }
    }

    @Test
    void stopAndRestartOnSamePort() throws IOException, InterruptedException {
        int port = nextPort();

        // First server instance
        Server server = new Server("127.0.0.1", port);
        server.initialize();
        server.start();
        Thread.sleep(200);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            assertTrue(socket.isConnected());
        }
        server.close();

        // Allow TIME_WAIT to clear
        Thread.sleep(500);

        // Second server instance on same port
        Server server2 = new Server("127.0.0.1", port);
        try {
            server2.initialize();
            server2.start();
            Thread.sleep(200);

            try (Socket socket = new Socket("127.0.0.1", port)) {
                assertTrue(socket.isConnected());

                String request = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
                socket.getOutputStream().write(request.getBytes());
                socket.getOutputStream().flush();

                byte[] buf = new byte[4096];
                int bytesRead = socket.getInputStream().read(buf);
                String response = new String(buf, 0, bytesRead);
                assertTrue(response.contains("200 OK") || response.contains("Request logged"));
            }
        } finally {
            server2.close();
        }
    }
}
