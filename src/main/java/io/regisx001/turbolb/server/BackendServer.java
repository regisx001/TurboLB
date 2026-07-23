package io.regisx001.turbolb.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.logging.Logger;

public class BackendServer {
    private static final Logger LOG = Logger.getLogger(Server.class.getName());

    private final String host;
    private final int port;
    private final ServerSocketChannel server;
    private final Selector selector;

    public BackendServer(String host, int port) throws IOException {
        this.host = host;
        this.port = port;
        this.selector = Selector.open();
        this.server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(host, port));
        server.configureBlocking(false);
    }

    public void eventLoop() throws IOException {
        server.register(selector, SelectionKey.OP_ACCEPT);
        while (true) {
            selector.select();

            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                iterator.remove();

                if (key.isAcceptable()) {
                    acceptConnection(server, selector);
                }
            }

        }
    }

    private static void acceptConnection(ServerSocketChannel server,
            Selector selector) throws IOException {

        SocketChannel client = server.accept();

        if (client == null) {
            return;
        }

        client.configureBlocking(false);

        System.out.println("Client connected: " + client.getRemoteAddress());
    }
}
