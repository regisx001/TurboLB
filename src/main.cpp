#include <iostream>
#include "lb/Config.hpp"

int main(int argc, char *argv[])
{
    auto config = Config::load(argc, argv);

    auto host = config.getString("server.host");
    auto port = config.getInt("server.port");

    std::cout << "TurboLB starting on " << host << ":" << port << std::endl;
    return 0;
}