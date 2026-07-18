# TurboLB

A lightweight, event-driven TCP load balancer — recreated in Java.

## Overview

TurboLB is a learning-oriented project designed to explore systems programming, networking, and concurrency. Originally built in C++ with epoll, it has been ported to Java to deepen understanding of the JVM's NIO capabilities. The architecture and design decisions mirror the original C++ implementation.

## Features

### Implemented
- [x] TCP listener on configurable host:port
- [x] Non-blocking I/O with Java NIO `Selector` (analogous to epoll)
- [x] Event-driven, single-threaded event loop
- [x] HTTP request parsing via state-machine parser
- [x] Externalized configuration via `.properties` files
- [x] Graceful shutdown with self-pipe / wakeup channel
- [x] Config path resolution (CLI flag → env var → default)
- [x] Comprehensive test suite (JUnit 5)

### Roadmap
- [ ] **Stage 1:** TCP Proxy – forward bytes between client and backend
- [ ] **Stage 2:** Multiple Backends – round-robin, random, least connections
- [ ] **Stage 3:** Health Checks – detect and remove dead backends
- [ ] **Stage 4:** Metrics – expose `/metrics` endpoint
- [ ] **Stage 5:** Advanced Features – keep-alive, rate limiting, TLS termination

## Project Structure

```
TurboLB/
├── pom.xml                         # Maven build configuration
├── Makefile                        # Convenience wrapper around Maven
├── README.md                       # This file
├── ENGINEERING.md                  # Engineering journal & design decisions
├── .turbolb/
│   └── config.properties           # Default configuration
├── src/
│   ├── main/java/io/regisx001/turbolb/
│   │   ├── App.java                # Entry point
│   │   ├── config/Config.java      # .properties file parser
│   │   ├── http/HttpParser.java    # State-machine HTTP request parser
│   │   └── server/Server.java      # NIO-based event-driven server
│   └── test/java/io/regisx001/turbolb/
│       ├── AppTest.java            # Smoke test
│       ├── config/ConfigTest.java  # Config unit tests
│       ├── http/HttpParserTest.java # HTTP parser unit tests
│       └── server/ServerTest.java  # Server unit & integration tests
└── target/                         # Build artifacts (generated)
```

## Requirements

- **JDK:** 21+ (uses Java NIO, records, sealed classes preview)
- **Build:** Maven 3.8+
- **OS:** Linux / macOS / Windows (any with JDK 21)

## Quick Start

### Build

```bash
make
# or: mvn compile
```

### Run

```bash
make run
# or: java -jar target/turbolb-1.0-SNAPSHOT.jar
```

With a custom config:

```bash
java -jar target/turbolb-1.0-SNAPSHOT.jar --config /path/to/config.properties
```

### Test

In another terminal:

```bash
curl -v http://localhost:8080/
# or
telnet localhost 8080
```

You should see server output:

```
╔══════════════════════════════════════════╗
║          TurboLB — Load Balancer        ║
╚══════════════════════════════════════════╝
Starting on 0.0.0.0:8080
```

And the client receives:

```
HTTP/1.1 200 OK
Content-Type: text/plain
Content-Length: 15
Connection: close

Request logged!
```

### Run Tests

```bash
make test
# or: mvn test
```

## Design Decisions

### I/O Model
- **Event-driven** using Java NIO `Selector` — scales to thousands of concurrent connections
- **Single-threaded** event loop for simplicity; matches original C++ epoll architecture
- **Level-triggered** by default (Java NIO semantics) — read-all pattern used in handlers

### C++ → Java Mapping

| C++ Concept | Java Equivalent |
|---|---|
| `epoll` + `epoll_wait()` | `java.nio.channels.Selector` + `select()` |
| `fcntl(_, O_NONBLOCK)` | `configureBlocking(false)` |
| `SO_REUSEADDR` | `StandardSocketOptions.SO_REUSEADDR` |
| Self-pipe / eventfd for wakeup | Loopback `SocketChannel` wakeup channel |
| RAII / destructors | `AutoCloseable` + `try-with-resources` |
| `errno` / `EAGAIN` | Non-blocking reads returning -1 / empty |
| C++23 `std::string` | `java.lang.String` / `StringBuilder` |
| CMake + Ninja | Maven |

### Configuration
- **`.properties` format** (key=value) — zero dependencies
- **Path resolution**: `--config <path>` → `TURBOLB_CONFIG` env var → default
- **Type coercion**: `getString()`, `getInt()`, `getBool()` with defaults

### Testing
- **JUnit 5** with parameterized tests, `@TempDir` for isolated files
- **Unique ports** per integration test via `AtomicInteger` counter
- **Real sockets, no mocking** — tests verify end-to-end behavior

### HTTP Parsing
- Hand-rolled state machine for HTTP/1.1 (no external dependencies)

### Configuration
- JSON format using `nlohmann-json` (optional)

## Development

### Build Commands

```bash
# Clean build
rm -rf build && mkdir build && cd build
cmake -G Ninja ..
ninja

# Run
./TurboLB

# Debug build
cmake -G Ninja -DCMAKE_BUILD_TYPE=Debug ..
ninja
```

### Useful Tools

- **Valgrind:** `valgrind --leak-check=full ./TurboLB`
- **strace:** `strace -f ./TurboLB`
- **tcpdump:** `sudo tcpdump -i lo port 8080`
- **perf:** `perf record ./TurboLB` then `perf report`

## License

MIT License — see LICENSE for details.

## Contributing

This is primarily a learning project, but issues and suggestions are welcome.