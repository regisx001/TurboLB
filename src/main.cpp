#include <iostream>
#include <cstdlib>
#include <csignal>

#include "lb/Config.hpp"
#include "lb/Server.hpp"

// Forward declaration so the signal handler can reach the server pointer
static Server *g_server = nullptr;

/**
 * @brief Signal handler for SIGINT/SIGTERM — requests graceful shutdown.
 *
 * @note Currently stop() only sets a flag; epoll_wait() won't be interrupted
 *       until the next event arrives. A proper eventfd or self-pipe mechanism
 *       will be added in a future enhancement (see ENGINEERING.md).
 */
static void handleSignal(int sig)
{
    std::cout << "\nCaught signal " << sig << ", shutting down...\n";
    if (g_server)
    {
        g_server->stop();
    }
}

int main(int argc, char *argv[])
{
    // ── Load configuration ────────────────────────────────────────────
    auto config = Config::load(argc, argv);

    auto host = config.getString("server.host");
    auto port = config.getInt("server.port");

    std::cout << "TurboLB starting on " << host << ":" << port << "\n";

    // ── Register signal handlers for graceful shutdown ────────────────
    std::signal(SIGINT, handleSignal);
    std::signal(SIGTERM, handleSignal);

    // ── Create and start the server ───────────────────────────────────
    Server server(port);
    g_server = &server;

    if (!server.initialize())
    {
        std::cerr << "Failed to initialize server on port " << port << "\n";
        return 1;
    }

    std::cout << "Server initialized. Entering event loop.\n";
    server.run();

    std::cout << "Server shut down cleanly.\n";
    return 0;
}