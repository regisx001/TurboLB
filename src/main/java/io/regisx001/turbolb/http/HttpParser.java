package io.regisx001.turbolb.http;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Incremental, state-machine-based HTTP request parser.
 *
 * States:
 * REQUEST_LINE — parsing "METHOD URI VERSION"
 * HEADERS — parsing header lines until blank line
 * BODY — reading exactly Content-Length bytes
 * COMPLETE — request is fully parsed
 * ERROR — malformed request encountered
 *
 * Usage:
 * {@code
 *   HttpParser parser = new HttpParser();
 *   parser.consume(data);
 *   if (parser.getState() == HttpParser.State.COMPLETE) {
 *       System.out.println(parser.getMethod());
 *       System.out.println(parser.getUri());
 *       // ...
 *   }
 *   }
 */
public class HttpParser {

    public enum State {
        REQUEST_LINE,
        HEADERS,
        BODY,
        COMPLETE,
        ERROR
    }

    private State state = State.REQUEST_LINE;

    // Parsed request line
    private String method;
    private String uri;
    private String version;

    // Parsed headers
    private final Map<String, String> headers = new LinkedHashMap<>();

    // Body tracking
    private final StringBuilder body = new StringBuilder();
    private int contentLength = 0;

    // Internal accumulation buffer for incremental parsing
    private final StringBuilder buffer = new StringBuilder();
    private int bodyBytesRead = 0;

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Feeds raw data into the parser. Can be called multiple times
     * as data arrives (e.g. from partial TCP reads).
     *
     * @param data the raw bytes of the HTTP request as a string
     * @return the current state after consuming this chunk
     */
    public State consume(String data) {
        if (state == State.COMPLETE || state == State.ERROR) {
            return state;
        }

        buffer.append(data);

        // Process until no more progress can be made (e.g. partial data
        // without a complete CRLF line means we need to wait for more input).
        boolean progress = true;
        while (state != State.COMPLETE && state != State.ERROR
                && buffer.length() > 0 && progress) {
            int before = buffer.length();
            switch (state) {
                case REQUEST_LINE:
                    parseRequestLine();
                    break;
                case HEADERS:
                    parseHeaders();
                    break;
                case BODY:
                    parseBody();
                    break;
                default:
                    // unreachable
                    break;
            }
            progress = buffer.length() < before;
        }

        return state;
    }

    /**
     * Resets the parser to its initial state for re-use.
     */
    public void reset() {
        state = State.REQUEST_LINE;
        method = null;
        uri = null;
        version = null;
        headers.clear();
        body.setLength(0);
        buffer.setLength(0);
        contentLength = 0;
        bodyBytesRead = 0;
    }

    // ── Getters ────────────────────────────────────────────────────────────

    public State getState() {
        return state;
    }

    public String getMethod() {
        return method;
    }

    public String getUri() {
        return uri;
    }

    public String getVersion() {
        return version;
    }

    public Map<String, String> getHeaders() {
        return new LinkedHashMap<>(headers);
    }

    public String getHeader(String name) {
        return headers.get(name.toLowerCase());
    }

    public String getBody() {
        return body.toString();
    }

    // ── State Machine ──────────────────────────────────────────────────────

    private void parseRequestLine() {
        int eol = findEndOfLine(buffer);
        if (eol < 0) {
            // Not enough data yet — wait for more
            return;
        }

        String line = buffer.substring(0, eol);
        buffer.delete(0, eol + 2); // consume line + "\r\n"

        // Parse "METHOD URI VERSION"
        String[] parts = line.split(" ", 3);
        if (parts.length < 3) {
            state = State.ERROR;
            return;
        }

        method = parts[0];
        uri = parts[1];
        version = parts[2];

        state = State.HEADERS;
    }

    private void parseHeaders() {
        while (true) {
            int eol = findEndOfLine(buffer);
            if (eol < 0) {
                // Not enough data yet
                return;
            }

            String line = buffer.substring(0, eol);
            buffer.delete(0, eol + 2); // consume line + "\r\n"

            // Blank line marks end of headers
            if (line.isEmpty()) {
                // Determine if we expect a body
                String cl = headers.get("content-length");
                if (cl != null) {
                    try {
                        contentLength = Integer.parseInt(cl.trim());
                    } catch (NumberFormatException e) {
                        state = State.ERROR;
                        return;
                    }
                }

                if (contentLength > 0) {
                    state = State.BODY;
                } else {
                    state = State.COMPLETE;
                }
                return;
            }

            // Parse "Key: Value"
            int colon = line.indexOf(':');
            if (colon < 0) {
                state = State.ERROR;
                return;
            }

            String key = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            headers.put(key, value);
        }
    }

    private void parseBody() {
        int available = buffer.length();
        int needed = contentLength - bodyBytesRead;
        int toRead = Math.min(available, needed);

        if (toRead > 0) {
            String chunk = buffer.substring(0, toRead);
            body.append(chunk);
            bodyBytesRead += toRead;
            buffer.delete(0, toRead);
        }

        if (bodyBytesRead >= contentLength) {
            state = State.COMPLETE;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Finds the end of a CRLF-terminated line in the buffer.
     *
     * @param buf the buffer to search
     * @return the index of the last character before "\r\n", or -1 if not found
     */
    private static int findEndOfLine(StringBuilder buf) {
        for (int i = 0; i < buf.length() - 1; i++) {
            if (buf.charAt(i) == '\r' && buf.charAt(i + 1) == '\n') {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "HttpParser{" +
                "state=" + state +
                ", method='" + method + '\'' +
                ", uri='" + uri + '\'' +
                ", version='" + version + '\'' +
                ", headers=" + headers +
                ", bodyLength=" + body.length() +
                '}';
    }
}
