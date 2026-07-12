#include <cstring>
#include <iostream>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#include <arpa/inet.h>

using namespace std;

int main()
{
    // CONNECT TO BACKEND SOCKET

    const int BACKEND_PORT = 9001;
    int backend_fd = socket(AF_INET, SOCK_STREAM, 0);

    sockaddr_in backend{};
    backend.sin_family = AF_INET;
    backend.sin_port = htons(BACKEND_PORT);

    inet_pton(AF_INET, "127.0.0.1", &backend.sin_addr);

    if (connect(backend_fd,
                (sockaddr *)&backend,
                sizeof(backend)) < 0)
    {
        cout << "Failed to connect to backend socket" << endl;
    }

    cout << "Connected to backend at port " << BACKEND_PORT << endl;

    // creating socket
    int server_df = socket(AF_INET, SOCK_STREAM, 0);
    if (server_df < 0)
    {
        cerr << "Failed to create socket" << endl;
        return 1;
    }

    // allow reuse of the address (avoid "address already in use")
    int opt = 1;
    if (setsockopt(server_df, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0)
    {
        cerr << "Failed to set socket options" << endl;
        close(server_df);
        return 1;
    }

    // specifying the address
    sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_port = htons(8080);
    addr.sin_addr.s_addr = INADDR_ANY;

    // binding socket
    if (bind(server_df, (struct sockaddr *)&addr,
             sizeof(addr)) < 0)
    {
        cerr << "Failed to bind socket" << endl;
        close(server_df);
        return 1;
    }

    // listening to the assigned socket
    if (listen(server_df, 5) < 0)
    {
        cerr << "Failed to listen on socket" << endl;
        close(server_df);
        return 1;
    }

    cout << "Server listening on port 8080..." << endl;

    // accepting connections in a loop
    while (true)
    {
        sockaddr_in client_addr;
        socklen_t clientAddrLen = sizeof(client_addr);

        int client_fd = accept(server_df,
                               (struct sockaddr *)&client_addr,
                               &clientAddrLen);
        if (client_fd < 0)
        {
            cerr << "Failed to accept connection" << endl;
            continue;
        }

        char clientIP[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &client_addr.sin_addr, clientIP, sizeof(clientIP));
        cout << "Client connected: " << clientIP << ":"
             << ntohs(client_addr.sin_port) << endl;

        // receiving data from client
        char buffer[4096] = {0};
        ssize_t bytesRead = recv(client_fd, buffer, sizeof(buffer) - 1, 0);
        if (bytesRead > 0)
        {
            cout << "Request from client (" << bytesRead << " bytes)" << endl;

            // forward request to backend
            send(backend_fd, buffer, bytesRead, 0);

            // read response from backend
            char respBuffer[4096] = {0};
            ssize_t backendBytes = recv(backend_fd, respBuffer, sizeof(respBuffer) - 1, 0);
            if (backendBytes > 0)
            {
                cout << "Response from backend (" << backendBytes << " bytes)" << endl;
                send(client_fd, respBuffer, backendBytes, 0);
            }
        }

        // closing the client socket
        close(client_fd);
        cout << "Client disconnected" << endl;
    }

    // closing the server socket (unreachable in this loop, but good practice)
    close(server_df);

    return 0;
}