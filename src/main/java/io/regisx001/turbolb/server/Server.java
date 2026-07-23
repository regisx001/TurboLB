package io.regisx001.turbolb.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Event-driven TCP server using Java NIO (Selector).
 *
 * <p>
 * Analogous to the C++ epoll-based Server, this class uses
 * {@link Selector} for non-blocking I/O multiplexing. It is
 * single-threaded — the entire event loop runs in one thread.
 *
 * <p>
 * This class owns only networking concerns:
 * <ul>
 * <li>Initialise the listening socket</li>
 * <li>Run the selector event loop</li>
 * <li>Accept new client connections</li>
 * <li>Dispatch read / write / connect events to a {@link ServerHandler}</li>
 * <li>Graceful shutdown via an internal wakeup channel</li>
 * </ul>
 *
 * <p>
 * Protocol logic, backend selection, proxying, and response generation
 * are the responsibility of the {@link ServerHandler} implementation.
 */
public class Server implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Server.class.getName());

    private static final int BUFFER_SIZE = 8192;

    private final String host;
    private final int port;
    private final ServerHandler handler;
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
     * @param host    the bind address
     * @param port    the bind port
     * @param handler the handler for application-level events
     */
    public Server(String host, int port, ServerHandler handler) {
        this.host = host;
        this.port = port;
        this.handler = handler;
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

    /**
     * Exposes the internal {@link Selector} so handlers can register
     * channels or modify interest ops on existing {@link SelectionKey}s.
     */
    public Selector getSelector() {
        return selector;
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
                    } else if (key.isConnectable()) {
                        handleConnect(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
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

    /**
     * Accepts all pending connections on the listening socket.
     *
     * <p>
     * Internal wakeup-channel accepts are handled silently. Real client
     * connections are registered for {@code OP_READ} and dispatched to
     * {@link ServerHandler#onAccept(SocketChannel)}.
     */
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel;

        while ((clientChannel = serverChannel.accept()) != null) {
            clientChannel.configureBlocking(false);

            if (serverChannel == wakeupServerChannel) {
                // Internal wakeup channel — complete the handshake silently
                wakeupServerSide = clientChannel;
                clientChannel.register(selector, SelectionKey.OP_READ);
                continue;
            }

            clientChannel.register(selector, SelectionKey.OP_READ);
            LOG.fine("Accepted connection from " + safeRemoteAddress(clientChannel));
            handler.onAccept(clientChannel);
        }
    }

    /**
     * Handles a connectable event — an outbound connection handshake completed.
     *
     * <p>
     * Internal wakeup-channel connects are completed silently. Real
     * connections are dispatched to
     * {@link ServerHandler#onConnectReady(SocketChannel)}.
     */
    private void handleConnect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();

        if (channel == wakeupClientChannel) {
            // Wakeup channel connection — complete handshake, register for reads
            if (channel.finishConnect()) {
                channel.register(selector, SelectionKey.OP_READ);
            }
            return;
        }

        if (channel.finishConnect()) {
            LOG.fine("Outbound connection established: " + safeRemoteAddress(channel));
            handler.onConnectReady(channel);
        }
    }

    /**
     * Reads all available data from the channel, then dispatches to
     * the handler. If the peer closed the connection, the channel is
     * closed and the handler is notified via {@link ServerHandler#onDisconnect}.
     */
    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buf = ByteBuffer.allocate(BUFFER_SIZE);
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        int bytesRead;
        while ((bytesRead = channel.read(buf)) > 0) {
            buf.flip();
            byte[] chunk = new byte[buf.remaining()];
            buf.get(chunk);
            data.write(chunk, 0, chunk.length);
            buf.clear();
        }

        if (data.size() > 0) {
            handler.onData(channel, data.toByteArray());
        }

        if (bytesRead == -1) {
            LOG.fine("Client disconnected: " + safeRemoteAddress(channel));
            key.cancel();
            channel.close();
            handler.onDisconnect(channel);
        }
    }

    /**
     * Handles a writable event — dispatched when a channel previously
     * registered for {@code OP_WRITE} has buffer space available.
     */
    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        handler.onWriteReady(channel);
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

        int wakeupPort = ((InetSocketAddress) wakeupServerChannel.getLocalAddress()).getPort();
        wakeupClientChannel = SocketChannel.open();
        wakeupClientChannel.configureBlocking(false);
        wakeupClientChannel.connect(new InetSocketAddress("127.0.0.1", wakeupPort));

        wakeupClientChannel.register(selector, SelectionKey.OP_CONNECT);
    }

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

    // ── Utilities ──────────────────────────────────────────────────────────

    private static String safeRemoteAddress(SocketChannel channel) {
        try {
            return channel.getRemoteAddress().toString();
        } catch (IOException e) {
            return "unknown";
        }
    }
}
