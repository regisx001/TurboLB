# Engineering Journal — TurboLB

*This file is a living document. It captures the decisions, trade-offs, and reasoning behind TurboLB. It exists for two reasons: to help me internalize what I learn, and to provide a record for anyone who wants to understand why the code looks the way it does.*

---

## 2026-07-08 — Project Genesis

### Why This Project

This is not a production-ready load balancer — at least not yet. It is a learning project with a specific goal: to deeply understand how high-performance networking software works. NGINX, HAProxy, and Envoy are black boxes to most developers. This project is my attempt to open them up.

The journey starts with POSIX sockets and ends (hopefully) with an event-driven proxy that can handle thousands of concurrent connections. The backend implementation is C++ because it forces me to be intentional about memory, concurrency, and system calls.

### Core Learning Objectives

1.  **Socket Programming** – Understand the full lifecycle: `socket`, `bind`, `listen`, `accept`, `read`, `write`, `close`, and the error paths in between.

2.  **Event-Driven I/O** – Move from blocking `accept` to `epoll` with non-blocking sockets. Understand why NGINX uses an event loop instead of spawning a thread per connection.

3.  **Concurrency Models** – Single-threaded event loop first, then evaluate when to add thread pools. Learn the trade-offs between `epoll` + threads and `SO_REUSEPORT`.

4.  **HTTP Semantics** – Building a parser teaches me what a request actually looks like. Header folding, chunked encoding, keep-alive — these are real constraints.

5.  **Systems Thinking** – How do health checks interact with request routing? What happens when a backend dies mid-request? What does graceful shutdown mean at the socket level?

### Current State

Right now, TurboLB is a simple TCP listener. It accepts a connection, sends a hardcoded HTTP response, and closes. It is blocking and handles one client at a time.

This is intentionally the starting point. It establishes that the build system works, the port binds, and the network stack is configured correctly. From here, I will iteratively add complexity.

### Key Decisions (so far)

- **Language:** C++23 – balances modern features with low-level control.
- **Build System:** CMake + Ninja – CMake is standard; Ninja is fast.
- **I/O:** Linux `epoll` – the choice is already made. I am not writing a cross-platform library; I am understanding Linux networking.
- **Port:** 8080 – easy to test with `curl` and `telnet`.
- **Address Reuse:** `SO_REUSEADDR` enabled – makes development less painful when restarting.

### What I'm Reading Alongside This

- *The Linux Programming Interface* – Kerrisk
- `man` pages for `socket`, `epoll`, `sendfile`, etc.
- NGINX source code — reading selectively.
- Beej's Guide to Network Programming — for the fundamentals.

---

## Next Steps

1.  Convert `main.cpp` to use non-blocking sockets and `epoll`.
2.  Implement a simple proxy: client → TurboLB → backend.
3.  Add a backend pool with round-robin selection.

---

## Notes to Future Me

- If you are reading this and something is broken, start by checking the socket flags. Most bugs come from forgetting to set `O_NONBLOCK`.
- The `errno` is your friend. Print it.
- When in doubt, trace the system calls with `strace`.