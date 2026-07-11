#pragma once
#ifndef TURBOLB_HTTP_REQUEST_HPP
#define TURBOLB_HTTP_REQUEST_HPP

#include <string>
#include <unordered_map>

enum class HttpMethod
{
    UNKNOWN,
    GET,
    POST,
    PUT,
    DELETE,
    HEAD,
    OPTIONS,
    PATCH,
    TRACE,
    CONNECT
};

struct HttpRequest
{
    HttpMethod method = HttpMethod::UNKNOWN;
    std::string uri;
    std::string version;
    std::unordered_map<std::string, std::string> headers;
    std::string body;

    std::string methodString() const
    {
        switch (method)
        {
        case HttpMethod::GET:
            return "GET";
        case HttpMethod::POST:
            return "POST";
        case HttpMethod::PUT:
            return "PUT";
        case HttpMethod::DELETE:
            return "DELETE";
        case HttpMethod::HEAD:
            return "HEAD";
        case HttpMethod::OPTIONS:
            return "OPTIONS";
        case HttpMethod::PATCH:
            return "PATCH";
        case HttpMethod::TRACE:
            return "TRACE";
        case HttpMethod::CONNECT:
            return "CONNECT";
        default:
            return "UNKNOWN";
        }
    }
};

#endif // TURBOLB_HTTP_REQUEST_HPP
