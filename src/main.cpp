#include "lb/Server.hpp"

int main()
{
    Server server(8080);
    if (!server.initialize())
    {
        return 1;
    }
    server.run();
    return 0;
}