#pragma once
#ifndef TURBOLB_SERVER_HPP
#define TURBOLB_SERVER_HPP

#include <string>

/**
 * @brief TCP server core class for TurboLB.
 *
 * This class manages the listening socket and the accept loop.
 * It abstracts away the low-level POSIX socket operations into
 * a clean RAII interface.
 *
 * @note The current implementation is BLOCKING and SINGLE-THREADED.
 *       This is intentional for the learning phase.
 *       Future iterations will use non-blocking sockets + epoll.
 */
class Server
{
public:
    /**
     * @brief Construct a Server that will listen on the given port.
     *
     * @param port The TCP port number to bind to.
     */
    explicit Server(int port);

    /**
     * @brief Destructor — closes the listening socket if still open.
     *
     * RAII ensures the socket is cleaned up automatically.
     */
    ~Server();

    /**
     * @brief Creates and configures the listening socket.
     *
     * Steps performed:
     *   1. socket()   — create a TCP socket
     *   2. setsockopt() — enable SO_REUSEADDR to avoid "Address already in use"
     *   3. bind()     — attach to the specified port
     *   4. listen()   — start accepting connections with a backlog of 10
     *
     * @return true on success, false on failure (with error printed to stderr)
     */
    bool initialize();

    /**
     * @brief The main accept loop — runs until stop() is called.
     *
     * Currently handles one client at a time (blocking accept).
     * Each accepted client gets a hardcoded response, then the connection is closed.
     *
     * @note This function blocks indefinitely until interrupted or stop() is called.
     *       There is no graceful shutdown yet — that's a future enhancement.
     */
    void run();

    /**
     * @brief Signal the run() loop to exit gracefully.
     *
     * @note This only sets a flag. It does NOT interrupt a blocking accept() call.
     *       Future work: integrate with epoll + eventfd or self-pipe trick.
     */
    void stop();

private:
    int port_;      ///< TCP port the server binds to
    int server_fd_; ///< Listening socket file descriptor, or -1 if not initialized
    bool running_;  ///< Controls the accept loop; set to false to exit
};

#endif // TURBOLB_SERVER_HPP