# TurboLB — Development Roadmap

> **Current version:** Epoll-based TCP echo server with HTTP parser
> **Last updated:** 2026-07-12

---

## Table of Contents

- [Overview](#overview)
- [Stage 0 — Quick Wins](#stage-0--quick-wins)
- [Stage 1 — TCP Proxy](#stage-1--tcp-proxy)
- [Stage 2 — Backend Pool & Load Balancing](#stage-2--backend-pool--load-balancing)
- [Stage 3 — HTTP-Aware Routing](#stage-3--http-aware-routing)
- [Stage 4 — Health Checks](#stage-4--health-checks)
- [Stage 5 — Configuration File](#stage-5--configuration-file)
- [Stage 6 — Metrics & Observability](#stage-6--metrics--observability)
- [Stage 7 — Advanced Features](#stage-7--advanced-features)
- [Appendix: Architectural Patterns](#appendix-architectural-patterns)

---

## Overview

TurboLB currently has:
- `Server` class — epoll-based, non-blocking, event-driven TCP server
- `HttpParser` — incremental state-machine HTTP request parser
- `HttpRequest` — structured request data (method, URI, headers, body)
- Comprehensive test suite (~14 cases)
- Old-style blocking `main.cpp` (parses HTTP, sends static response)

### What needs to happen

The `Server` class currently **echoes** data back to clients. The roadmap is a series of stages that transform it from an echo server into a real HTTP load balancer. Each stage builds on the previous one.

---

## Stage 0 — Quick Wins

*Estimated effort: small · Priority: before Stage 1*

These are small improvements that make development smoother before tackling the proxy logic.

### 0.1 — Update `main.cpp` to use the `Server` class

`main.cpp` still implements its own blocking socket loop. It should use the `Server` class instead, so you can test the epoll event loop easily from the command line.

**Current (blocking, raw sockets):**
```cpp
int main() {
    int server_df = socket(AF_INET, SOCK_STREAM, 0);
    // ... bind, listen, accept, recv, close ...
    while (true) {
        int client_fd = accept(server_df, ...);
        // ...
    }
}
```

**Suggested:**
```cpp
int main() {
    Server server(8080);
    if (!server.initialize()) {
        return 1;
    }
    server.run();  // epoll-based, non-blocking
    return 0;
}
```

### 0.2 — Graceful shutdown (eventfd)

`Server::stop()` only sets a flag, but `epoll_wait()` blocks forever with `-1` timeout. Add an **eventfd** so `stop()` can interrupt the event loop immediately.

**Suggestion — add to Server class:**
```cpp
// In Server.hpp
#include <sys/eventfd.h>

// New member:
int stop_fd_;  // eventfd for signaling stop

// In initialize():
stop_fd_ = eventfd(0, EFD_NONBLOCK);
epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, stop_fd_, &ev);  // EPOLLIN

// In stop():
uint64_t val = 1;
write(stop_fd_, &val, sizeof(val));
running_ = false;

// In run() event loop:
if (fd == stop_fd_) {
    // stop signal received — exit the loop
    break;
}
```

This eliminates the hacky `wake_server()` pattern in tests.

### 0.3 — Move HTTP parsing into `Server::handleClientData()`

Right now, HTTP parsing only happens in the old `main.cpp`. The `Server` class echoes raw bytes. Move parsing into the epoll-based path so the event-driven server understands HTTP.

**Suggestion — accumulate data per connection:**
```cpp
// In Server.hpp
struct ConnectionContext {
    std::string buffer;      // accumulated raw bytes
    HttpParser parser;       // incremental parser
    HttpRequest request;     // parsed result
    bool headersParsed;      // flag to know when headers are done
};

// Map client_fd -> ConnectionContext
std::unordered_map<int, ConnectionContext> connections_;
```

Then in `handleClientData()`, feed bytes to the parser instead of echoing.

---

## Stage 1 — TCP Proxy

*Estimated effort: medium · Core feature — transforms TurboLB into a real proxy*

### Goal

Replace the echo logic with actual request forwarding: **client → TurboLB → backend**.

### What needs to change in `Server`

The current `handleClientData()` echoes:

```cpp
void Server::handleClientData(int client_fd) {
    char buffer[4096];
    ssize_t n = recv(client_fd, buffer, sizeof(buffer) - 1, 0);
    // ...
    send(client_fd, buffer, n, 0);  // echo!
}
```

It should instead:

1. Read data from the client
2. Forward it to the backend server
3. Read the backend's response
4. Send the response back to the client

### Backend Connection Management

When a client connects, TurboLB also needs to connect to the backend. This adds a new **state machine** per connection:

```
CLIENT_CONNECTED → wait for client data
CLIENT_DATA_RECEIVED → connect to backend (if not connected)
BACKEND_CONNECTING → wait for EPOLLOUT on backend fd
BACKEND_CONNECTED → forward data to backend
BACKEND_RESPONSE → wait for EPOLLIN on backend fd
RESPONSE_FORWARDED → check for keep-alive or close
```

### Key challenge — full-duplex proxying

A TCP proxy must handle **two-way** data flow simultaneously:
- Client → Backend (request)
- Backend → Client (response)

Both directions need non-blocking reads/writes managed by epoll.

**Suggested `ConnectionContext` structure:**
```cpp
struct ConnectionContext {
    int client_fd;
    int backend_fd;
    bool backend_connected;

    // Buffers for partial reads/writes
    std::string client_to_backend;  // bytes to forward to backend
    std::string backend_to_client;  // bytes to forward to client
    size_t client_send_offset;      // how far we've sent to backend
    size_t backend_send_offset;     // how far we've sent to client

    // States
    enum State {
        CONNECTING_TO_BACKEND,
        FORWARDING_REQUEST,
        FORWARDING_RESPONSE,
        CLOSING
    } state;
};
```

### Registering for EPOLLOUT

When `send()` can't write everything (partial write), register the socket for `EPOLLOUT` to get notified when it's ready to write more:

```cpp
struct epoll_event ev{};
ev.events = EPOLLIN | EPOLLOUT | EPOLLET;
ev.data.fd = backend_fd;
epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, backend_fd, &ev);
```

### Testing Stage 1

- Start a simple backend server (`nc -l 9001` or a Python one-liner)
- Send a request through TurboLB → verify it reaches the backend
- Send a response from the backend → verify it reaches the client
- Test with `curl -v --proxy http://localhost:8080 http://example.com`

---

## Stage 2 — Backend Pool & Load Balancing

*Estimated effort: medium · Adds horizontal scaling*

### Goal

Support multiple backend servers and distribute traffic among them using different algorithms.

### Backend Representation

```cpp
struct Backend {
    std::string host;
    int port;
    bool healthy;
    int active_connections;
};
```

### Backend Pool

```cpp
class BackendPool {
public:
    void addBackend(const std::string& host, int port);
    void removeBackend(const std::string& host, int port);

    const Backend* nextRoundRobin();
    const Backend* nextRandom();
    const Backend* nextLeastConnections();

    void markHealthy(const std::string& host, int port);
    void markUnhealthy(const std::string& host, int port);

private:
    std::vector<Backend> backends_;
    size_t rr_index_ = 0;
};
```

### Load Balancing Algorithms

| Algorithm | Description | Use Case |
|---|---|---|
| **Round-robin** | Cycle through backends in order | Equal-capacity servers |
| **Random** | Pick a backend at random | Stateless services |
| **Least connections** | Pick the backend with fewest active connections | Unequal workloads |

**Suggestion — Strategy pattern:**
```cpp
enum class LBAlgorithm {
    ROUND_ROBIN,
    RANDOM,
    LEAST_CONNECTIONS
};

// In Server:
LBAlgorithm algorithm_ = LBAlgorithm::ROUND_ROBIN;
BackendPool pool_;

// When picking a backend:
const Backend* backend = nullptr;
switch (algorithm_) {
    case LBAlgorithm::ROUND_ROBIN:
        backend = pool_.nextRoundRobin(); break;
    case LBAlgorithm::RANDOM:
        backend = pool_.nextRandom(); break;
    case LBAlgorithm::LEAST_CONNECTIONS:
        backend = pool_.nextLeastConnections(); break;
}
```

### Integration with ConnectionContext

When a new client connects, the `Server` picks a backend from the pool:

```cpp
void Server::handleNewConnection() {
    // ... accept client ...
    const Backend* backend = pool_.nextRoundRobin();
    int backend_fd = connectToBackend(backend->host, backend->port);

    ConnectionContext ctx;
    ctx.client_fd = client_fd;
    ctx.backend_fd = backend_fd;
    ctx.state = ConnectionContext::CONNECTING_TO_BACKEND;
    connections_[client_fd] = std::move(ctx);
}
```

### Testing Stage 2

- Start multiple backends on different ports
- Send several requests and verify they get distributed
- Add a test that removes a backend and verifies the pool skips it

---

## Stage 3 — HTTP-Aware Routing

*Estimated effort: medium · Enables path/header-based routing*

### Goal

Use the `HttpParser` to inspect requests and route them to different backends based on URI, method, or headers.

### Why This Matters

A load balancer that understands HTTP can:
- Route `/api/*` to an API server and `/*` to a web server (path-based routing)
- Route based on `Host` header (virtual hosting)
- Route based on HTTP method (GET vs POST)
- Add/remove headers (X-Forwarded-For, X-Real-IP)

### Routing Table

```cpp
struct Route {
    std::string path_prefix;   // e.g., "/api/"
    std::vector<Backend> backends;  // backends for this route
    LBAlgorithm algorithm;
};

class Router {
public:
    void addRoute(const Route& route);
    const Route* matchRoute(const std::string& uri) const;
    const Backend* selectBackend(const std::string& uri);
};
```

### Flow

```
Client request arrives
  → Parse HTTP request (use HttpParser)
  → Match URI against routing table
  → Select backend (round-robin/random/least-connections)
  → Forward the *entire raw HTTP request* to the backend
  → Read backend response
  → Optionally rewrite headers (X-Forwarded-For, etc.)
  → Forward response to client
```

### Suggestion — header injection

Add standard proxy headers before forwarding:

```cpp
// After parsing the request, inject headers:
request.headers["x-forwarded-for"] = client_ip;
request.headers["x-forwarded-proto"] = "http";
request.headers["x-forwarded-host"] = request.headers["host"];

// Reconstruct the raw request from the parsed data
// (since we already have the raw bytes in the buffer, this is simpler)
```

> **Note:** Since we already have the raw bytes in `ConnectionContext::buffer`, it's simpler to forward them as-is and *only* reconstruct if we need to modify headers. This avoids the complexity of serializing back to raw HTTP.

### Testing Stage 3

- Set up route: `/api/*` → backend A, `/*` → backend B
- `curl localhost:8080/api/users` → hits A
- `curl localhost:8080/index.html` → hits B
- Verify `X-Forwarded-For` header is present

---

## Stage 4 — Health Checks

*Estimated effort: medium · Adds reliability*

### Goal

Automatically detect dead backends and remove them from the pool. Re-add them when they recover.

### Types of Health Checks

| Type | How it works | Pros | Cons |
|---|---|---|---|
| **Passive** | Detect failures during normal proxy operation (connection refused, timeouts) | Zero overhead | Slow to detect |
| **Active** | Periodically ping backends with TCP connect or HTTP HEAD | Fast detection | Adds network traffic |

### Suggested approach — start with passive, add active later

**Passive (in `connectToBackend()`):**
```cpp
int Server::connectToBackend(const std::string& host, int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    // ... set non-blocking ...
    int ret = connect(fd, ...);
    if (ret < 0 && errno != EINPROGRESS) {
        // Connection failed immediately — mark backend unhealthy
        pool_.markUnhealthy(host, port);
        return -1;
    }
    return fd;
}
```

**Active (background thread or timer):**
```cpp
void Server::healthCheckLoop() {
    while (running_) {
        std::this_thread::sleep_for(std::chrono::seconds(5));
        for (auto& backend : pool_.getAll()) {
            int fd = socket(AF_INET, SOCK_STREAM, 0);
            // Try to connect with a short timeout
            if (connect(fd, ...) == 0) {
                pool_.markHealthy(backend.host, backend.port);
            }
            close(fd);
        }
    }
}
```

### Suggestion — integrate with the event loop

Instead of a separate thread, use a **timer fd** (`timerfd_create`) with epoll:

```cpp
#include <sys/timerfd.h>

// In initialize():
int timer_fd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK);
struct itimerspec spec{};
spec.it_interval.tv_sec = 5;  // repeat every 5s
spec.it_value.tv_sec = 5;     // start in 5s
timerfd_settime(timer_fd, 0, &spec, nullptr);

// Add to epoll:
epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, timer_fd, &ev);

// In run() event loop:
if (fd == timer_fd) {
    uint64_t expirations;
    read(timer_fd, &expirations, sizeof(expirations));
    checkBackendHealth();
}
```

### Testing Stage 4

- Add a backend, start TurboLB, kill the backend → verify it's marked unhealthy
- Restart the backend → verify it's re-added to the pool
- Verify requests don't go to unhealthy backends

---

## Stage 5 — Configuration File

*Estimated effort: small-medium · Makes TurboLB actually usable*

### Goal

Read backends, routes, and settings from a configuration file instead of hardcoding.

### Suggested format — YAML (easier to read/write than JSON)

```yaml
# turbo.yaml
port: 8080

algorithm: round-robin  # round-robin | random | least-connections

backends:
  - host: 127.0.0.1
    port: 9001
  - host: 127.0.0.1
    port: 9002
  - host: 127.0.0.1
    port: 9003

routes:
  - path: /api/
    backends:
      - host: 127.0.0.1
        port: 9001
    algorithm: round-robin
  - path: /
    backends:
      - host: 127.0.0.1
        port: 9002
      - host: 127.0.0.1
        port: 9003
    algorithm: least-connections

health_checks:
  enabled: true
  interval_sec: 5
  timeout_ms: 2000
  type: tcp  # tcp | http

timeouts:
  connect_ms: 5000
  read_ms: 30000
  write_ms: 30000
```

### Parsing approach

You have two choices:

| Approach | Pros | Cons |
|---|---|---|
| **Use a library** (yaml-cpp) | Robust, handles edge cases | External dependency |
| **Write a simple parser** | Zero dependencies | More code, less robust |

**Suggestion:** Start with **yaml-cpp** — it's a well-maintained, header-only-ish library. Add it to CMake:

```cmake
find_package(yaml-cpp REQUIRED)
target_link_libraries(TurboLB PRIVATE yaml-cpp)
```

### Configuration struct

```cpp
struct Config {
    int port = 8080;
    LBAlgorithm algorithm = LBAlgorithm::ROUND_ROBIN;
    std::vector<BackendConfig> backends;
    std::vector<RouteConfig> routes;
    HealthCheckConfig health_checks;
    TimeoutConfig timeouts;

    static Config loadFromFile(const std::string& path);
};
```

### Testing Stage 5

- Create a config file, load it, verify all fields are parsed correctly
- Test with missing fields (sensible defaults)
- Test with an invalid file → graceful error message

---

## Stage 6 — Metrics & Observability

*Estimated effort: medium · Makes TurboLB operable*

### Goal

Expose real-time metrics so you can monitor what TurboLB is doing.

### What to track

```cpp
struct Metrics {
    std::atomic<uint64_t> total_connections{0};
    std::atomic<uint64_t> active_connections{0};
    std::atomic<uint64_t> requests_proxied{0};
    std::atomic<uint64_t> requests_failed{0};
    std::atomic<uint64_t> bytes_received{0};
    std::atomic<uint64_t> bytes_sent{0};
    std::unordered_map<std::string, std::atomic<uint64_t>> status_codes;
    std::unordered_map<std::string, std::atomic<uint64_t>> backend_requests;
};
```

### Suggested — `/metrics` endpoint

If you've done Stage 3 (HTTP-aware routing), add a special internal route:

```cpp
if (req.uri == "/metrics") {
    // Return Prometheus-style metrics
    std::string response =
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: text/plain\r\n"
        "\r\n"
        "turbolb_connections_total " + std::to_string(metrics_.total_connections.load()) + "\n"
        "turbolb_connections_active " + std::to_string(metrics_.active_connections.load()) + "\n"
        "turbolb_requests_proxied " + std::to_string(metrics_.requests_proxied.load()) + "\n"
        "turbolb_bytes_received " + std::to_string(metrics_.bytes_received.load()) + "\n"
        "turbolb_bytes_sent " + std::to_string(metrics_.bytes_sent.load()) + "\n";
    // ... send response ...
}
```

### Prometheus format

Use the [Prometheus exposition format](https://prometheus.io/docs/instrumenting/exposition_formats/) so you can scrape metrics directly:

```
# HELP turbolb_connections_total Total number of connections handled
# TYPE turbolb_connections_total counter
turbolb_connections_total 1234
# HELP turbolb_connections_active Current number of active connections
# TYPE turbolb_connections_active gauge
turbolb_connections_active 7
```

### Logging

Add structured logging with request IDs:

```cpp
struct AccessLogEntry {
    std::string client_ip;
    std::string method;
    std::string uri;
    int status_code;
    size_t response_bytes;
    std::chrono::milliseconds duration;
};
```

Log to stdout in a structured format (JSON or Apache Common Log Format).

### Testing Stage 6

- Send requests, check that metrics increment
- `curl localhost:8080/metrics` → returns Prometheus-formatted data
- Verify active_connections goes up and down correctly

---

## Stage 7 — Advanced Features

*Estimated effort: large · Production polish*

### 7.1 — HTTP Keep-Alive

Currently, every connection is closed after one request-response cycle. With keep-alive, a single TCP connection can handle multiple requests.

**What changes:**
- Don't close the client connection after sending the response
- Re-arm epoll for `EPOLLIN` to read the next request
- Parse `Connection: keep-alive` header
- Add a timeout: if no new request arrives within N seconds, close the connection

```cpp
void Server::handleClientData(int client_fd) {
    // ... read, parse, forward, send response ...
    
    // Check if the client wants keep-alive
    auto& ctx = connections_[client_fd];
    if (ctx.request.headers["connection"] == "keep-alive") {
        // Don't close! Wait for the next request on the same socket
        ctx.parser.reset();
        ctx.buffer.clear();
    } else {
        removeClient(client_fd);
    }
}
```

### 7.2 — Rate Limiting

Protect backends from being overwhelmed by too many requests.

**Algorithm — Token Bucket (simple and effective):**
```cpp
struct TokenBucket {
    double tokens;
    double max_tokens;
    double refill_rate;  // tokens per second
    std::chrono::steady_clock::time_point last_refill;
    
    bool tryConsume(double count = 1.0) {
        refill();
        if (tokens >= count) {
            tokens -= count;
            return true;
        }
        return false;
    }
    
    void refill() {
        auto now = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration<double>(now - last_refill).count();
        tokens = std::min(max_tokens, tokens + elapsed * refill_rate);
        last_refill = now;
    }
};

// Per-client or global rate limiter
std::unordered_map<std::string, TokenBucket> rate_limiters_;
```

### 7.3 — TLS Termination

Offload TLS decryption so backends only handle plain HTTP.

**This adds significant complexity:**
- Need OpenSSL or similar library
- Certificate loading and management
- SNI (Server Name Indication) for multiple domains
- Session caching for TLS resumption

```cpp
#include <openssl/ssl.h>
#include <openssl/err.h>

struct ConnectionContext {
    // ... existing fields ...
    SSL* ssl = nullptr;       // non-null if TLS is enabled
    bool handshake_done = false;
};
```

**Suggestion:** Only attempt this after all other stages are complete. TLS is a significant undertaking and is the "capstone" feature.

### 7.4 — Connection Draining & Graceful Shutdown

When stopping the server, don't kill active connections — let them finish:

```cpp
void Server::stop() {
    running_ = false;
    // Don't exit immediately — let active connections drain
    drain_started_ = std::chrono::steady_clock::now();
}

void Server::run() {
    while (running_ || !connections_.empty()) {
        int timeout = running_ ? -1 : 1000;  // 1s poll during drain
        int nfds = epoll_wait(epoll_fd_, events, MAX_EVENTS, timeout);
        
        // Process events as usual...
        
        // Check drain timeout (e.g., 30s max)
        if (!running_ && connections_.empty()) break;
        if (!running_) {
            auto elapsed = std::chrono::steady_clock::now() - drain_started_;
            if (elapsed > std::chrono::seconds(30)) {
                // Force close remaining connections
                for (auto& [fd, ctx] : connections_) {
                    removeClient(fd);
                }
                break;
            }
        }
    }
}
```

---

## Appendix: Architectural Patterns

### State Machine per Connection

Every proxied connection goes through a state machine. This is the most important design pattern in TurboLB:

```
                    ┌──────────────┐
                    │  NEW_CLIENT  │
                    └──────┬───────┘
                           │ accept + pick backend
                           ▼
                ┌──────────────────────┐
                │ CONNECTING_TO_BACKEND│◄──── EPOLLOUT (non-blocking connect finishes)
                └──────────┬───────────┘
                           │ connected
                           ▼
                ┌──────────────────────┐
                │  FORWARDING_REQUEST  │◄──── EPOLLIN from client
                └──────────┬───────────┘
                           │ request fully sent
                           ▼
                ┌───────────────────────┐
                │ WAITING_FOR_RESPONSE  │◄──── EPOLLIN from backend
                └───────────┬───────────┘
                           │ response fully received
                           ▼
                ┌──────────────────────┐
                │  SENDING_RESPONSE    │◄──── EPOLLOUT to client
                └──────────┬───────────┘
                           │ done
                           ▼
                ┌──────────────────────┐
                │   KEEP_ALIVE_OR_DONE │
                └──────────────────────┘
```

### Buffer Management

For a high-performance proxy, avoid per-operation allocations:

- Pre-allocate **16KB or 64KB buffers** per connection (the kernel's socket buffer size)
- Use a **ring buffer** for streaming (or just a `std::string` for simplicity)
- Track read/write offsets to handle partial operations

```cpp
struct Buffer {
    std::vector<char> data;
    size_t read_offset = 0;   // how much has been consumed
    size_t write_offset = 0;  // how much has been written
    
    size_t available() const { return write_offset - read_offset; }
    size_t capacity() const { return data.size() - write_offset; }
    
    const char* readable() const { return data.data() + read_offset; }
    char* writable() { return data.data() + write_offset; }
};
```

### Error Handling Philosophy

| Error | What to do |
|---|---|
| `EAGAIN` / `EWOULDBLOCK` | Not an error — just means try again later |
| `EINTR` | Interrupted by signal — retry the system call |
| `ECONNREFUSED` | Backend is down — mark unhealthy, try another |
| `ECONNRESET` | Client/backend disconnected abruptly — clean up |
| `EPIPE` | Writing to a closed connection — remove from epoll |
| Buffer full | Log warning, close connection (or add backpressure) |

### Suggested Reading

- *The Linux Programming Interface* (Kerrisk) — Chapters 56–63 on sockets and epoll
- *NGINX source code* — `src/event/ngx_event_accept.c`, `ngx_epoll_module.c`
- *HAProxy architecture guide* — https://www.haproxy.org/download/1.8/doc/architecture.txt
- Beej's Guide to Network Programming — https://beej.us/guide/bgnet/

---

> **Pro tip:** Tackle the stages in order. Each stage produces a working, testable server that you can run with `curl`. This keeps momentum high and avoids the trap of building everything at once and having nothing work.
