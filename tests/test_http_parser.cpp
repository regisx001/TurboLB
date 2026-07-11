#include "catch.hpp"
#include "lb/HttpParser.hpp"
#include <string>

TEST_CASE("Parser parses a simple GET request", "[http_parser]") {
    HttpParser parser;
    std::string req = "GET /index.html HTTP/1.1\r\n"
                      "Host: example.com\r\n"
                      "User-Agent: test\r\n\r\n";
    
    size_t consumed = parser.consume(req.c_str(), req.length());
    
    REQUIRE(consumed == req.length());
    REQUIRE(parser.getState() == ParseState::COMPLETE);
    
    auto& r = parser.getRequest();
    REQUIRE(r.method == HttpMethod::GET);
    REQUIRE(r.uri == "/index.html");
    REQUIRE(r.version == "HTTP/1.1");
    REQUIRE(r.headers.at("host") == "example.com");
    REQUIRE(r.headers.at("user-agent") == "test");
    REQUIRE(r.body.empty());
}

TEST_CASE("Parser parses incrementally byte-by-byte", "[http_parser]") {
    HttpParser parser;
    std::string req = "POST /api/data HTTP/1.1\r\n"
                      "Content-Length: 5\r\n"
                      "Content-Type: text/plain\r\n\r\n"
                      "hello";
    
    for (size_t i = 0; i < req.length(); ++i) {
        size_t consumed = parser.consume(&req[i], 1);
        if (i < req.length() - 1) {
            REQUIRE(consumed == 1);
            REQUIRE(parser.getState() != ParseState::COMPLETE);
        } else {
            REQUIRE(consumed == 1);
            REQUIRE(parser.getState() == ParseState::COMPLETE);
        }
    }
    
    auto& r = parser.getRequest();
    REQUIRE(r.method == HttpMethod::POST);
    REQUIRE(r.uri == "/api/data");
    REQUIRE(r.version == "HTTP/1.1");
    REQUIRE(r.headers.at("content-length") == "5");
    REQUIRE(r.body == "hello");
}

TEST_CASE("Parser detects error in request line", "[http_parser]") {
    HttpParser parser;
    std::string req = "INVALID_REQUEST_LINE\r\n";
    
    parser.consume(req.c_str(), req.length());
    REQUIRE(parser.getState() == ParseState::ERROR);
}

TEST_CASE("Parser extracts body with correct Content-Length", "[http_parser]") {
    HttpParser parser;
    std::string req = "PUT /update HTTP/1.1\r\n"
                      "Content-Length: 10\r\n\r\n"
                      "0123456789";
    
    parser.consume(req.c_str(), req.length());
    REQUIRE(parser.getState() == ParseState::COMPLETE);
    REQUIRE(parser.getRequest().body == "0123456789");
}
