#include "catch.hpp"
#include "lb/Config.hpp"

#include <string>
#include <fstream>
#include <cstdio>
#include <cstring>
#include <filesystem>

// ── Helpers ──────────────────────────────────────────────────────────────

static int tempCounter = 0;

/// Write `content` to a unique temp file and return its path.
static std::string writeTempConfig(const std::string &content)
{
    auto path = std::filesystem::temp_directory_path() /
                ("turbolb_test_" + std::to_string(tempCounter++) + ".properties");
    std::ofstream f(path);
    f << content;
    return path.string();
}

// ── Basic loading ────────────────────────────────────────────────────────

TEST_CASE("Config loads simple key=value pairs", "[config]")
{
    auto path = writeTempConfig(
        "key1=value1\n"
        "key2=value2\n");

    Config cfg(path);
    REQUIRE(cfg.getString("key1") == "value1");
    REQUIRE(cfg.getString("key2") == "value2");

    std::filesystem::remove(path);
}

TEST_CASE("Config ignores comment lines (# and ;)", "[config]")
{
    auto path = writeTempConfig(
        "# this is a comment\n"
        "; this is also a comment\n"
        "key=value\n");

    Config cfg(path);
    REQUIRE(cfg.getString("key") == "value");

    std::filesystem::remove(path);
}

TEST_CASE("Config ignores blank lines", "[config]")
{
    auto path = writeTempConfig(
        "\n"
        "\n"
        "key=value\n"
        "\n");

    Config cfg(path);
    REQUIRE(cfg.getString("key") == "value");

    std::filesystem::remove(path);
}

TEST_CASE("Config trims whitespace around keys and values", "[config]")
{
    auto path = writeTempConfig("  key  =  value  \n");

    Config cfg(path);
    REQUIRE(cfg.getString("key") == "value");

    std::filesystem::remove(path);
}

// ── getString ────────────────────────────────────────────────────────────

TEST_CASE("getString returns value for existing key", "[config]")
{
    auto path = writeTempConfig("greeting=hello\n");
    Config cfg(path);

    REQUIRE(cfg.getString("greeting") == "hello");

    std::filesystem::remove(path);
}

TEST_CASE("getString throws for missing key", "[config]")
{
    auto path = writeTempConfig("existing=yes\n");
    Config cfg(path);

    REQUIRE_THROWS_AS(cfg.getString("nonexistent"), std::runtime_error);

    std::filesystem::remove(path);
}

TEST_CASE("getString with default returns value when key exists", "[config]")
{
    auto path = writeTempConfig("name=Alice\n");
    Config cfg(path);

    REQUIRE(cfg.getString("name", "Bob") == "Alice");

    std::filesystem::remove(path);
}

TEST_CASE("getString with default returns default when key missing", "[config]")
{
    auto path = writeTempConfig("other=ignored\n");
    Config cfg(path);

    REQUIRE(cfg.getString("missing", "fallback") == "fallback");

    std::filesystem::remove(path);
}

// ── getInt ───────────────────────────────────────────────────────────────

TEST_CASE("getInt parses a valid integer", "[config]")
{
    auto path = writeTempConfig("port=8080\n");
    Config cfg(path);

    REQUIRE(cfg.getInt("port") == 8080);

    std::filesystem::remove(path);
}

TEST_CASE("getInt throws for missing key", "[config]")
{
    auto path = writeTempConfig("x=1\n");
    Config cfg(path);

    REQUIRE_THROWS_AS(cfg.getInt("missing"), std::runtime_error);

    std::filesystem::remove(path);
}

TEST_CASE("getInt throws for non-numeric value", "[config]")
{
    auto path = writeTempConfig("port=notanumber\n");
    Config cfg(path);

    REQUIRE_THROWS_AS(cfg.getInt("port"), std::invalid_argument);

    std::filesystem::remove(path);
}

TEST_CASE("getInt with default returns value when key exists", "[config]")
{
    auto path = writeTempConfig("timeout=30\n");
    Config cfg(path);

    REQUIRE(cfg.getInt("timeout", 10) == 30);

    std::filesystem::remove(path);
}

TEST_CASE("getInt with default returns default when key missing", "[config]")
{
    auto path = writeTempConfig("x=1\n");
    Config cfg(path);

    REQUIRE(cfg.getInt("missing", 42) == 42);

    std::filesystem::remove(path);
}

// ── getBool ──────────────────────────────────────────────────────────────

TEST_CASE("getBool accepts true/1/yes (case-insensitive)", "[config]")
{
    auto path = writeTempConfig(
        "a=true\n"
        "b=TRUE\n"
        "c=1\n"
        "d=yes\n"
        "e=YES\n");
    Config cfg(path);

    REQUIRE(cfg.getBool("a") == true);
    REQUIRE(cfg.getBool("b") == true);
    REQUIRE(cfg.getBool("c") == true);
    REQUIRE(cfg.getBool("d") == true);
    REQUIRE(cfg.getBool("e") == true);

    std::filesystem::remove(path);
}

TEST_CASE("getBool accepts false/0/no (case-insensitive)", "[config]")
{
    auto path = writeTempConfig(
        "a=false\n"
        "b=FALSE\n"
        "c=0\n"
        "d=no\n"
        "e=NO\n");
    Config cfg(path);

    REQUIRE(cfg.getBool("a") == false);
    REQUIRE(cfg.getBool("b") == false);
    REQUIRE(cfg.getBool("c") == false);
    REQUIRE(cfg.getBool("d") == false);
    REQUIRE(cfg.getBool("e") == false);

    std::filesystem::remove(path);
}

TEST_CASE("getBool throws for invalid boolean string", "[config]")
{
    auto path = writeTempConfig("flag=maybe\n");
    Config cfg(path);

    REQUIRE_THROWS_AS(cfg.getBool("flag"), std::runtime_error);

    std::filesystem::remove(path);
}

TEST_CASE("getBool with default returns value when key exists", "[config]")
{
    auto path = writeTempConfig("debug=true\n");
    Config cfg(path);

    REQUIRE(cfg.getBool("debug", false) == true);

    std::filesystem::remove(path);
}

TEST_CASE("getBool with default returns default when key missing", "[config]")
{
    auto path = writeTempConfig("x=1\n");
    Config cfg(path);

    REQUIRE(cfg.getBool("missing", true) == true);
    REQUIRE(cfg.getBool("alsoMissing", false) == false);

    std::filesystem::remove(path);
}

// ── Error handling ───────────────────────────────────────────────────────

TEST_CASE("Config throws on non-existent file", "[config]")
{
    REQUIRE_THROWS_AS(
        Config("/nonexistent/path/config.properties"),
        std::runtime_error);
}

TEST_CASE("Config throws on malformed line (no = sign)", "[config]")
{
    auto path = writeTempConfig("justaword\n");
    REQUIRE_THROWS_AS(Config(path), std::runtime_error);
    std::filesystem::remove(path);
}

TEST_CASE("Config throws on missing key without default", "[config]")
{
    auto path = writeTempConfig("a=1\n");
    Config cfg(path);

    REQUIRE_THROWS_AS(cfg.getString("b"), std::runtime_error);
    REQUIRE_THROWS_AS(cfg.getInt("b"), std::runtime_error);
    REQUIRE_THROWS_AS(cfg.getBool("b"), std::runtime_error);

    std::filesystem::remove(path);
}

// ── Real-world config file ───────────────────────────────────────────────

TEST_CASE("Config loads the project's actual config.properties", "[config]")
{
    // Look relative to build dir, source dir, or CWD
    std::vector<std::string> candidates = {
        "../.turbolb/config.properties",
        ".turbolb/config.properties",
        "../../.turbolb/config.properties",
    };

    std::string found;
    for (const auto &p : candidates)
    {
        if (std::filesystem::exists(p))
        {
            found = p;
            break;
        }
    }

    REQUIRE(!found.empty());

    Config cfg(found);
    REQUIRE(cfg.getString("server.host") == "0.0.0.0");
    REQUIRE(cfg.getInt("server.port") == 8080);
    REQUIRE(cfg.getInt("server.workers") == 4);
    REQUIRE(cfg.getString("logging.level") == "INFO");
}

// ── Config::load() factory ───────────────────────────────────────────────

TEST_CASE("Config::load with --config flag", "[config][factory]")
{
    auto path = writeTempConfig("mode=test\n");

    const char *argv[] = {"program", "--config", path.c_str()};
    auto cfg = Config::load(3, const_cast<char **>(argv));

    REQUIRE(cfg.getString("mode") == "test");

    std::filesystem::remove(path);
}

TEST_CASE("Config::load falls back when no --config or env var", "[config][factory]")
{
    // Without --config and without TURBOLB_CONFIG, it tries PROJECT_ROOT
    // (if defined) or a relative path. We just verify it doesn't crash.
    const char *argv[] = {"program"};
    // Should not throw — either finds a config or throws a meaningful error
    // about a missing file (not a crash).
    REQUIRE_NOTHROW(Config::load(1, const_cast<char **>(argv)));
}
