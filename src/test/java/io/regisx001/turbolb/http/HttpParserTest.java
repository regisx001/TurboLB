package io.regisx001.turbolb.http;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link HttpParser} class.
 *
 * Tests cover all parser states:
 * - REQUEST_LINE parsing
 * - HEADERS parsing
 * - BODY parsing
 * - COMPLETE state
 * - ERROR state (malformed requests)
 * - Incremental (chunked) feeding
 * - Reset for re-use
 */
class HttpParserTest {

    // ── Basic Request Line ─────────────────────────────────────────────────

    @Test
    void parsesSimpleGetRequest() {
        HttpParser parser = new HttpParser();
        String request = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";

        HttpParser.State state = parser.consume(request);

        assertEquals(HttpParser.State.COMPLETE, state);
        assertEquals("GET", parser.getMethod());
        assertEquals("/", parser.getUri());
        assertEquals("HTTP/1.1", parser.getVersion());
    }

    @Test
    void parsesPostRequest() {
        HttpParser parser = new HttpParser();
        String body = "{\"name\":\"test\"}";
        String request = "POST /api/data HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "\r\n" +
                body;

        HttpParser.State state = parser.consume(request);

        assertEquals(HttpParser.State.COMPLETE, state);
        assertEquals("POST", parser.getMethod());
        assertEquals("/api/data", parser.getUri());
        assertEquals("HTTP/1.1", parser.getVersion());
    }

    @Test
    void parsesPutRequest() {
        HttpParser parser = new HttpParser();
        String request = "PUT /resource/123 HTTP/1.1\r\nHost: test\r\n\r\n";

        parser.consume(request);

        assertEquals(HttpParser.State.COMPLETE, parser.getState());
        assertEquals("PUT", parser.getMethod());
        assertEquals("/resource/123", parser.getUri());
    }

    // ── Headers ────────────────────────────────────────────────────────────

    @Test
    void parsesHeaders() {
        HttpParser parser = new HttpParser();
        String request = "GET / HTTP/1.1\r\n" +
                "Host: localhost:8080\r\n" +
                "User-Agent: curl/8.21.0\r\n" +
                "Accept: */*\r\n" +
                "\r\n";

        parser.consume(request);

        assertEquals(HttpParser.State.COMPLETE, parser.getState());
        Map<String, String> headers = parser.getHeaders();
        assertEquals("localhost:8080", headers.get("host"));
        assertEquals("curl/8.21.0", headers.get("user-agent"));
        assertEquals("*/*", headers.get("accept"));
    }

    @Test
    void headerKeysAreLowercased() {
        HttpParser parser = new HttpParser();
        String request = "GET / HTTP/1.1\r\n" +
                "Content-Type: application/json\r\n" +
                "X-Custom-Header: value\r\n" +
                "\r\n";

        parser.consume(request);

        assertEquals("application/json", parser.getHeader("content-type"));
        assertEquals("application/json", parser.getHeader("Content-Type"));
        assertEquals("value", parser.getHeader("x-custom-header"));
    }

    @Test
    void parsesMultipleHeaders() {
        HttpParser parser = new HttpParser();
        String request = "GET / HTTP/1.1\r\n" +
                "A: 1\r\n" +
                "B: 2\r\n" +
                "C: 3\r\n" +
                "\r\n";

        parser.consume(request);

        assertEquals(HttpParser.State.COMPLETE, parser.getState());
        assertEquals(3, parser.getHeaders().size());
    }

    // ── Request Body ───────────────────────────────────────────────────────

    @Test
    void parsesBodyWithContentLength() {
        HttpParser parser = new HttpParser();
        String body = "{\"message\":\"hello\"}";
        String request = "POST / HTTP/1.1\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "\r\n" +
                body;

        parser.consume(request);

        assertEquals(HttpParser.State.COMPLETE, parser.getState());
        assertEquals(body, parser.getBody());
    }

    @Test
    void parsesEmptyBody() {
        HttpParser parser = new HttpParser();
        String request = "POST / HTTP/1.1\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n";

        parser.consume(request);

        assertEquals(HttpParser.State.COMPLETE, parser.getState());
        assertTrue(parser.getBody().isEmpty());
    }

    @Test
    void getBodyReturnsEmptyForGetRequest() {
        HttpParser parser = new HttpParser();
        String request = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";

        parser.consume(request);

        assertEquals(HttpParser.State.COMPLETE, parser.getState());
        assertTrue(parser.getBody().isEmpty());
    }

    // ── Malformed Requests (ERROR State) ───────────────────────────────────

    @Test
    void detectsMissingVersionInRequestLine() {
        HttpParser parser = new HttpParser();
        String request = "GET ONLY_TWO_PARTS\r\n\r\n";

        HttpParser.State state = parser.consume(request);

        assertEquals(HttpParser.State.ERROR, state);
    }

    @Test
    void detectsMalformedHeaderMissingColon() {
        HttpParser parser = new HttpParser();
        String request = "GET / HTTP/1.1\r\n" +
                "HeaderWithoutColon\r\n" +
                "\r\n";

        HttpParser.State state = parser.consume(request);

        assertEquals(HttpParser.State.ERROR, state);
    }

    @Test
    void detectsInvalidContentLength() {
        HttpParser parser = new HttpParser();
        String request = "POST / HTTP/1.1\r\n" +
                "Content-Length: abc\r\n" +
                "\r\n";

        HttpParser.State state = parser.consume(request);

        assertEquals(HttpParser.State.ERROR, state);
    }

    // ── Incremental / Chunked Feeding ──────────────────────────────────────

    @Test
    void handlesIncrementalRequestLine() {
        HttpParser parser = new HttpParser();

        assertEquals(HttpParser.State.REQUEST_LINE, parser.consume("GET / HT"));
        assertEquals(HttpParser.State.REQUEST_LINE, parser.consume("TP/1."));
        assertEquals(HttpParser.State.COMPLETE, parser.consume("1\r\nHost: localhost\r\n\r\n"));

        assertEquals("GET", parser.getMethod());
        assertEquals("/", parser.getUri());
    }

    @Test
    void handlesIncrementalHeaders() {
        HttpParser parser = new HttpParser();

        parser.consume("GET / HTTP/1.1\r\n");
        assertEquals(HttpParser.State.HEADERS, parser.getState());

        parser.consume("Host: localhost\r\n");
        assertEquals(HttpParser.State.HEADERS, parser.getState());

        parser.consume("\r\n");
        assertEquals(HttpParser.State.COMPLETE, parser.getState());
    }

    @Test
    void handlesIncrementalBody() {
        HttpParser parser = new HttpParser();
        String body = "HelloWorld";

        parser.consume("POST / HTTP/1.1\r\nContent-Length: " + body.length() + "\r\n\r\n");
        assertEquals(HttpParser.State.BODY, parser.getState());

        parser.consume("Hello");
        assertEquals(HttpParser.State.BODY, parser.getState());

        parser.consume("World");
        assertEquals(HttpParser.State.COMPLETE, parser.getState());

        assertEquals(body, parser.getBody());
    }

    @Test
    void handlesByteByByteBody() {
        HttpParser parser = new HttpParser();
        String body = "AB";

        parser.consume("POST / HTTP/1.1\r\nContent-Length: 2\r\n\r\n");
        assertEquals(HttpParser.State.BODY, parser.getState());

        parser.consume("A");
        assertEquals(HttpParser.State.BODY, parser.getState());

        parser.consume("B");
        assertEquals(HttpParser.State.COMPLETE, parser.getState());

        assertEquals(body, parser.getBody());
    }

    // ── Reset ──────────────────────────────────────────────────────────────

    @Test
    void resetsToInitialState() {
        HttpParser parser = new HttpParser();
        parser.consume("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertEquals(HttpParser.State.COMPLETE, parser.getState());

        parser.reset();

        assertEquals(HttpParser.State.REQUEST_LINE, parser.getState());
        assertNull(parser.getMethod());
        assertNull(parser.getUri());
        assertTrue(parser.getHeaders().isEmpty());
        assertTrue(parser.getBody().isEmpty());
    }

    @Test
    void canReuseParserAfterReset() {
        HttpParser parser = new HttpParser();

        parser.consume("GET /first HTTP/1.1\r\nHost: a\r\n\r\n");
        assertEquals(HttpParser.State.COMPLETE, parser.getState());
        assertEquals("/first", parser.getUri());

        parser.reset();

        parser.consume("GET /second HTTP/1.1\r\nHost: b\r\n\r\n");
        assertEquals(HttpParser.State.COMPLETE, parser.getState());
        assertEquals("/second", parser.getUri());
    }

    // ── Edge Cases ─────────────────────────────────────────────────────────

    @Test
    void consumeAfterCompleteIsNoOp() {
        HttpParser parser = new HttpParser();
        parser.consume("GET / HTTP/1.1\r\n\r\n");
        assertEquals(HttpParser.State.COMPLETE, parser.getState());

        // Calling consume again should not change state
        parser.consume("extra data");
        assertEquals(HttpParser.State.COMPLETE, parser.getState());
    }

    @Test
    void consumeAfterErrorIsNoOp() {
        HttpParser parser = new HttpParser();
        parser.consume("GET\r\n");
        assertEquals(HttpParser.State.ERROR, parser.getState());

        parser.consume("more data");
        assertEquals(HttpParser.State.ERROR, parser.getState());
    }

    @Test
    void handlesEmptyInput() {
        HttpParser parser = new HttpParser();
        HttpParser.State state = parser.consume("");
        assertEquals(HttpParser.State.REQUEST_LINE, state);
    }

    @Test
    void handlesRequestWithoutHeaders() {
        HttpParser parser = new HttpParser();
        String request = "GET / HTTP/1.1\r\n\r\n";

        parser.consume(request);

        assertEquals(HttpParser.State.COMPLETE, parser.getState());
        assertTrue(parser.getHeaders().isEmpty());
    }

    // ── toString ───────────────────────────────────────────────────────────

    @Test
    void toStringIncludesParsedData() {
        HttpParser parser = new HttpParser();
        parser.consume("GET /test HTTP/1.1\r\nHost: x\r\n\r\n");

        String str = parser.toString();
        assertTrue(str.contains("COMPLETE"));
        assertTrue(str.contains("GET"));
        assertTrue(str.contains("/test"));
    }
}
