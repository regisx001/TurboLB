#include "Server.hpp"

#include <iostream>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <cstring>

// -----------------------------------------------------------------------------
// Constructor / Destructor
// -----------------------------------------------------------------------------

Server::Server(int port) : port_(port), server_fd_(-1), running_(false)
{
    // server_fd_ is set to -1 to indicate "not initialized".
    // running_ starts false; initialize() will set it to true.
}

Server::~Server()
{
    // RAII cleanup: close the socket if it was ever opened.
    // It's safe to close even if server_fd_ is -1.
    if (server_fd_ >= 0)
    {
        close(server_fd_);
    }
}

// -----------------------------------------------------------------------------
// Initialization
// -----------------------------------------------------------------------------

bool Server::initialize()
{
    // Step 1: Create the listening socket
    // AF_INET     = IPv4
    // SOCK_STREAM = TCP (reliable, ordered, connection-oriented)
    // 0           = let the OS pick the protocol (TCP for SOCK_STREAM)
    server_fd_ = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd_ < 0)
    {
        std::cerr << "socket() failed\n";
        return false;
    }

    // Step 2: Allow address reuse
    // Without this, if the program crashes and restarts quickly,
    // bind() will fail with "Address already in use" because the OS
    // keeps the port in TIME_WAIT for ~60 seconds.
    int opt = 1;
    if (setsockopt(server_fd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0)
    {
        std::cerr << "setsockopt(SO_REUSEADDR) failed\n";
        // Non-fatal; continue anyway.
    }

    // Step 3: Bind to the port
    // sockaddr_in is the IPv4 address structure.
    // We zero-initialize it with {} to avoid uninitialized padding bytes.
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY; // Listen on all network interfaces
    addr.sin_port = htons(port_);      // Convert to network byte order

    if (bind(server_fd_, (sockaddr *)&addr, sizeof(addr)) < 0)
    {
        std::cerr << "bind() failed\n";
        return false;
    }

    // Step 4: Start listening
    // The backlog of 10 means the kernel will queue up to 10 pending connections
    // while our application is busy. If the queue fills, new connections are refused.
    if (listen(server_fd_, 10) < 0)
    {
        std::cerr << "listen() failed\n";
        return false;
    }

    running_ = true;
    std::cout << "TurboLB listening on port " << port_ << "...\n";
    return true;
}

// -----------------------------------------------------------------------------
// Accept Loop
// -----------------------------------------------------------------------------

void Server::run()
{
    while (running_)
    {
        // Step 1: Block until a client connects
        // accept() returns a NEW socket for this specific client.
        // The original server_fd_ remains open for future connections.
        sockaddr_in client_addr{};
        socklen_t client_len = sizeof(client_addr);

        int client_fd = accept(server_fd_, (sockaddr *)&client_addr, &client_len);
        if (client_fd < 0)
        {
            // This can happen if a signal interrupted accept().
            // For now, just log and retry.
            std::cerr << "accept() failed\n";
            continue;
        }

        // We successfully accepted a client.
        // client_fd is a connected socket — we can read from and write to it.
        std::cout << "New connection accepted\n";

        // Step 2: Send a hardcoded HTTP response
        // This is a placeholder. In future stages, we will:
        //   - Read the client's request
        //   - Connect to a backend server
        //   - Forward the request and send the backend's response back
        const char *response =
            "HTTP/1.1 200 OK\r\n"
            "Content-Length: 13\r\n"
            "\r\n"
            "Hello TurboLB\n";

        ssize_t bytes_sent = send(client_fd, response, strlen(response), 0);
        if (bytes_sent < 0)
        {
            std::cerr << "send() failed\n";
            // Continue anyway — we'll close the connection below.
        }

        // Step 3: Close the client connection
        // In a production load balancer, we'd keep the connection open
        // for keep-alive, or at least drain the buffer before closing.
        close(client_fd);
    }
}

// -----------------------------------------------------------------------------
// Shutdown Control
// -----------------------------------------------------------------------------

void Server::stop()
{
    // This sets a flag that the run() loop checks at the top of each iteration.
    // However, if the loop is currently blocked inside accept(), it won't
    // notice the flag until a new connection arrives or a signal interrupts.
    //
    // Future improvement: use epoll to monitor both the listening socket
    // AND a control eventfd, so we can break out of epoll_wait() immediately.
    running_ = false;
}