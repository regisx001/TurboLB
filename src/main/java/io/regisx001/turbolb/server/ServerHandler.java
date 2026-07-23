package io.regisx001.turbolb.server;

import java.nio.channels.SocketChannel;

/**
 * Callback interface for the event-driven {@link Server}.
 *
 * <p>
 * Implementations handle application-level logic for TCP connections
 * managed by the Server's NIO event loop. The Server owns the selector
 * and I/O multiplexing; the handler owns the protocol / business logic.
 *
 * <p>
 * All callbacks are invoked from the Server's single event-loop thread,
 * so implementations should never block.
 */
public interface ServerHandler {

    /**
     * Called when a new client connection has been accepted and registered
     * with the selector for {@code OP_READ}.
     *
     * @param clientChannel the newly accepted client channel (non-blocking)
     */
    void onAccept(SocketChannel clientChannel);

    /**
     * Called when data has been read from a channel.
     *
     * @param channel the channel data was read from
     * @param data    the raw bytes read
     */
    void onData(SocketChannel channel, byte[] data);

    /**
     * Called when a channel becomes writable (after {@code OP_WRITE} was
     * previously registered on the key).
     *
     * @param channel the channel ready for writing
     */
    void onWriteReady(SocketChannel channel);

    /**
     * Called when an outbound connection initiated via
     * {@link SocketChannel#connect(java.net.SocketAddress)} completes.
     *
     * @param channel the channel whose connection handshake finished
     */
    void onConnectReady(SocketChannel channel);

    /**
     * Called when the remote peer has closed the connection (read returned -1).
     * The channel has already been closed and its key cancelled by the Server
     * before this callback is invoked.
     *
     * @param channel the channel that disconnected
     */
    void onDisconnect(SocketChannel channel);
}
