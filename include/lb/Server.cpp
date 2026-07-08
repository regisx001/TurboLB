#include "Server.hpp"

// Standard C++ headers
#include <iostream>
#include <cstring>

// POSIX socket headers
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>

// epoll and non-blocking I/O
#include <fcntl.h>     // fcntl() — for setting non-blocking mode
#include <sys/epoll.h> // epoll — event monitoring
#include <errno.h>     // errno — for error checking

// -----------------------------------------------------------------------------
// Constructor / Destructor
// -----------------------------------------------------------------------------

/**
 * @brief Constructor — initializes the server state.
 *
 * @param port The TCP port to listen on.
 *
 * @note All file descriptors are set to -1 to indicate "not initialized".
 *       running_ is set to false; initialize() will set it to true.
 */
Server::Server(int port)
    : port_(port), server_fd_(-1), running_(false), epoll_fd_(-1)
{
    // Everything is initialized in the initializer list.
    // No additional work needed here.
}

/**
 * @brief Destructor — ensures all resources are cleaned up.
 *
 * RAII principle: resources acquired in initialize() are released
 * when the Server object is destroyed.
 */
Server::~Server()
{
    // Close the listening socket if it was opened
    if (server_fd_ >= 0)
    {
        close(server_fd_);
    }

    // Close the epoll instance if it was created
    if (epoll_fd_ >= 0)
    {
        close(epoll_fd_);
    }
}

// -----------------------------------------------------------------------------
// Helper Methods
// -----------------------------------------------------------------------------

/**
 * @brief Set a socket to non-blocking mode.
 *
 * @param fd The file descriptor to modify.
 * @return true on success, false on failure.
 *
 * @details
 * Non-blocking mode is essential for epoll. Without it:
 *   - read()/recv() would block if no data is available
 *   - write()/send() would block if the socket buffer is full
 *
 * With non-blocking mode, these functions return immediately:
 *   - If data is available, they return the data
 *   - If no data is available, they return -1 and set errno to EAGAIN/EWOULDBLOCK
 *
 * The fcntl() call is the standard POSIX way to set O_NONBLOCK.
 */
bool Server::setNonBlocking(int fd)
{
    // Get the current flags
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0)
    {
        return false;
    }

    // Add the O_NONBLOCK flag
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0;
}

/**
 * @brief Create the epoll instance.
 *
 * @return true on success, false on failure.
 *
 * @details
 * epoll_create1(0) creates a new epoll instance.
 * The 0 means no flags (EPOLL_CLOEXEC is set by default in modern glibc).
 *
 * The returned file descriptor (epoll_fd_) is used for all subsequent
 * epoll_ctl() and epoll_wait() calls.
 */
bool Server::setupEpoll()
{
    epoll_fd_ = epoll_create1(0);
    return epoll_fd_ >= 0;
}

// -----------------------------------------------------------------------------
// Event Handlers
// -----------------------------------------------------------------------------

/**
 * @brief Accept all pending connections on the listening socket.
 *
 * @details
 * This function is called when epoll_wait() indicates that the listening
 * socket is ready (EPOLLIN).
 *
 * Since we use edge-triggered mode (EPOLLET), we must accept ALL pending
 * connections in a loop. If we stop after the first one, epoll won't
 * notify us again until the state changes again (e.g., new connections arrive).
 *
 * The loop breaks when accept() returns -1 with errno EAGAIN/EWOULDBLOCK,
 * indicating that no more connections are pending.
 *
 * For each accepted client:
 *   1. Set the client socket to non-blocking
 *   2. Add the client socket to epoll for reading (EPOLLIN) with edge-triggered (EPOLLET)
 *   3. Log the new connection
 *
 * @note Error handling: if epoll_ctl(ADD) fails, we close the client socket
 *       and continue. This prevents resource leaks.
 */
void Server::handleNewConnection()
{
    while (true)
    {
        // Accept a new connection
        sockaddr_in client_addr{};
        socklen_t client_len = sizeof(client_addr);
        int client_fd = accept(server_fd_, (sockaddr *)&client_addr, &client_len);

        if (client_fd < 0)
        {
            // If no more pending connections, break out of the loop
            if (errno == EAGAIN || errno == EWOULDBLOCK)
            {
                break;
            }

            // Other errors: log and break
            std::cerr << "accept() failed: " << errno << "\n";
            break;
        }

        // Make the client socket non-blocking
        if (!setNonBlocking(client_fd))
        {
            std::cerr << "setNonBlocking(client) failed for fd=" << client_fd << "\n";
            close(client_fd);
            continue;
        }

        // Add the client socket to epoll
        struct epoll_event ev{};
        ev.events = EPOLLIN | EPOLLET; // Ready for reading, edge-triggered
        ev.data.fd = client_fd;

        if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, client_fd, &ev) < 0)
        {
            std::cerr << "epoll_ctl(ADD) failed for client fd=" << client_fd << "\n";
            close(client_fd);
            continue;
        }

        std::cout << "New connection accepted (fd=" << client_fd << ")\n";
    }
}

/**
 * @brief Read data from a client socket and echo it back.
 *
 * @param client_fd The client socket file descriptor.
 *
 * @details
 * This function is called when epoll_wait() indicates that a client socket
 * is ready for reading (EPOLLIN).
 *
 * Since we use edge-triggered mode (EPOLLET), we must read ALL available
 * data in one go. The loop reads until recv() returns -1 with errno
 * EAGAIN/EWOULDBLOCK.
 *
 * The current implementation:
 *   1. Reads up to 4096 bytes into a buffer
 *   2. If recv() returns 0: client closed the connection
 *   3. If recv() returns < 0: error or no data available
 *   4. If recv() returns > 0: echo the data back to the client
 *
 * @note Future stages will replace the echo with a proxy to a backend server.
 *
 * @warning This function does NOT handle partial writes on send(). In a
 *          production system, we'd use EPOLLOUT to handle write readiness.
 */
void Server::handleClientData(int client_fd)
{
    char buffer[4096];
    ssize_t n = recv(client_fd, buffer, sizeof(buffer) - 1, 0);

    if (n <= 0)
    {
        if (n == 0)
        {
            // recv() returned 0: the client closed the connection
            std::cout << "Client closed connection (fd=" << client_fd << ")\n";
        }
        else if (errno != EAGAIN && errno != EWOULDBLOCK)
        {
            // recv() failed with an actual error (not just "no data available")
            std::cerr << "recv() error on fd=" << client_fd << ": " << errno << "\n";
        }
        // For EAGAIN/EWOULDBLOCK, we just return and wait for the next event
        removeClient(client_fd);
        return;
    }

    // Null-terminate the buffer for safe printing
    buffer[n] = '\0';
    std::cout << "Received " << n << " bytes from fd=" << client_fd << "\n";

    // Echo back what we received
    // TODO: In Stage 2, this will forward the request to a backend server
    send(client_fd, buffer, n, 0);
}

/**
 * @brief Remove a client connection from epoll and close the socket.
 *
 * @param client_fd The client socket file descriptor.
 *
 * @details
 * This function is called when:
 *   - The client closed the connection
 *   - An error occurred on the client socket
 *   - We're cleaning up the connection
 *
 * It removes the socket from epoll and then closes it.
 * This ensures no further events are delivered for this socket.
 */
void Server::removeClient(int client_fd)
{
    // Remove from epoll
    epoll_ctl(epoll_fd_, EPOLL_CTL_DEL, client_fd, nullptr);

    // Close the socket
    close(client_fd);

    // Note: we don't log here because the caller already logs the reason
}

// -----------------------------------------------------------------------------
// Public Interface
// -----------------------------------------------------------------------------

/**
 * @brief Initialize the server: create socket, bind, listen, setup epoll.
 *
 * @return true on success, false on failure.
 *
 * @details
 * This function performs all the setup steps:
 *   1. socket()     — create a TCP socket
 *   2. setsockopt() — enable SO_REUSEADDR
 *   3. bind()       — attach to the specified port
 *   4. listen()     — start accepting connections
 *   5. setNonBlocking() — make the listening socket non-blocking
 *   6. setupEpoll() — create the epoll instance
 *   7. epoll_ctl(ADD) — add the listening socket to epoll
 *
 * The listening socket is added with EPOLLIN only (no EPOLLET).
 * For the listening socket, level-triggered is simpler and safer.
 *
 * @note If any step fails, the function returns false. The caller should
 *       handle the failure gracefully.
 */
bool Server::initialize()
{
    // Step 1: Create the listening socket
    // AF_INET = IPv4, SOCK_STREAM = TCP
    server_fd_ = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd_ < 0)
    {
        std::cerr << "socket() failed\n";
        return false;
    }

    // Step 2: Allow address reuse
    // Without this, we'd get "Address already in use" if we restart quickly
    int opt = 1;
    if (setsockopt(server_fd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0)
    {
        std::cerr << "setsockopt(SO_REUSEADDR) failed\n";
        // Non-fatal: continue anyway
    }

    // Step 3: Bind to the port
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY; // Listen on all interfaces
    addr.sin_port = htons(port_);      // Convert to network byte order

    if (bind(server_fd_, (sockaddr *)&addr, sizeof(addr)) < 0)
    {
        std::cerr << "bind() failed on port " << port_ << "\n";
        return false;
    }

    // Step 4: Start listening
    // Backlog of 10: kernel queues up to 10 pending connections
    if (listen(server_fd_, 10) < 0)
    {
        std::cerr << "listen() failed\n";
        return false;
    }

    // Step 5: Make the listening socket non-blocking
    // This allows accept() to return immediately if no connections are pending
    if (!setNonBlocking(server_fd_))
    {
        std::cerr << "setNonBlocking(server) failed\n";
        return false;
    }

    // Step 6: Create the epoll instance
    if (!setupEpoll())
    {
        std::cerr << "epoll_create1() failed\n";
        return false;
    }

    // Step 7: Add the listening socket to epoll
    // We use level-triggered (no EPOLLET) for the listening socket.
    // This is simpler and safer: as long as we keep accepting, epoll will
    // keep notifying us.
    struct epoll_event ev{};
    ev.events = EPOLLIN; // Ready for reading (accepting connections)
    ev.data.fd = server_fd_;

    if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, server_fd_, &ev) < 0)
    {
        std::cerr << "epoll_ctl(ADD, server) failed\n";
        return false;
    }

    // All done: mark the server as running
    running_ = true;
    std::cout << "TurboLB listening on port " << port_ << "...\n";
    return true;
}

/**
 * @brief The main event loop.
 *
 * @details
 * This is the heart of the server. It repeatedly calls epoll_wait()
 * to get a list of file descriptors that are ready for I/O.
 *
 * The loop:
 *   1. epoll_wait() waits for events
 *   2. If interrupted by a signal (EINTR), retry
 *   3. For each event:
 *      a. If it's the listening socket: call handleNewConnection()
 *      b. If it's a client socket: call handleClientData()
 *      c. If it's an error or hang-up: removeClient()
 *
 * The loop continues until running_ is set to false (by stop()).
 *
 * @note epoll_wait() blocks indefinitely (-1 timeout) until an event occurs
 *       or a signal is received.
 *
 * @todo Add an eventfd or self-pipe trick to interrupt epoll_wait() for
 *       graceful shutdown.
 */
void Server::run()
{
    const int MAX_EVENTS = 64;
    struct epoll_event events[MAX_EVENTS];

    while (running_)
    {
        // Wait for events on any monitored file descriptor
        // timeout = -1 means block indefinitely
        int nfds = epoll_wait(epoll_fd_, events, MAX_EVENTS, -1);

        if (nfds < 0)
        {
            // If interrupted by a signal, retry
            if (errno == EINTR)
            {
                continue;
            }
            // Otherwise, log the error and break the loop
            std::cerr << "epoll_wait() failed: " << errno << "\n";
            break;
        }

        // Process all events
        for (int i = 0; i < nfds; ++i)
        {
            int fd = events[i].data.fd;

            // Check for errors or hang-up
            if (events[i].events & (EPOLLERR | EPOLLHUP))
            {
                std::cout << "Error/hangup on fd=" << fd << "\n";
                if (fd != server_fd_)
                {
                    removeClient(fd);
                }
                // For the listening socket, we could try to recover,
                // but for now we just skip it.
                continue;
            }

            // Handle the event based on which fd it is
            if (fd == server_fd_)
            {
                // Listening socket is ready: accept new connections
                handleNewConnection();
            }
            else if (events[i].events & EPOLLIN)
            {
                // Client socket is ready: read data
                handleClientData(fd);
            }
            // Note: we don't handle EPOLLOUT yet. We'll add that when we
            // need to handle partial writes for proxying.
        }
    }
}

// -----------------------------------------------------------------------------
// Shutdown Control
// -----------------------------------------------------------------------------

/**
 * @brief Signal the run() loop to exit gracefully.
 *
 * @details
 * This function sets running_ to false. The run() loop checks this flag
 * at the top of each iteration and exits when it's false.
 *
 * @warning This does NOT interrupt epoll_wait() if it's currently blocked.
 *          To interrupt it, we need to use the self-pipe trick or eventfd.
 *          That will be added in a future enhancement.
 */
void Server::stop()
{
    running_ = false;
}