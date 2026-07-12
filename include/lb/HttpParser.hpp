#pragma once
#ifndef TURBOLB_HTTP_PARSER_HPP
#define TURBOLB_HTTP_PARSER_HPP

#include "HttpRequest.hpp"
#include <string>
#include <cstddef>

enum class ParseState
{
    REQUEST_LINE,
    HEADERS,
    BODY,
    COMPLETE,
    ERROR
};

class HttpParser
{
public:
    HttpParser();

    /**
     * @brief Feed data into the incremental parser.
     *
     * @param data Pointer to the data buffer
     * @param len Length of the data
     * @return Number of bytes consumed from the input data
     */
    size_t consume(const char *data, size_t len);

    ParseState getState() const { return state_; }
    const HttpRequest &getRequest() const { return request_; }

    void reset();

private:
    ParseState state_;
    HttpRequest request_;
    std::string buffer_;
    size_t expected_body_length_;

    // Internal parsing helpers
    bool parseRequestLine(const std::string &line);
    bool parseHeaderLine(const std::string &line);
    HttpMethod stringToMethod(const std::string &m);
};

#endif // TURBOLB_HTTP_PARSER_HPP
