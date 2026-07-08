#include <iostream>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <cstring>

int main()
{
    // Create socket
    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0)
    {
        std::cerr << "Failed to create socket\n";
        return 1;
    }

    // Allow address reuse
    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    // Bind to port 8080
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons(8080);

    if (bind(server_fd, (sockaddr *)&address, sizeof(address)) < 0)
    {
        std::cerr << "Bind failed\n";
        return 1;
    }

    // Listen
    if (listen(server_fd, 10) < 0)
    {
        std::cerr << "Listen failed\n";
        return 1;
    }

    std::cout << "TurboLB listening on port 8080...\n";

    // Accept loop (blocking, single-threaded for now)
    while (true)
    {
        sockaddr_in client_addr{};
        socklen_t client_len = sizeof(client_addr);
        int client_fd = accept(server_fd, (sockaddr *)&client_addr, &client_len);
        if (client_fd < 0)
        {
            std::cerr << "Accept failed\n";
            continue;
        }

        std::cout << "New connection accepted\n";

        // TODO: Connect to backend and proxy bytes
        // For now, just send a placeholder response
        const char *response = "HTTP/1.1 200 OK\r\nContent-Length: 13\r\n\r\nHello TurboLB\n";
        send(client_fd, response, strlen(response), 0);
        close(client_fd);
    }

    close(server_fd);
    return 0;
}