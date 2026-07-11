cmake_minimum_required(VERSION 3.20)

project(TurboLB LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})



add_executable(TurboLB
    src/main.cpp
    src/server/Server.cpp
    src/http/HttpParser.cpp
)


target_include_directories(TurboLB
    PRIVATE
        ${PROJECT_SOURCE_DIR}/include
)




# TESTING
enable_testing()

add_executable(tests
    tests/test_server.cpp
    tests/test_http_parser.cpp
    src/server/Server.cpp
    src/http/HttpParser.cpp
)

target_include_directories(tests PRIVATE include)


add_test(NAME server_tests COMMAND ${CMAKE_BINARY_DIR}/tests)