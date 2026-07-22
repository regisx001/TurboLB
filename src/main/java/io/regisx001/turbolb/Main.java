package io.regisx001.turbolb;

import java.io.IOException;

import io.regisx001.turbolb.server.Server;

import io.regisx001.turbolb.config.Config;
import io.regisx001.turbolb.domain.Backend;

import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {
    private static final Logger LOG = Logger.getLogger(App.class.getName());

    public static void main(String[] args) {
        try {
            // ── Load configuration ─────────────────────────────────────────
            Config config = Config.load(args);

            String host = config.getString("server.host");
            int port = config.getInt("server.port");
            boolean debug = config.getBool("server.debug", false);

            List<Backend> backends = config.getBackends();

            for (Backend backend : backends) {
                System.out.println(backend.host() + ":" + backend.port());
            }

        } catch (IOException e) {
            System.err.println("Fatal: " + e.getMessage());
            System.exit(1);
        }
    }
}
