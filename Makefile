# ──────────────────────────────────────────────────────────────
# TurboLB — Top-level Makefile (CMake wrapper)
#
# This Makefile delegates everything to CMake so you don't have
# to remember the cmake incantations.
#
# Usage:
#   make              # configure + build  (default)
#   make build        # build only (after configure)
#   make test         # run tests via ctest
#   make run          # run the TurboLB server
#   make clean        # remove the build directory
#   make rebuild      # clean + build from scratch
#   make verbose      # build with VERBOSE=1
# ──────────────────────────────────────────────────────────────

CMAKE       := cmake
BUILD_DIR   := build
SRC_DIR     := .
CONFIG      := -DCMAKE_EXPORT_COMPILE_COMMANDS=ON

# Number of parallel jobs — use all available cores by default
JOBS        := $(shell nproc 2>/dev/null || echo 4)

.PHONY: all build test run clean rebuild verbose

# ── Default target: configure (if needed) + build ────────────
all: build

# ── Configure ─────────────────────────────────────────────────
$(BUILD_DIR)/build.ninja $(BUILD_DIR)/Makefile:
	@echo "── Configuring project with CMake ──"
	$(CMAKE) -S $(SRC_DIR) -B $(BUILD_DIR) $(CONFIG)

# ── Build ─────────────────────────────────────────────────────
build: $(BUILD_DIR)/build.ninja
	@echo "── Building ──"
	$(CMAKE) --build $(BUILD_DIR) -- -j$(JOBS)

# ── Build with verbose output ─────────────────────────────────
verbose: $(BUILD_DIR)/build.ninja
	$(CMAKE) --build $(BUILD_DIR) --verbose -- -j$(JOBS)

# ── Run tests ─────────────────────────────────────────────────
test: build
	@echo "── Running tests ──"
	cd $(BUILD_DIR) && ctest --output-on-failure

# ── Run the server ────────────────────────────────────────────
run: build
	@echo "── Starting TurboLB server ──"
	$(BUILD_DIR)/TurboLB

# ── Clean ─────────────────────────────────────────────────────
clean:
	@echo "── Removing $(BUILD_DIR) ──"
	rm -rf $(BUILD_DIR)

# ── Rebuild from scratch ──────────────────────────────────────
rebuild: clean all
