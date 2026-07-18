# Engineering Journal — TurboLB

*This file is a living document. It captures the decisions, trade-offs, and reasoning behind TurboLB. It exists for two reasons: to help me internalize what I learn, and to provide a record for anyone who wants to understand why the code looks the way it does.*



## Table of Contents

1. [2026-07-08 — Project Genesis](#2026-07-08--project-genesis)
2. [2026-07-09 — Non-Blocking I/O with epoll](#2026-07-09--non-blocking-io-with-epoll)
3. [2026-07-09 — Project Structure](#2026-07-09--project-structure)
4. [2026-07-10 — Testing Infrastructure & Makefile](#2026-07-10--testing-infrastructure--makefile)
5. [2026-07-12 — HTTP Request Parsing & Debug Task Fix](#2026-07-12--http-request-parsing--debug-task-fix)
6. [2026-07-18 — Configuration System (.properties)](#2026-07-18--configuration-system-properties)
7. [2026-07-18 — Java Port (Full Recreation)](#2026-07-18--java-port-full-recreation)

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

---

## 2026-07-12 — HTTP Request Parsing & Debug Task Fix

### What Changed

Two things happened today:

1. **`main.cpp`** was rewritten to parse incoming HTTP requests and print them to the console, instead of forwarding them to a backend server.
2. **`tasks.json`** was fixed so the default "C/C++: g++ build active file" task actually compiles the project correctly.

### The HTTP Parsing Problem

The original `main.cpp` connected to a backend socket on port 9001, forwarded raw HTTP bytes to it, read the response, and sent it back to the client. This was a forward proxy in its simplest form.

I wanted to understand what the server was actually receiving — so I swapped the forwarding logic for the existing `HttpParser` class (which was already written but never used from `main.cpp`).

### The Implementation

The new flow in `main.cpp`:

1. Accept a TCP connection (unchanged).
2. Receive raw bytes into a buffer (unchanged).
3. Feed the buffer into `HttpParser::consume()`.
4. If parsing completes (or errors), print the structured request:
   - **Method** (GET, POST, etc.)
   - **URI** (the request path)
   - **Version** (HTTP/1.1)
   - **Headers** (all key-value pairs, keys lowercased)
   - **Body** (if present, e.g. for POST/PUT)
5. Send a minimal `200 OK` response so the client doesn't hang.
6. Close the connection.

The `HttpParser` class (in `src/http/HttpParser.cpp`) is an incremental, state-machine-based parser that handles:
- **REQUEST_LINE state** — parses `METHOD URI VERSION`
- **HEADERS state** — parses header lines until blank line, tracks `content-length`
- **BODY state** — reads exactly `content-length` bytes
- **COMPLETE state** — signals the request is fully parsed
- **ERROR state** — malformed request

### Why main.cpp Instead of the Server Class

The `Server` class (`src/server/Server.cpp`) is the epoll-based event-driven server. It currently echoes data back. The HTTP parsing was added to `main.cpp` to keep things simple and observable — no event loop complexity, just blocking accept + parse + print.

Eventually, the HTTP parsing should move into the `Server` class's `handleClientData()` method, so the event-driven server can route requests based on URI or headers.

### The Build Task Bug

When I added `#include "lb/HttpParser.hpp"` to `main.cpp`, the default build task (**C/C++: g++ build active file**) failed with:

```
fatal error: lb/HttpParser.hpp: No such file or directory
```

The task was compiling just `main.cpp` with no `-I` flags and none of the other `.cpp` files. The CMake task worked fine because `CMakeLists.txt` already had:

```cmake
target_include_directories(TurboLB PRIVATE ${PROJECT_SOURCE_DIR}/include)
```

### The Fix

Updated `.vscode/tasks.json` to:

1. Add `-I ${workspaceFolder}/include` so the parser header can be found.
2. Include all three source files (`main.cpp`, `HttpParser.cpp`, `Server.cpp`) so the linker resolves all symbols.
3. Output the binary to `build/TurboLB` to be consistent with the CMake output path.

```json
{
    "type": "cppbuild",
    "label": "C/C++: g++ build active file",
    "command": "/usr/bin/g++",
    "args": [
        "-fdiagnostics-color=always",
        "-g",
        "-I",
        "${workspaceFolder}/include",
        "${workspaceFolder}/src/main.cpp",
        "${workspaceFolder}/src/http/HttpParser.cpp",
        "${workspaceFolder}/src/server/Server.cpp",
        "-o",
        "${workspaceFolder}/build/TurboLB"
    ],
    "options": {
        "cwd": "${workspaceFolder}"
    }
}
```

### Verification

Tested with curl:

```
$ curl -v http://localhost:8080/
*   Trying 127.0.0.1:8080...
* Connected to localhost (127.0.0.1) port 8080
> GET / HTTP/1.1
> Host: localhost:8080
> User-Agent: curl/8.21.0
> Accept: */*
>
* Request completely sent off
< HTTP/1.1 200 OK
< Content-Type: text/plain
< Content-Length: 15
< Connection: close
<
Request logged!
* Closing connection
```

Server console output:

```
===== Client connected: 127.0.0.1:45906 =====
--- Parsed HTTP Request ---
Method:  GET
URI:     /
Version: HTTP/1.1
Headers:
  accept: */*
  user-agent: curl/8.21.0
  host: localhost:8080
--------------------------
Client disconnected
```

### Key Decisions

| Decision | Why |
|---|---|
| **Parse in main.cpp, not Server class** | Keep it simple and observable; Server is still an echo server. |
| **Single recv() call** | HTTP requests from curl usually fit in one TCP segment. A real parser would need to handle partial reads, but for development this is fine. |
| **Connection: close response** | Simplest possible response — no keep-alive state to manage. |
| **Fixed g++ task instead of removing it** | The CMake task was correct but the default build keyboard shortcut (Ctrl+Shift+B) ran the broken g++ task. Now both work. |

### What I Learned

1. **The HttpParser I already wrote works** — it was sitting in `src/http/` untested from main. Using it confirmed the state machine handles real curl requests.
2. **Default build tasks matter** — VS Code's "C/C++: g++ build active file" is fine for single-file programs, but multi-file projects need explicit source lists and include paths. CMake is the canonical build system, but the editor task also needs to be correct for quick iterations.
3. **Parsing HTTP by hand teaches you the format** — seeing the structured output confirms how headers are delimited, how `content-length` controls body parsing, and what a blank-line-terminated header section looks like on the wire.

### What's Next

1. Move HTTP parsing into the `Server` class so the event-driven server can inspect and route requests.
2. Reintroduce the backend connection logic — but this time route based on the parsed URI, not raw byte forwarding.
3. Add a round-robin backend pool.

---

## 2026-07-18 — Configuration System (.properties)

### What Changed

Added a `Config` class that reads a `.properties` file (key=value format) to externalize all tunables. This was done *before* implementing the load-balancing logic so that backends, ports, and algorithms are data-driven from the start.

### New Files

| File | Role |
|---|---|
| `include/lb/Config.hpp` | `Config` class declaration |
| `src/config/Config.cpp` | `.properties` parser + `Config::load()` factory |
| `.turbolb/config.properties` | Default configuration file |

### The Config Class

The API is simple — key/value lookups with type coercion:

```cpp
Config config("path/to/config.properties");

auto host = config.getString("server.host");       // throws if missing
auto port = config.getInt("server.port", 8080);    // default if missing
auto debug = config.getBool("server.debug");       // "true"/"false"/"1"/"0"
auto workers = config.getInt("thread.pool.size");  // via std::stoi
```

The `.properties` format supports:
- `key=value` pairs
- `#` and `;` comment lines
- Whitespace trimming around keys and values
- Case-insensitive boolean parsing (`true`, `false`, `yes`, `no`, `1`, `0`)

### Config Path Resolution

`Config::load()` is a static factory that resolves the config file with this precedence:

1. `--config <path>` command-line flag
2. `TURBOLB_CONFIG` environment variable
3. `PROJECT_ROOT/.turbolb/config.properties` (development default)

This means during development, the config is always found regardless of the working directory (because `PROJECT_ROOT` is baked in by the build system). In production, you'd override with `--config /etc/turbolb/config.properties` or `TURBOLB_CONFIG=/etc/turbolb/config.properties`.

### The PROJECT_ROOT Define

The build system passes the project root as a preprocessor define so `Config::load()` can find the default config file relative to the project root, not the CWD:

- **CMake**: `target_compile_definitions(... PROJECT_ROOT=${CMAKE_SOURCE_DIR})`
- **tasks.json (g++)**: `-DPROJECT_ROOT=${workspaceFolder}`

The value is a raw path (e.g. `/home/user/TurboLB`), not a string literal. `Config.cpp` uses the `XSTR`/`STRINGIFY` macro trick to convert it to a proper C++ string at compile time:

```cpp
#define XSTR(x) STRINGIFY(x)
#define STRINGIFY(x) #x

configPath = std::filesystem::path(XSTR(PROJECT_ROOT)) / ".turbolb" / "config.properties";
```

### main.cpp Cleanup

`main.cpp` was slimmed down from ~50 lines of hand-rolled config resolution to 10 lines:

```cpp
int main(int argc, char *argv[])
{
    auto config = Config::load(argc, argv);
    auto host = config.getString("server.host");
    auto port = config.getInt("server.port");
    std::cout << "TurboLB starting on " << host << ":" << port << std::endl;
    return 0;
}
```

### Key Decisions

| Decision | Why |
|---|---|
| **`.properties` over JSON/YAML** | Zero dependencies — no JSON or YAML library needed. Simple enough for a learning project. |
| **`PROJECT_ROOT` define** | Lets the dev default config path work from any CWD. Production deployments override via env var or `--config`. |
| **`XSTR/STRINGIFY` macro trick** | Converts a raw preprocessor value (e.g. `/home/user/TurboLB`) into a C++ string literal without needing quotes in the `-D` flag. |
| **Static `Config::load()` factory** | Keeps the precedence logic inside `Config` where it belongs, not in `main()`. |
| **`properties_` naming convention** | Renamed from `properties` to follow the trailing-underscore convention for class members. |

### What I Learned

1. **Preprocessor defines for paths are tricky** — `-DPATH=/foo` makes `PATH` expand to `/foo`, which is not valid C++ syntax. `-DPATH="/foo"` makes it a string literal. The `XSTR` trick lets you use the raw form and stringify it in code.

2. **JSON parsing adds a dependency** — for a learning project, a flat key-value format is sufficient and avoids pulling in nlohmann/json or writing a recursive descent parser.

3. **Factory methods encapsulate construction logic** — `Config::load()` hides the precedence chain from callers. Adding a new resolution strategy (e.g., a system config path) doesn't touch `main.cpp`.

4. **The `.turbolb/` directory** — following the convention of dot-directories for project-local config (like `.vscode/`, `.git/`, `.github/`), the config lives in `.turbolb/config.properties`.

### What's Next

Now that the config system is in place, the next step is to implement the actual load-balancing logic:
1. Read backend list from config and connect to them.
2. Implement round-robin request forwarding.
3. Add health checks to detect dead backends.

---

## 2026-07-18 — Java Port (Full Recreation)

### What Changed

The entire TurboLB project has been recreated from C++ to Java. The C++ source files (`main.cpp`, `Server.cpp`, `HttpParser.cpp`, `Config.cpp`, their headers, `CMakeLists.txt`, and the Catch2 test file) have been replaced with a complete Java implementation using Maven and JUnit 5.

### New Java Files

| File | Role | C++ Origin |
|---|---|---|
| `src/main/java/.../config/Config.java` | `.properties` file parser | `include/lb/Config.hpp` + `src/config/Config.cpp` |
| `src/main/java/.../http/HttpParser.java` | State-machine HTTP parser | `include/lb/HttpParser.hpp` + `src/http/HttpParser.cpp` |
| `src/main/java/.../server/Server.java` | NIO event-driven server | `include/lb/Server.hpp` + `src/server/Server.cpp` |
| `src/main/java/.../App.java` | Entry point | `src/main.cpp` |
| `src/test/java/.../config/ConfigTest.java` | Config unit tests (20 tests) | `test/test_server.cpp` (Config portion) |
| `src/test/java/.../http/HttpParserTest.java` | HttpParser unit tests (20 tests) | New — parser-specific tests |
| `src/test/java/.../server/ServerTest.java` | Server unit + integration tests (12 tests) | `test/test_server.cpp` |

### The C++ → Java Architecture Map

| C++ Concept | Java Equivalent | Notes |
|---|---|---|
| `epoll` + `epoll_wait()` | `Selector` + `select()` | Same single-threaded event loop pattern |
| `fcntl(_, O_NONBLOCK)` | `configureBlocking(false)` | Standard Java NIO API |
| Edge-triggered (`EPOLLET`) | Level-triggered + read-all pattern | Java NIO is level-triggered only; we compensate by reading until buffer is empty |
| `SO_REUSEADDR` | `StandardSocketOptions.SO_REUSEADDR` | Same semantics |
| Self-pipe trick for wakeup | Loopback `SocketChannel` wakeup channel | Same concept; Java's `Selector.wakeup()` is unreliable with concurrent `close()` |
| `errno` / `EAGAIN` | Non-blocking `read()` returning 0 or -1 | Same semantics, different API |
| RAII destructors | `AutoCloseable` + `close()` | `try-with-resources` for scoped cleanup |
| CMake + Ninja | Maven | Build system swap |
| Catch2 (`REQUIRE_NOTHROW`) | JUnit 5 (`assertDoesNotThrow`) | Same assertion semantics |
| `atomic<int>` port counter | `AtomicInteger` port counter | Same pattern for parallel-safe test ports |

### The Server Class (Java NIO)

The `Server` class in Java uses `java.nio.channels.Selector` — the JVM's multiplexer analogous to `epoll`. The event loop follows the same structure as the C++ version:

```java
while (running.get()) {
    int ready = selector.select();      // epoll_wait()
    // process selected keys
    for (SelectionKey key : keys) {
        if (key.isAcceptable())  handleAccept(key);
        if (key.isReadable())    handleRead(key);
    }
}
```

Key design decisions in the Java port:

1. **Wakeup channel**: Uses a loopback TCP connection (self-pipe trick) to interrupt `select()` on shutdown — mirrors the C++ approach of using an eventfd or self-pipe.

2. **Read-all pattern**: On each `OP_READ` event, the handler reads in a loop until no more data is available (`bytesRead <= 0`). This compensates for the lack of edge-triggered semantics in Java NIO.

3. **HTTP integration**: Unlike the C++ version where HTTP parsing happened in `main.cpp` outside the event loop, the Java port integrates `HttpParser` directly into the `Server`'s `handleRead()` method. This moves the architecture toward the stated goal from the 07-12 engineering entry.

### The Config Class (Java)

The `Config` port is a straightforward translation with a few improvements:

1. **No `XSTR`/`STRINGIFY` macro needed**: Java doesn't have C-style macros. Instead, `Config.resolvePath()` searches multiple well-known locations at runtime.

2. **Config path resolution** (precedence):
   - `--config <path>` in CLI args
   - `TURBOLB_CONFIG` environment variable
   - `$HOME/.turbolb/config.properties`
   - `.turbolb/config.properties` (current working directory)

3. **`Config.load(String[] args)`** is a static factory that calls `resolvePath()` internally — cleaner than the C++ version where `main()` had to call `Config::load(argc, argv)`.

4. **Switch expression for booleans**: Uses Java 21's `switch` expression for clean boolean parsing.

### The HttpParser Class (Java)

The HTTP parser is a faithful port of the C++ state machine:

- Same 5 states: `REQUEST_LINE → HEADERS → BODY → COMPLETE | ERROR`
- Same incremental feeding model: `consume()` can be called repeatedly with partial data
- Headers are lowercased for case-insensitive lookup
- `getHeader(String name)` provides case-insensitive access
- `reset()` allows re-use of the parser instance

### The Test Suite

The original C++ test file (`test/test_server.cpp`) had 14 test cases with 33 assertions covering:
- **Unit tests** (9): construction, initialization, port reuse, stop safety
- **Integration tests** (5): accept, echo, multiple clients, connect-send-close cycle, stop+restart

The Java test suite expands this to **52 test cases**:

| Test Class | Tests | Focus |
|---|---|---|
| `ConfigTest` | 20 | Loading, comments, whitespace, type coercion, booleans, missing keys, path resolution, edge cases |
| `HttpParserTest` | 20 | Request parsing, headers, body, incremental feeding, error states, reset, edge cases |
| `ServerTest` | 12 | Construction, initialization, port reuse, accept, multi-client, stop+restart |

Integration tests use **real TCP sockets** — the same approach as the C++ version. A unique port counter (`AtomicInteger` starting at 25000) prevents collisions between tests, identical to the C++ `atomic<int>` approach.

### pom.xml Changes

The original `pom.xml` was a minimal Maven skeleton with JUnit 3.8.1. Changes made:

| Change | Before | After |
|---|---|---|
| JUnit | 3.8.1 | JUnit Jupiter 5.10.1 |
| Java version | default (5/6) | 21 |
| Compiler plugin | none | `maven-compiler-plugin` 3.11.0 |
| JAR plugin | none | `maven-jar-plugin` with `mainClass` manifest |
| Surefire plugin | none | `maven-surefire-plugin` 3.2.3 |

### Makefile Changes

The Makefile was rewritten from a CMake wrapper to a Maven wrapper:

| Command | C++ (CMake) | Java (Maven) |
|---|---|---|
| `make build` | `cmake --build build/` | `mvn compile` |
| `make test` | `cd build && ctest` | `mvn test` |
| `make run` | `build/TurboLB` | `java -jar target/turbolb-*.jar` |
| `make clean` | `rm -rf build/` | `rm -rf target/` |

### Key Decisions

| Decision | Why |
|---|---|
| **Java NIO over Netty** | Understanding the Selector API is the whole point — same reason the C++ version uses raw epoll instead of libuv. |
| **Level-triggered with read-all** | Java NIO only supports level-triggered. The read-all loop (`while (read > 0)`) compensates and matches edge-triggered efficiency. |
| **HttpParser in Server, not main()** | The C++ journal (07-12) explicitly lists this as a future improvement. The Java port implements it directly. |
| **JUnit 5 over JUnit 3/4** | Modern test framework with better assertions, parameterized tests, and `@TempDir` for isolated file I/O testing. |
| **52 tests instead of 14** | Each class gets its own focused test file. Many edge cases (empty input, incremental body, byte-by-byte feeding) are now covered. |
| **Loopback wakeup channel** | Same pattern as the C++ self-pipe trick. `Selector.wakeup()` is documented as unreliable when closing channels concurrently. |

### What I Learned

1. **Java NIO is epoll for the JVM** — The `Selector` API abstracts the same `epoll`/`kqueue`/`IOCP` mechanisms that the C++ version uses directly. The event loop pattern is identical at the architectural level.

2. **Level-triggered vs edge-triggered** — Java NIO doesn't expose edge-triggered mode. This means `select()` may return the same ready event multiple times unless all data is consumed. The fix is to always read in a loop until you'd block — which is exactly what edge-triggered code already does in C++.

3. **`Selector.wakeup()` is not enough** — For graceful shutdown, waking up the selector is only half the battle. The selected keys still need to be processed, and the selector needs to close cleanly. A dedicated wakeup channel (loopback connection) provides deterministic control over the lifecycle.

4. **Maven is more ceremonial than CMake** — A CMake project can be expressed in 30 lines. A Maven `pom.xml` with JAR packaging, manifest, compiler settings, and surefire configuration takes twice that. The `Makefile` wrapper is arguably *more* important for Maven than it was for CMake.

5. **Testing NIO servers needs the same tricks as testing epoll servers** — The wakeup mechanism, dedicated thread, and port counter patterns transferred directly from C++ to Java with no conceptual changes.

6. **Switch expressions make boolean parsing elegant** — Java 21's `switch` with arrow cases is much cleaner than a chain of `if-else` statements, and it's exhaustive at compile time.

### What's Next

The Java port establishes the same foundation as the C++ version. Future work is the same on both sides:

1. Implement backend connection pooling with round-robin.
2. Add health checks to detect and remove dead backends.
3. Forward parsed HTTP requests to backends using the parsed URI and headers.
4. Support keep-alive connections.
