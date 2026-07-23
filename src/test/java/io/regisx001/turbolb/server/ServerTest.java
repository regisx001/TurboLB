package io.regisx001.turbolb.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and integration tests for the {@link Server} class.
 *
 * Uses unique ports per test via an atomic counter to avoid collisions.
 * Integration tests verify the event-dispatch contract via a
 * {@link TestHandler} instead of checking HTTP responses.
 */
class ServerTest {

    private static final AtomicInteger portCounter = new AtomicInteger(25000);

    private static int nextPort() {
        return portCounter.incrementAndGet();
    }

    /** A no-op handler for unit tests that only test lifecycle. */
    private static final ServerHandler NOOP_HANDLER = new ServerHandler() {
        @Override
        public void onAccept(SocketChannel c) {
        }

        @Override
        public void onData(SocketChannel c, byte[] d) {
        }

        @Override
        public void onWriteReady(SocketChannel c) {
        }

        @Override
        public void onConnectReady(SocketChannel c) {
        }

        @Override
        public void onDisconnect(SocketChannel c) {
        }
    };

    // ── Unit Tests ─────────────────────────────────────────────────────────

    @Test
    void serverConstructionDoesNotThrow() {
        assertDoesNotThrow(() -> {
            try (Server s = new Server("127.0.0.1", nextPort(), NOOP_HANDLER)) {
                // constructor only — no resources opened yet
            }
        });
    }

    @Test
    void serverDestructorIsSafeWithoutInitialize() {
        Server server = new Server("127.0.0.1", nextPort(), NOOP_HANDLER);
        server.close();
    }

    @Test
    void serverInitializeSucceedsOnFreePort() throws IOException {
        Server server = new Server("127.0.0.1", nextPort(), NOOP_HANDLER);
        try {
            assertDoesNotThrow(server::initialize);
        } finally {
            server.close();
        }
    }

    @Test
    void multipleServersCanBeCreatedAndDestroyed() throws IOException {
        for (int i = 0; i < 5; i++) {
            Server server = new Server("127.0.0.1", nextPort(), NOOP_HANDLER);
            server.initialize();
            server.close();
        }
    }

    @Test
    void stopIsSafeWithoutInitialize() {
        Server server = new Server("127.0.0.1", nextPort(), NOOP_HANDLER);
        assertDoesNotThrow(server::close);
    }

    @Test
    void stopIsSafeBeforeRun() throws IOException {
        Server server = new Server("127.0.0.1", nextPort(), NOOP_HANDLER);
        server.initialize();
        assertDoesNotThrow(server::close);
    }

    @Test
    void isRunningReturnsFalseInitially() {
        Server server = new Server("127.0.0.1", nextPort(), NOOP_HANDLER);
        assertFalse(server.isRunning());
        server.close();
    }

    @Test
    void getPortReturnsPortAfterInitialize() throws IOException {
        int port = nextPort();
        Server server = new Server("127.0.0.1", port, NOOP_HANDLER);
        try {
            server.initialize();
            assertEquals(port, server.getPort());
        } finally {
            server.close();
        }
    }

    @Test
    void getPortReturnsZeroBeforeInitialize() {
        Server server = new Server("127.0.0.1", nextPort(), NOOP_HANDLER);
        assertEquals(0, server.getPort());
        server.close();
    }

    // ── Port Reuse ─────────────────────────────────────────────────────────

    @Test
    void portCanBeReusedAfterClose() throws IOException {
        int port = nextPort();

        Server server = new Server("127.0.0.1", port, NOOP_HANDLER);
        server.initialize();
        server.close();

        Server server2 = new Server("127.0.0.1", port, NOOP_HANDLER);
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
            Server server = new Server("127.0.0.1", port, NOOP_HANDLER);
            try {
                server.initialize();
            } finally {
                server.close();
            }
        }
    }

    // ── Integration Tests ──────────────────────────────────────────────────

    @Test
    void serverAcceptsConnectionAndReadsData() throws IOException, InterruptedException {
        int port = nextPort();
        TestHandler handler = new TestHandler();
        Server server = new Server("127.0.0.1", port, handler);
        server.initialize();
        server.start();

        try {
            Thread.sleep(200); // let server bind

            try (Socket socket = new Socket("127.0.0.1", port)) {
                assertTrue(socket.isConnected());

                assertTrue(handler.accepted.await(2, TimeUnit.SECONDS),
                        "Server should accept a connection");

                String payload = "hello from test";
                socket.getOutputStream().write(payload.getBytes());
                socket.getOutputStream().flush();

                assertTrue(handler.dataReceived.await(2, TimeUnit.SECONDS),
                        "Server should read data from client");
                assertNotNull(handler.receivedData);
                assertEquals(payload, new String(handler.receivedData));
            }

            assertTrue(handler.disconnected.await(2, TimeUnit.SECONDS),
                    "Server should detect client disconnect");
        } finally {
            server.close();
        }
    }

    @Test
    void handlesMultipleConcurrentClients() throws IOException, InterruptedException {
        int port = nextPort();
        MultiClientHandler handler = new MultiClientHandler(5);
        Server server = new Server("127.0.0.1", port, handler);
        server.initialize();
        server.start();

        try {
            int numClients = 5;
            CountDownLatch allConnected = new CountDownLatch(numClients);

            for (int i = 0; i < numClients; i++) {
                int clientId = i;
                new Thread(() -> {
                    try (Socket socket = new Socket("127.0.0.1", port)) {
                        allConnected.countDown();
                        String payload = "data from client " + clientId;
                        socket.getOutputStream().write(payload.getBytes());
                        socket.getOutputStream().flush();
                        // Keep connection open briefly so the server can read
                        Thread.sleep(500);
                    } catch (Exception e) {
                        fail("Client " + clientId + " failed: " + e.getMessage());
                    }
                }).start();
            }

            assertTrue(allConnected.await(3, TimeUnit.SECONDS),
                    "All clients should connect");
            assertTrue(handler.allDataReceived.await(5, TimeUnit.SECONDS),
                    "Server should receive data from all clients");
            assertEquals(numClients, handler.acceptCount.get(),
                    "All clients should be accepted");
        } finally {
            server.close();
        }
    }

    @Test
    void connectSendCloseCycleRepeats() throws IOException, InterruptedException {
        int port = nextPort();
        TestHandler handler = new TestHandler();
        Server server = new Server("127.0.0.1", port, handler);
        server.initialize();
        server.start();

        try {
            for (int cycle = 0; cycle < 3; cycle++) {
                String payload = "cycle " + cycle;

                try (Socket socket = new Socket("127.0.0.1", port)) {
                    socket.getOutputStream().write(payload.getBytes());
                    socket.getOutputStream().flush();
                }

                // Each cycle: accept + data + disconnect
                Thread.sleep(300);
            }
        } finally {
            server.close();
        }
    }

    @Test
    void stopAndRestartOnSamePort() throws IOException, InterruptedException {
        int port = nextPort();

        // First server instance
        TestHandler handler1 = new TestHandler();
        Server server = new Server("127.0.0.1", port, handler1);
        server.initialize();
        server.start();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            assertTrue(socket.isConnected());
            assertTrue(handler1.accepted.await(2, TimeUnit.SECONDS));
        }
        server.close();

        // Allow TIME_WAIT to clear
        Thread.sleep(500);

        // Second server instance on same port
        TestHandler handler2 = new TestHandler();
        Server server2 = new Server("127.0.0.1", port, handler2);
        try {
            server2.initialize();
            server2.start();

            try (Socket socket = new Socket("127.0.0.1", port)) {
                assertTrue(socket.isConnected());

                String payload = "hello after restart";
                socket.getOutputStream().write(payload.getBytes());
                socket.getOutputStream().flush();

                assertTrue(handler2.dataReceived.await(2, TimeUnit.SECONDS),
                        "Second server instance should read data");
                assertEquals(payload, new String(handler2.receivedData));
            }
        } finally {
            server2.close();
        }
    }

    // ── Test Handlers ──────────────────────────────────────────────────────

    /** Records a single accept / data / disconnect cycle. */
    private static class TestHandler implements ServerHandler {
        final CountDownLatch accepted = new CountDownLatch(1);
        final CountDownLatch dataReceived = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        volatile byte[] receivedData;

        @Override
        public void onAccept(SocketChannel clientChannel) {
            accepted.countDown();
        }

        @Override
        public void onData(SocketChannel channel, byte[] data) {
            receivedData = data;
            dataReceived.countDown();
        }

        @Override
        public void onWriteReady(SocketChannel channel) {
        }

        @Override
        public void onConnectReady(SocketChannel channel) {
        }

        @Override
        public void onDisconnect(SocketChannel channel) {
            disconnected.countDown();
        }
    }

    /** Counts accepts and data events across multiple clients. */
    private static class MultiClientHandler implements ServerHandler {
        final AtomicInteger acceptCount = new AtomicInteger(0);
        final CountDownLatch allDataReceived;
        final int expectedClients;

        MultiClientHandler(int expectedClients) {
            this.expectedClients = expectedClients;
            this.allDataReceived = new CountDownLatch(expectedClients);
        }

        @Override
        public void onAccept(SocketChannel clientChannel) {
            acceptCount.incrementAndGet();
        }

        @Override
        public void onData(SocketChannel channel, byte[] data) {
            allDataReceived.countDown();
        }

        @Override
        public void onWriteReady(SocketChannel channel) {
        }

        @Override
        public void onConnectReady(SocketChannel channel) {
        }

        @Override
        public void onDisconnect(SocketChannel channel) {
        }
    }
}
