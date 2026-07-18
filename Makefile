# ──────────────────────────────────────────────────────────────
# TurboLB — Top-level Makefile (Maven wrapper)
#
# This Makefile delegates everything to Maven so you don't have
# to remember the mvn incantations.
#
# Usage:
#   make              # build the JAR  (default)
#   make build        # build (alias for `make`)
#   make test         # run all tests via Maven Surefire
#   make run          # run the TurboLB server
#   make clean        # remove the target/ directory
#   make rebuild      # clean + full rebuild
#   make verbose      # build with verbose output
#   make package      # produce the JAR artifact
# ──────────────────────────────────────────────────────────────

MVN         := mvn
JAVA        := java
JAR_FILE    := target/turbolb-1.0-SNAPSHOT.jar

.PHONY: all build test run clean rebuild verbose package

# ── Default target: build ─────────────────────────────────────
all: build

# ── Build ─────────────────────────────────────────────────────
build:
	@echo "── Building TurboLB ──"
	$(MVN) compile

# ── Build with verbose output ─────────────────────────────────
verbose:
	$(MVN) compile -X

# ── Run tests ─────────────────────────────────────────────────
test:
	@echo "── Running tests ──"
	$(MVN) test

# ── Run the server ────────────────────────────────────────────
run: package
	@echo "── Starting TurboLB server ──"
	$(JAVA) -jar $(JAR_FILE)

# ── Package as JAR ────────────────────────────────────────────
package:
	@echo "── Packaging ──"
	$(MVN) package -DskipTests

# ── Clean ─────────────────────────────────────────────────────
clean:
	@echo "── Removing target/ ──"
	rm -rf target/

# ── Rebuild from scratch ──────────────────────────────────────
rebuild: clean all
