package io.regisx001.turbolb;

import io.regisx001.turbolb.config.Config;
import io.regisx001.turbolb.server.Server;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TurboLB — A lightweight, event-driven TCP load balancer.
 *
 * <p>
 * Entry point that wires together configuration and the
 * NIO-based event-driven server.
 * </p>
 *
 * <pre>
 * Usage:
 *   java -jar Turbolb.jar                     # uses .turbolb/config.properties
 *   java -jar Turbolb.jar --config /path/to/config.properties
 *   TURBOLB_CONFIG=/path/to/config.properties java -jar Turbolb.jar
 * </pre>
 */
public class App {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    public static void main(String[] args) {
        try {
            // ── Load configuration ─────────────────────────────────────────
            Config config = Config.load(args);

            String host = config.getString("server.host");
            int port = config.getInt("server.port");
            boolean debug = config.getBool("server.debug", false);

            if (debug) {
                LOG.setLevel(Level.FINE);
            }

            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║          TurboLB — Load Balancer        ║");
            System.out.println("╚══════════════════════════════════════════╝");
            System.out.println("Starting on " + host + ":" + port);
            System.out.println("Debug mode: " + debug);

            // ── Start server ──────────────────────────────────────────────
            Server server = new Server(host, port);
            server.initialize();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down...");
                server.stop();
            }));

            server.run();

        } catch (IOException e) {
            System.err.println("Fatal: " + e.getMessage());
            System.exit(1);
        }
    }
}
