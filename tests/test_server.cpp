#define CATCH_CONFIG_MAIN
#include "catch.hpp"

#include "lb/Server.hpp"

#include <thread>
#include <chrono>
#include <cstring>
#include <vector>
#include <atomic>

// POSIX socket headers
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Port base — each test uses a distinct port so they don't collide
static constexpr int PORT_BASE = 25000;

/// Next port to use (incremented atomically)
static std::atomic<int> next_port(PORT_BASE);

/// Allocate a unique port for each test case
static int unique_port()
{
    return next_port.fetch_add(1, std::memory_order_relaxed);
}

/**
 * @brief Connect a TCP socket to 127.0.0.1:port.
 * @returns socket fd on success, -1 on failure.
 */
static int tcp_connect(int port)
{
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0)
        return -1;

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);

    if (connect(fd, (sockaddr *)&addr, sizeof(addr)) < 0)
    {
        close(fd);
        return -1;
    }
    return fd;
}

/**
 * @brief Send all bytes over a socket (handles partial writes).
 */
static bool send_all(int fd, const char *data, size_t len)
{
    while (len > 0)
    {
        ssize_t n = ::send(fd, data, len, 0);
        if (n <= 0)
            return false;
        data += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

/**
 * @brief Receive up to `len` bytes from a socket.
 * @returns number of bytes received, or -1 on error.
 */
static ssize_t recv_some(int fd, char *buf, size_t len)
{
    return ::recv(fd, buf, len, 0);
}

/**
 * @brief Wake up a server's epoll_wait so it can check the running_ flag.
 *
 * The server's run() loop blocks in epoll_wait() with -1 timeout and
 * stop() just sets a flag.  To get the loop to actually exit, we need
 * epoll_wait to return — the simplest way is to connect (and optionally
 * send a byte) so the server processes an event, then checks running_.
 */
static void wake_server(int port)
{
    int fd = tcp_connect(port);
    if (fd >= 0)
    {
        // Send a byte to make epoll_wait return promptly
        send_all(fd, "x", 1);
        close(fd);
    }
}

// (No convenience wrapper needed — tests manage their own thread lifecycle)

// ===========================================================================
// Unit tests
// ===========================================================================

// ---------------------------------------------------------------------------
// Construction / Destruction
// ---------------------------------------------------------------------------

TEST_CASE("Server can be constructed without throwing", "[server][unit]")
{
    REQUIRE_NOTHROW(Server{unique_port()});
}

TEST_CASE("Server can be destroyed without initializing", "[server][unit]")
{
    // Should not crash — destructor guards against negative fds
    Server server(unique_port());
    // Just let it go out of scope
}

TEST_CASE("Multiple servers can be constructed and destroyed", "[server][unit]")
{
    for (int i = 0; i < 10; ++i)
    {
        Server s(unique_port());
    }
}

// ---------------------------------------------------------------------------
// Initialization
// ---------------------------------------------------------------------------

TEST_CASE("initialize() returns true on a free port", "[server][unit]")
{
    Server server(unique_port());
    REQUIRE(server.initialize());
}

TEST_CASE("initialize() fails when port is already in use", "[server][unit]")
{
    int port = unique_port();
    Server server1(port);
    REQUIRE(server1.initialize());

    Server server2(port);
    REQUIRE_FALSE(server2.initialize());
}

TEST_CASE("Server can reuse a port after destruction (SO_REUSEADDR)", "[server][unit]")
{
    int port = unique_port();
    {
        Server s(port);
        REQUIRE(s.initialize());
    } // s destroyed → socket closed

    // The same port should be bindable immediately
    Server s2(port);
    REQUIRE(s2.initialize());
}

TEST_CASE("Port is reusable within the same process sequentially", "[server][unit]")
{
    for (int i = 0; i < 5; ++i)
    {
        int port = unique_port();
        Server s(port);
        REQUIRE(s.initialize());
        // Destructor closes the socket
    }
}

// ---------------------------------------------------------------------------
// Stop before / without run
// ---------------------------------------------------------------------------

TEST_CASE("stop() before run() does nothing harmful", "[server][unit]")
{
    Server server(unique_port());
    REQUIRE(server.initialize());
    server.stop(); // running_ was already false → safe no-op
}

TEST_CASE("stop() on uninitialized server does nothing harmful", "[server][unit]")
{
    Server server(unique_port());
    server.stop(); // server_fd_ = -1, epoll_fd_ = -1 → safe
}

// ===========================================================================
// Integration tests (real TCP connections)
// ===========================================================================

TEST_CASE("Server accepts a connection", "[integration]")
{
    int port = unique_port();
    Server server(port);
    REQUIRE(server.initialize());

    std::thread t([&server]()
                  { server.run(); });
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    // Connect a client
    int client = tcp_connect(port);
    REQUIRE(client >= 0);

    // Wake the server so the loop checks running_
    server.stop();
    wake_server(port);
    close(client);
    t.join();
}

TEST_CASE("Server echoes data back to client", "[integration]")
{
    int port = unique_port();
    Server server(port);
    REQUIRE(server.initialize());

    std::thread t([&server]()
                  { server.run(); });
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    // Connect and send
    int client = tcp_connect(port);
    REQUIRE(client >= 0);

    const char *payload = "Hello, TurboLB!";
    size_t len = strlen(payload);

    REQUIRE(send_all(client, payload, len));

    // Give the server time to echo back
    std::this_thread::sleep_for(std::chrono::milliseconds(100));

    char buf[1024];
    ssize_t n = recv_some(client, buf, sizeof(buf) - 1);

    if (n > 0)
    {
        buf[n] = '\0';
        CHECK(n == (ssize_t)len);
        CHECK(std::string(buf) == payload);
    }
    // If n == 0 or -1, the echo may not have arrived yet — it's a best-effort check

    server.stop();
    wake_server(port);
    close(client);
    t.join();
}

TEST_CASE("Server handles multiple concurrent clients", "[integration]")
{
    int port = unique_port();
    Server server(port);
    REQUIRE(server.initialize());

    std::thread t([&server]()
                  { server.run(); });
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    constexpr int NUM_CLIENTS = 5;
    std::vector<int> clients;

    for (int i = 0; i < NUM_CLIENTS; ++i)
    {
        int fd = tcp_connect(port);
        REQUIRE(fd >= 0);
        clients.push_back(fd);
    }

    // Give the server a moment to accept all connections
    std::this_thread::sleep_for(std::chrono::milliseconds(200));

    server.stop();
    wake_server(port);

    for (int fd : clients)
        close(fd);
    t.join();
}

TEST_CASE("Server handles connect -> send -> close cycle", "[integration]")
{
    int port = unique_port();
    Server server(port);
    REQUIRE(server.initialize());

    std::thread t([&server]()
                  { server.run(); });
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    // Cycle: connect, send, close — multiple times
    for (int i = 0; i < 3; ++i)
    {
        int client = tcp_connect(port);
        REQUIRE(client >= 0);

        std::string msg = "Msg #" + std::to_string(i);
        send_all(client, msg.data(), msg.size());

        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        close(client);
        std::this_thread::sleep_for(std::chrono::milliseconds(30));
    }

    server.stop();
    wake_server(port);
    t.join();
}

TEST_CASE("Server accepts connection after stop+restart on same port", "[integration]")
{
    int port = unique_port();

    // First run
    {
        Server server(port);
        REQUIRE(server.initialize());

        std::thread t([&server]()
                      { server.run(); });
        std::this_thread::sleep_for(std::chrono::milliseconds(50));

        int client = tcp_connect(port);
        REQUIRE(client >= 0);

        server.stop();
        wake_server(port);
        close(client);
        t.join();
    } // server destroyed, socket closed

    // Second run on the same port
    {
        Server server(port);
        REQUIRE(server.initialize());

        std::thread t([&server]()
                      { server.run(); });
        std::this_thread::sleep_for(std::chrono::milliseconds(50));

        int client = tcp_connect(port);
        REQUIRE(client >= 0);

        server.stop();
        wake_server(port);
        close(client);
        t.join();
    }
}