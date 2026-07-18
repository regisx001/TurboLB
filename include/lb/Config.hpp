#pragma once
#ifndef TURBOLB_CONFIG_HPP
#define TURBOLB_CONFIG_HPP

#include <string>
#include <unordered_map>

class Config
{
public:
    explicit Config(const std::string &filename);

    /// Load config with precedence: --config <path> > $TURBOLB_CONFIG > PROJECT_ROOT/.turbolb/config.properties
    static Config load(int argc, char *argv[]);

    std::string getString(const std::string &key) const;
    int getInt(const std::string &key) const;
    bool getBool(const std::string &key) const;

    std::string getString(const std::string &key,
                          const std::string &defaultValue) const;

    int getInt(const std::string &key,
               int defaultValue) const;

    bool getBool(const std::string &key,
                 bool defaultValue) const;

private:
    std::unordered_map<std::string, std::string> properties_;

    void load(const std::string &filename);
};

#endif