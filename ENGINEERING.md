# Engineering Journal — TurboLB

*This file is a living document. It captures the decisions, trade-offs, and reasoning behind TurboLB. It exists for two reasons: to help me internalize what I learn, and to provide a record for anyone who wants to understand why the code looks the way it does.*



## Table of Contents

1. [2026-07-08 — Project Genesis](#2026-07-08--project-genesis)
2. [2026-07-09 — Non-Blocking I/O with epoll](#2026-07-09--non-blocking-io-with-epoll)
3. [2026-07-09 — Project Structure](#2026-07-09--project-structure)

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




## 2026-07-09 — Non-Blocking I/O with epoll

### What Changed

The server has been refactored from a blocking, single-client-at-a-time listener into an event-driven, non-blocking server that can handle thousands of simultaneous connections.

### The Problem That Drove This Change

The previous implementation used blocking system calls:

- `accept()` blocked until a client connected
- `recv()` / `send()` blocked until data was available or buffer space was free
- Slow clients blocked all other clients (head-of-line blocking)

In real-world load balancers like NGINX and HAProxy, this approach would not scale beyond a few dozen connections.

### The Solution

The server now uses:

1. **Non-blocking sockets** — system calls return immediately with an error (usually `EAGAIN`/`EWOULDBLOCK`) if they can't complete immediately.
2. **epoll** — the kernel notifies the server when sockets are ready for I/O.
3. **Edge-triggered events** (`EPOLLET`) for client sockets — guarantees we receive events only when the state changes, reducing the number of system calls.

### Implementation Details

The `Server` class now handles:

- **Listening socket**: registered with `EPOLLIN` (level-triggered). When `epoll_wait()` returns with this event, we call `handleNewConnection()` to accept all pending connections.
- **Client sockets**: registered with `EPOLLIN | EPOLLET` (edge-triggered). When `epoll_wait()` returns with this event, we call `handleClientData()` to read all available data.

### Key Design Decisions

| Decision | Why |
|---|---|
| **Single epoll instance** | Simpler than multiple event loops; can scale to ~10k connections with this approach. |
| **Edge-triggered on clients** | Fewer `epoll_wait()` wakeups; forces us to read all data each time, which is more efficient. |
| **Level-triggered on listening socket** | Safer for accepting connections; we won't miss any if we don't handle them all in one go. |
| **Single-threaded event loop** | Simpler to reason about; no locks; matches the architecture of NGINX. |
| **RAII for all file descriptors** | Destructor cleans up the listening socket and the epoll instance automatically. |

### What I Learned

1. **Non-blocking I/O changes the mental model** — we no longer write linear code (connect → read → write → close). Instead, we respond to events, and state machines become essential.

2. **epoll is powerful but requires discipline** — you must handle `EAGAIN` correctly. Forgetting to loop on `accept()` or `recv()` with edge-triggered events leads to missed events and lost data.

3. **System calls are expensive** — a well-designed event loop reduces the number of `epoll_wait()` calls by batching events.

4. **The self-pipe trick is needed for graceful shutdown** — `epoll_wait()` blocks indefinitely; a simple `running_` flag won't interrupt it. I'll need to add an eventfd or self-pipe to interrupt the loop when `stop()` is called.

### What's Next

The server can now handle multiple clients concurrently. The next step is to:

1. **Implement the proxy logic** — forward client requests to a backend server.
2. **Add a backend pool** — round-robin selection among multiple backends.
3. **Support HTTP parsing** — so we can route based on the request path or headers.

### Code References

The key pieces are:

- `Server::setNonBlocking()` — makes sockets non-blocking using `fcntl()`.
- `Server::setupEpoll()` — creates the epoll instance.
- `Server::handleNewConnection()` — accepts all pending connections with `accept()`.
- `Server::handleClientData()` — reads all available data with `recv()`.
- `Server::run()` — the main event loop that calls `epoll_wait()`.

### Notes for Future Me

- Always check `errno` after system calls. `EAGAIN` and `EWOULDBLOCK` are not errors — they mean "try again later".
- With edge-triggered events, you must read until you get `EAGAIN`. Otherwise, you'll miss data.
- The current `stop()` implementation doesn't interrupt `epoll_wait()`. This means the server won't exit until a connection arrives or a signal is received. Add an eventfd or use the self-pipe trick in the next iteration.
- Keep the event loop single-threaded for now; adding threads adds complexity with locking and shared state.

---

## 2026-07-09 — Project Structure

The codebase has been refactored into a cleaner structure:

---

## 2026-07-10 — Testing Infrastructure & Makefile

### What Changed

Two non-code additions that improve the development experience:

1. **A `Makefile` wrapper** around CMake — so common tasks are always one word away.
2. **A comprehensive test suite** — 14 test cases (33 assertions) covering unit and integration scenarios.

### The Makefile

Running `cmake --build build` or `ctest` is easy enough, but I want to lower the friction for future sessions. The new top-level `Makefile` provides:

| Command | Effect |
|---|---|
| `make` / `make build` | Configure + build |
| `make test` | Build + run all tests via `ctest` |
| `make run` | Build + launch the server |
| `make clean` | Remove the `build/` directory |
| `make rebuild` | Clean + full rebuild |
| `make verbose` | Build with `VERBOSE=1` |

It auto-detects the number of CPU cores for parallel builds. Nothing fancy — just a thin delegating layer that means I never have to remember CMake flags.

### The Problem: A Bug in the First Test

The original `test_server.cpp` had one test case:

```cpp
REQUIRE_NOTHROW(Server server(8080));
```

This looks reasonable but doesn't compile. Catch2's `REQUIRE_NOTHROW` macro expects a single **expression**, but `Server server(8080)` is a **declaration** (it declares a variable). The compiler can't parse this inside a macro and reports:

> type name is not allowed

The fix was trivial — use brace-initialization to create a temporary expression:

```cpp
REQUIRE_NOTHROW(Server{8080});
```

### Test Design

I wrote 14 test cases organized into two groups:

#### Unit Tests (`[server][unit]` — 9 tests)

These test the Server class in isolation, without requiring a running event loop:

- **Construction / destruction**: the constructor doesn't throw; the destructor is safe even without calling `initialize()` (all fds are `-1`); multiple servers can be created and destroyed in a loop.
- **Initialization**: succeeds on a free port; fails when the port is already taken (verified with two servers on the same port).
- **Port reuse** (SO_REUSEADDR): after a server is destroyed, the same port can be rebound immediately — tested both single-cycle and in a 5-iteration loop.
- **`stop()` safety**: calling `stop()` on an uninitialized server or before `run()` is a safe no-op (just sets a boolean flag).

#### Integration Tests (`[integration]` — 5 tests)

These spin up a server in a background thread and connect with real TCP sockets:

- **Accept a connection**: connect and disconnect cleanly.
- **Echo data**: send "Hello, TurboLB!" and verify the server echoes it back (the current implementation is an echo server).
- **Multiple concurrent clients**: connect 5 clients at once — verifies the epoll event loop handles many connections.
- **Connect → send → close cycle**: repeat the cycle 3 times — catches stale state issues.
- **Stop + restart on same port**: destroy a server, create a new one on the same port, connect again — validates full lifecycle.

### Key Design Decisions for Tests

| Decision | Why |
|---|---|
| **Unique port per test** (`atomic<int>`) | Tests can run in any order, in parallel, or in a single binary. No port collisions. |
| **`wake_server()` pattern** | `epoll_wait()` blocks with `-1` timeout; `stop()` only sets a flag. To make the event loop exit, we connect and send a byte to wake it up so it can check `running_`. |
| **Real sockets, not mocks** | The Server class wraps POSIX sockets; mocking them would defeat the purpose. Real connections validate the full stack. |
| **No external dependencies** | The test binary links nothing beyond Catch2 and libc. Socket helpers are inline static functions. |

### What I Learned

1. **Catch2 macros and declarations** — `REQUIRE_NOTHROW` and similar macros use the preprocessor and can't handle C++ declarations. Always use an expression (temporary, lambda, etc.).

2. **Testing epoll-based servers requires ceremony** — because the event loop blocks, every integration test needs: (a) a dedicated thread, (b) a small sleep for the thread to reach `epoll_wait`, (c) the actual test logic, and (d) a wake-up mechanism for clean shutdown.

3. **`SO_REUSEADDR` is necessary but not sufficient** — it prevents "Address already in use" on restart, but `TIME_WAIT` on the client side can still cause issues in test scenarios. Using distinct ports per test (base 25000 + increment) sidesteps this entirely.

4. **A good Makefile is a force multiplier** — it's trivial code but removes the "how do I build/run/test this again?" pause every time I come back to the project.

### What's Next

The test suite gives confidence to make changes. Next steps:

1. Add an `eventfd` or self-pipe to the `Server` class so `stop()` can interrupt `epoll_wait()` immediately — no more `wake_server()` hack.
2. Replace the echo logic with a real proxy: client → TurboLB → backend.
3. Add a backend pool with round-robin selection.
