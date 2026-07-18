package io.regisx001.turbolb.server;

import io.regisx001.turbolb.http.HttpParser;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Event-driven TCP server using Java NIO (Selector).
 *
 * Analogous to the C++ epoll-based Server, this class uses
 * {@link Selector} for non-blocking I/O multiplexing. It is
 * single-threaded — the entire event loop runs in one thread,
 * matching the architecture described in the engineering journal.
 *
 * Features:
 * <ul>
 * <li>Non-blocking {@link ServerSocketChannel} for listening</li>
 * <li>{@link Selector} for event notification (analogous to epoll)</li>
 * <li>Edge-style handling: read until EAGAIN-equivalent on each wakeup</li>
 * <li>HTTP request parsing via {@link HttpParser}</li>
 * <li>Graceful shutdown via an internal wakeup channel</li>
 * </ul>
 */
public class Server implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Server.class.getName());

    private static final int BUFFER_SIZE = 8192;

    private final String host;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private Thread eventLoopThread;

    // Internal wakeup channel to interrupt select() on stop()
    private ServerSocketChannel wakeupServerChannel;
    private SocketChannel wakeupClientChannel;
    private SocketChannel wakeupServerSide;

    /**
     * Creates a new Server instance bound to the given address.
     *
     * @param host the bind address
     * @param port the bind port
     */
    public Server(String host, int port) {
        this.host = host;
        this.port = port;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Initializes the server: opens the listening socket, binds, and
     * sets up the selector. Does NOT start the event loop.
     *
     * @throws IOException if socket or selector setup fails
     */
    public void initialize() throws IOException {
        // ── Listening socket ───────────────────────────────────────────────
        serverChannel = ServerSocketChannel.open();
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(host, port));

        // ── Selector ──────────────────────────────────────────────────────
        selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        // ── Wakeup channel (self-pipe trick for Java NIO) ──────────────────
        // We use a loopback connection to interrupt selector.select()
        // when stop() is called, since Selector.wakeup() is unreliable
        // with concurrent close() calls.
        setupWakeupChannel();

        LOG.info("Server initialized on " + host + ":" + port);
    }

    /**
     * Starts the event loop in a background thread.
     *
     * @throws IOException if the server has not been initialized
     */
    public void start() throws IOException {
        if (serverChannel == null || !serverChannel.isOpen()) {
            throw new IOException("Server not initialized. Call initialize() first.");
        }

        running.set(true);
        eventLoopThread = new Thread(this::eventLoop, "turbolb-event-loop");
        eventLoopThread.setDaemon(true);
        eventLoopThread.start();
        LOG.info("Server event loop started");
    }

    /**
     * Runs the event loop on the calling thread (blocking).
     *
     * @throws IOException if the server has not been initialized
     */
    public void run() throws IOException {
        if (serverChannel == null || !serverChannel.isOpen()) {
            throw new IOException("Server not initialized. Call initialize() first.");
        }

        running.set(true);
        eventLoop();
    }

    /**
     * Stops the server gracefully. Interrupts the event loop,
     * closes all channels, and waits for the event loop thread
     * to finish.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            // Not running — just clean up and return
            cleanup();
            return;
        }

        // Interrupt the wakeup channel so select() returns
        try {
            if (wakeupClientChannel != null && wakeupClientChannel.isConnected()) {
                wakeupClientChannel.write(ByteBuffer.wrap(new byte[] { 0 }));
            }
        } catch (IOException e) {
            // Best-effort
        }

        // Wait for the event loop thread to finish
        if (Thread.currentThread() != eventLoopThread && eventLoopThread != null) {
            try {
                eventLoopThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        cleanup();
        LOG.info("Server stopped");
    }

    /**
     * Returns whether the server event loop is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the port the server is bound to (0 if not yet bound).
     */
    public int getPort() {
        if (serverChannel != null && serverChannel.isOpen()) {
            try {
                return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            } catch (IOException e) {
                return 0;
            }
        }
        return 0;
    }

    // ── Event Loop ────────────────────────────────────────────────────────

    private void eventLoop() {
        try {
            while (running.get()) {
                int ready = selector.select();
                if (!running.get())
                    break;
                if (ready == 0)
                    continue;

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();

                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    if (!key.isValid())
                        continue;

                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    }
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                LOG.log(Level.SEVERE, "Event loop error", e);
            }
        } finally {
            cleanup();
        }
    }

    // ── I/O Handlers ──────────────────────────────────────────────────────

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel;

        // Accept all pending connections (edge-style, though Java NIO
        // is level-triggered by default — this still minimises wakeups)
        while ((clientChannel = serverChannel.accept()) != null) {
            clientChannel.configureBlocking(false);
            clientChannel.register(selector, SelectionKey.OP_READ);
            LOG.fine("Accepted connection from " + clientChannel.getRemoteAddress());
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer buf = ByteBuffer.allocate(BUFFER_SIZE);
        StringBuilder requestData = new StringBuilder();

        // Read all available data (analogous to edge-triggered reading)
        int bytesRead;
        boolean remoteClosed = false;

        while ((bytesRead = clientChannel.read(buf)) > 0) {
            buf.flip();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            requestData.append(new String(data, StandardCharsets.UTF_8));
            buf.clear();
        }

        if (bytesRead == -1) {
            remoteClosed = true;
        }

        if (requestData.length() > 0) {
            String rawRequest = requestData.toString();
            LOG.fine("Received " + rawRequest.length() + " bytes from " + safeRemoteAddress(clientChannel));

            // Parse the HTTP request
            HttpParser parser = new HttpParser();
            HttpParser.State result = parser.consume(rawRequest);

            switch (result) {
                case COMPLETE: {
                    logParsedRequest(parser, clientChannel);

                    String httpResponse = buildHttpResponse(parser);
                    ByteBuffer responseBuf = ByteBuffer.wrap(httpResponse.getBytes(StandardCharsets.UTF_8));
                    clientChannel.write(responseBuf);
                    break;
                }
                case ERROR: {
                    LOG.warning("Malformed request from " + safeRemoteAddress(clientChannel));
                    String errorResponse = "HTTP/1.1 400 Bad Request\r\n" +
                            "Content-Length: 15\r\n" +
                            "Connection: close\r\n" +
                            "\r\n" +
                            "400 Bad Request";
                    ByteBuffer responseBuf = ByteBuffer.wrap(errorResponse.getBytes(StandardCharsets.UTF_8));
                    clientChannel.write(responseBuf);
                    break;
                }
                default: {
                    // Partial data — wait for more
                    LOG.fine("Partial request (" + result + "), waiting for more data");
                    return;
                }
            }
        }

        if (remoteClosed) {
            LOG.fine("Client disconnected: " + safeRemoteAddress(clientChannel));
            key.cancel();
            clientChannel.close();
        }
    }

    // ── Wakeup Channel (Self-Pipe Trick) ──────────────────────────────────

    /**
     * Sets up a local loopback connection used to interrupt
     * {@code selector.select()} when {@link #stop()} is called.
     *
     * This is the Java NIO equivalent of the self-pipe trick or
     * eventfd used in the C++ epoll implementation.
     */
    private void setupWakeupChannel() throws IOException {
        wakeupServerChannel = ServerSocketChannel.open();
        wakeupServerChannel.configureBlocking(false);
        wakeupServerChannel.bind(new InetSocketAddress("127.0.0.1", 0));
        wakeupServerChannel.register(selector, SelectionKey.OP_ACCEPT);

        // Connect a client to ourselves
        int wakeupPort = ((InetSocketAddress) wakeupServerChannel.getLocalAddress()).getPort();
        wakeupClientChannel = SocketChannel.open();
        wakeupClientChannel.configureBlocking(false);
        wakeupClientChannel.connect(new InetSocketAddress("127.0.0.1", wakeupPort));

        // Register for OP_CONNECT to complete the handshake
        wakeupClientChannel.register(selector, SelectionKey.OP_CONNECT);
    }

    /**
     * In the event loop, accept the wakeup server side and
     * register it for reads (handled via the main accept logic).
     */

    // ── Cleanup ────────────────────────────────────────────────────────────

    private void cleanup() {
        try {
            if (wakeupClientChannel != null) {
                wakeupClientChannel.close();
            }
        } catch (IOException ignored) {
        }

        try {
            if (wakeupServerSide != null) {
                wakeupServerSide.close();
            }
        } catch (IOException ignored) {
        }

        try {
            if (wakeupServerChannel != null) {
                wakeupServerChannel.close();
            }
        } catch (IOException ignored) {
        }

        try {
            if (selector != null && selector.isOpen()) {
                selector.close();
            }
        } catch (IOException ignored) {
        }

        try {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ── HTTP Response Building ─────────────────────────────────────────────

    private static String buildHttpResponse(HttpParser parser) {
        String body = "Request logged!\n";
        return "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body;
    }

    private static void logParsedRequest(HttpParser parser, SocketChannel clientChannel) {
        LOG.info("\n===== Client connected: " + safeRemoteAddress(clientChannel) + " =====" +
                "\n--- Parsed HTTP Request ---" +
                "\nMethod:  " + parser.getMethod() +
                "\nURI:     " + parser.getUri() +
                "\nVersion: " + parser.getVersion() +
                "\nHeaders:");
        parser.getHeaders().forEach((key, value) -> LOG.info("  " + key + ": " + value));
        if (parser.getBody() != null && !parser.getBody().isEmpty()) {
            LOG.info("Body (" + parser.getBody().length() + " bytes):");
            LOG.info(parser.getBody());
        }
        LOG.info("--------------------------");
    }

    private static String safeRemoteAddress(SocketChannel channel) {
        try {
            return channel.getRemoteAddress().toString();
        } catch (IOException e) {
            return "unknown";
        }
    }
}
