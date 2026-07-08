# TurboLB

A lightweight, high-performance TCP load balancer built from scratch in C++.

## Overview

TurboLB is a learning-oriented project designed to explore systems programming, networking, and concurrency in C++. It aims to be a simple, extensible load balancer that can proxy TCP traffic to multiple backends with various load-balancing algorithms.

## Features

### Implemented
- [x] TCP listener on configurable port
- [x] Connection handling with `send`/`close`
- [x] Non-blocking I/O with `epoll` *(planned)*

### Roadmap
- [ ] **Stage 1:** TCP Proxy – forward bytes between client and backend
- [ ] **Stage 2:** Multiple Backends – round-robin, random, least connections
- [ ] **Stage 3:** HTTP Awareness – parse HTTP requests for routing
- [ ] **Stage 4:** Health Checks – detect and remove dead backends
- [ ] **Stage 5:** Configuration – JSON/YAML file support
- [ ] **Stage 6:** Metrics – expose `/metrics` endpoint
- [ ] **Stage 7:** Advanced Features – keep-alive, rate limiting, TLS termination

## Project Structure

```
TurboLB/
├── CMakeLists.txt          # Build configuration
├── README.md               # This file
├── src/
│   └── main.cpp            # Current entry point
└── build/                  # Build artifacts (generated)
```

## Requirements

- **Compiler:** GCC 14+ or Clang 18+ (C++23 support)
- **Build:** CMake 3.20+, Ninja (or Make)
- **OS:** Linux (epoll required; POSIX sockets)

## Quick Start

### Clone & Build

```bash
git clone https://github.com/yourusername/TurboLB.git
cd TurboLB
mkdir -p build && cd build
cmake -G Ninja ..
ninja
```

### Run

```bash
./TurboLB
```

### Test

In another terminal:

```bash
curl -v http://localhost:8080/
# or
telnet localhost 8080
# or
nc -v localhost 8080
```

You should see:

```
TurboLB listening on port 8080...
New connection accepted
```

And the client receives:

```
HTTP/1.1 200 OK
Content-Length: 13

Hello TurboLB
```

## Design Decisions

### I/O Model
- **Event-driven** using `epoll` (Linux) — scales to thousands of concurrent connections
- **Single-threaded** event loop for simplicity; worker threads can be added later

### Concurrency
- Start single-threaded; avoid thread-per-connection overhead
- Consider `SO_REUSEPORT` + multi-process later, similar to NGINX

### Buffer Management
- Per-connection ring buffers to minimize dynamic allocations

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