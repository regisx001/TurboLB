#pragma once
#ifndef TURBOLB_SERVER_HPP
#define TURBOLB_SERVER_HPP

#include <string>

/**
 * @brief TCP server core class for TurboLB.
 *
 * This class manages the listening socket and the event-driven accept loop.
 * It abstracts away the low-level POSIX socket operations into a clean RAII interface.
 *
 * @note This implementation uses NON-BLOCKING sockets + epoll for scalability.
 *       It can handle thousands of simultaneous connections.
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
     * @brief Destructor — closes the listening socket and epoll instance if still open.
     *
     * RAII ensures resources are cleaned up automatically.
     */
    ~Server();

    /**
     * @brief Creates and configures the listening socket.
     *
     * Steps performed:
     *   1. socket()     — create a TCP socket
     *   2. setsockopt() — enable SO_REUSEADDR to avoid "Address already in use"
     *   3. bind()       — attach to the specified port
     *   4. listen()     — start accepting connections with a backlog of 10
     *   5. setNonBlocking() — make the listening socket non-blocking
     *   6. epoll_create1() — create an epoll instance
     *   7. epoll_ctl(ADD) — add the listening socket to epoll
     *
     * @return true on success, false on failure (with error printed to stderr)
     */
    bool initialize();

    /**
     * @brief The main event loop — runs until stop() is called.
     *
     * This is the heart of the server. It repeatedly calls epoll_wait()
     * to get a list of file descriptors that are ready for I/O.
     *
     * - If the listening socket is ready: call handleNewConnection()
     * - If a client socket is ready: call handleClientData()
     *
     * @note This function blocks in epoll_wait() until an event occurs.
     *       It does NOT block on individual I/O operations because all
     *       sockets are non-blocking.
     */
    void run();

    /**
     * @brief Signal the run() loop to exit gracefully.
     *
     * @note This only sets a flag. The run() loop will exit after the
     *       current iteration of epoll_wait() completes. Since epoll_wait()
     *       blocks, we need an eventfd or self-pipe trick to interrupt it.
     *       That will be added in a future enhancement.
     */
    void stop();

private:
    // Core server state
    int port_;      ///< TCP port the server binds to
    int server_fd_; ///< Listening socket file descriptor, or -1 if not initialized
    bool running_;  ///< Controls the accept loop; set to false to exit
    int epoll_fd_;  ///< epoll instance file descriptor

    // Helper methods
    /**
     * @brief Set a socket to non-blocking mode.
     *
     * @param fd The file descriptor to modify
     * @return true on success, false on failure
     *
     * @note Without this, epoll_wait() would still block on read/write.
     */
    bool setNonBlocking(int fd);

    /**
     * @brief Create the epoll instance.
     *
     * @return true on success, false on failure
     *
     * @note epoll_create1(0) is equivalent to epoll_create() with the old API.
     *       The 0 means no flags are set (EPOLL_CLOEXEC is default in modern glibc).
     */
    bool setupEpoll();

    /**
     * @brief Accept all pending connections on the listening socket.
     *
     * This is called when the listening socket is ready for reading (EPOLLIN).
     * It loops until accept() returns EAGAIN/EWOULDBLOCK, meaning no more
     * pending connections.
     *
     * For each accepted client:
     *   1. Set the client socket to non-blocking
     *   2. Add it to epoll with EPOLLIN | EPOLLET (edge-triggered)
     *   3. Log the new connection
     */
    void handleNewConnection();

    /**
     * @brief Read data from a client socket and echo it back.
     *
     * This is called when a client socket is ready for reading (EPOLLIN).
     *
     * Steps:
     *   1. recv() up to 4096 bytes
     *   2. If recv() returns 0: client closed the connection → removeClient()
     *   3. If recv() returns < 0 and errno is EAGAIN/EWOULDBLOCK: no data to read
     *   4. If recv() returns > 0: echo the data back to the client
     *
     * @param client_fd The client socket file descriptor
     *
     * @note This currently echoes data back. In future stages, it will
     *       forward the request to a backend server.
     */
    void handleClientData(int client_fd);

    /**
     * @brief Remove a client connection from epoll and close the socket.
     *
     * @param client_fd The client socket file descriptor
     */
    void removeClient(int client_fd);
};

#endif // TURBOLB_SERVER_HPP