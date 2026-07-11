#include "lb/HttpParser.hpp"
#include <cstring>
#include <algorithm>
#include <cctype>

HttpParser::HttpParser() {
    reset();
}

void HttpParser::reset() {
    state_ = ParseState::REQUEST_LINE;
    request_ = HttpRequest{};
    buffer_.clear();
    expected_body_length_ = 0;
}

size_t HttpParser::consume(const char* data, size_t len) {
    size_t consumed = 0;

    while (consumed < len && state_ != ParseState::COMPLETE && state_ != ParseState::ERROR) {
        if (state_ == ParseState::REQUEST_LINE || state_ == ParseState::HEADERS) {
            // Find \n in the remaining data + buffer
            const char* start = data + consumed;
            size_t available = len - consumed;
            const char* nl = static_cast<const char*>(memchr(start, '\n', available));

            if (nl) {
                // We found a newline. 
                size_t chunk_len = (nl - start) + 1;
                buffer_.append(start, chunk_len);
                consumed += chunk_len;

                // Strip \r\n or \n from the end of buffer_
                if (buffer_.length() >= 2 && buffer_[buffer_.length() - 2] == '\r') {
                    buffer_.pop_back(); // remove \n
                    buffer_.pop_back(); // remove \r
                } else {
                    buffer_.pop_back(); // remove \n
                }

                if (state_ == ParseState::REQUEST_LINE) {
                    if (!parseRequestLine(buffer_)) {
                        state_ = ParseState::ERROR;
                    } else {
                        state_ = ParseState::HEADERS;
                    }
                } else if (state_ == ParseState::HEADERS) {
                    if (buffer_.empty()) {
                        // Empty line means end of headers
                        if (expected_body_length_ > 0) {
                            state_ = ParseState::BODY;
                        } else {
                            state_ = ParseState::COMPLETE;
                        }
                    } else {
                        if (!parseHeaderLine(buffer_)) {
                            state_ = ParseState::ERROR;
                        }
                    }
                }
                
                // Clear the buffer to start fresh for the next line
                buffer_.clear();
            } else {
                // No newline found in this chunk, append all of it and wait for more
                buffer_.append(start, available);
                consumed += available;
            }
        } else if (state_ == ParseState::BODY) {
            size_t needed = expected_body_length_ - request_.body.length();
            size_t available = len - consumed;
            size_t take = std::min(needed, available);
            
            request_.body.append(data + consumed, take);
            consumed += take;
            
            if (request_.body.length() == expected_body_length_) {
                state_ = ParseState::COMPLETE;
            }
        }
    }

    return consumed;
}

bool HttpParser::parseRequestLine(const std::string& line) {
    // Format: METHOD URI VERSION
    size_t pos1 = line.find(' ');
    if (pos1 == std::string::npos) return false;
    
    size_t pos2 = line.find(' ', pos1 + 1);
    if (pos2 == std::string::npos) return false;

    std::string method_str = line.substr(0, pos1);
    request_.uri = line.substr(pos1 + 1, pos2 - pos1 - 1);
    request_.version = line.substr(pos2 + 1);

    request_.method = stringToMethod(method_str);
    if (request_.method == HttpMethod::UNKNOWN) {
        return false;
    }

    return true;
}

bool HttpParser::parseHeaderLine(const std::string& line) {
    size_t colon_pos = line.find(':');
    if (colon_pos == std::string::npos) return false;

    std::string key = line.substr(0, colon_pos);
    std::string value = line.substr(colon_pos + 1);

    // Trim leading whitespace from value
    size_t value_start = value.find_first_not_of(" \t");
    if (value_start != std::string::npos) {
        value = value.substr(value_start);
    } else {
        value = ""; // Only whitespace
    }
    
    // Trim trailing whitespace from value
    size_t value_end = value.find_last_not_of(" \t");
    if (value_end != std::string::npos) {
        value = value.substr(0, value_end + 1);
    }

    // Convert key to lowercase for case-insensitive matching
    std::transform(key.begin(), key.end(), key.begin(), [](unsigned char c){ return std::tolower(c); });

    request_.headers[key] = value;

    if (key == "content-length") {
        try {
            expected_body_length_ = std::stoull(value);
        } catch (...) {
            return false;
        }
    }

    return true;
}

HttpMethod HttpParser::stringToMethod(const std::string& m) {
    if (m == "GET") return HttpMethod::GET;
    if (m == "POST") return HttpMethod::POST;
    if (m == "PUT") return HttpMethod::PUT;
    if (m == "DELETE") return HttpMethod::DELETE;
    if (m == "HEAD") return HttpMethod::HEAD;
    if (m == "OPTIONS") return HttpMethod::OPTIONS;
    if (m == "PATCH") return HttpMethod::PATCH;
    if (m == "TRACE") return HttpMethod::TRACE;
    if (m == "CONNECT") return HttpMethod::CONNECT;
    return HttpMethod::UNKNOWN;
}
