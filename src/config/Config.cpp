#include "lb/Config.hpp"
#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <stdexcept>

// ── Convert a preprocessor symbol to a C++ string literal ──────────
// Usage: XSTR(PROJECT_ROOT) → "/home/regisx001/TurboLB"
#define XSTR(x) STRINGIFY(x)
#define STRINGIFY(x) #x

namespace
{
    std::string trim(const std::string &str)
    {
        const auto first = str.find_first_not_of(" \t\r\n");

        if (first == std::string::npos)
            return "";

        const auto last = str.find_last_not_of(" \t\r\n");

        return str.substr(first, last - first + 1);
    }

    std::string toLower(std::string str)
    {
        std::transform(
            str.begin(),
            str.end(),
            str.begin(),
            [](unsigned char c)
            {
                return static_cast<char>(std::tolower(c));
            });

        return str;
    }
}

// ── Static factory: resolves config path with precedence ──────────
//   1) --config <path>
//   2) $TURBOLB_CONFIG
//   3) PROJECT_ROOT/.turbolb/config.properties (development default)
// ──────────────────────────────────────────────────────────────────────
Config Config::load(int argc, char *argv[])
{
    std::filesystem::path configPath;

    if (argc >= 3 && std::strcmp(argv[1], "--config") == 0)
    {
        configPath = argv[2];
    }
    else if (const char *env = std::getenv("TURBOLB_CONFIG"))
    {
        configPath = env;
    }
    else
    {
#ifdef PROJECT_ROOT
        configPath = std::filesystem::path(XSTR(PROJECT_ROOT)) / ".turbolb" / "config.properties";
#else
        configPath = ".turbolb/config.properties";
#endif
    }

    return Config(configPath.string());
}

Config::Config(const std::string &filename)
{
    load(filename);
}

void Config::load(const std::string &filename)
{
    std::ifstream file(filename);

    if (!file)
    {
        throw std::runtime_error(
            "Failed to open configuration file: " + filename);
    }

    std::string line;
    size_t lineNumber = 0;

    while (std::getline(file, line))
    {
        ++lineNumber;

        line = trim(line);

        if (line.empty())
            continue;

        if (line[0] == '#' || line[0] == ';')
            continue;

        auto pos = line.find('=');

        if (pos == std::string::npos)
        {
            throw std::runtime_error(
                "Invalid configuration at line " +
                std::to_string(lineNumber));
        }

        auto key = trim(line.substr(0, pos));
        auto value = trim(line.substr(pos + 1));

        properties_[key] = value;
    }
}

std::string Config::getString(const std::string &key) const
{
    auto it = properties_.find(key);

    if (it == properties_.end())
    {
        throw std::runtime_error(
            "Missing configuration key: " + key);
    }

    return it->second;
}

std::string Config::getString(
    const std::string &key,
    const std::string &defaultValue) const
{
    auto it = properties_.find(key);

    if (it == properties_.end())
        return defaultValue;

    return it->second;
}

int Config::getInt(const std::string &key) const
{
    return std::stoi(getString(key));
}

int Config::getInt(
    const std::string &key,
    int defaultValue) const
{
    auto it = properties_.find(key);

    if (it == properties_.end())
        return defaultValue;

    return std::stoi(it->second);
}

bool Config::getBool(const std::string &key) const
{
    auto value = toLower(getString(key));

    if (value == "true" || value == "1" || value == "yes")
        return true;

    if (value == "false" || value == "0" || value == "no")
        return false;

    throw std::runtime_error(
        "Invalid boolean value for key: " + key);
}

bool Config::getBool(
    const std::string &key,
    bool defaultValue) const
{
    auto it = properties_.find(key);

    if (it == properties_.end())
        return defaultValue;

    auto value = toLower(it->second);

    if (value == "true" || value == "1" || value == "yes")
        return true;

    if (value == "false" || value == "0" || value == "no")
        return false;

    throw std::runtime_error(
        "Invalid boolean value for key: " + key);
}