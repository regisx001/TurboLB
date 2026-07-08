cmake_minimum_required(VERSION 3.20)

project(TurboLB LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(TurboLB
    src/main.cpp
    include/lb/Server.cpp
)


target_include_directories(TurboLB
    PRIVATE
        ${PROJECT_SOURCE_DIR}/include
)