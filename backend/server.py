#!/usr/bin/env python3
import http.server
import json
import logging
import sys

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("echo-server")


class EchoHandler(http.server.BaseHTTPRequestHandler):
    """Logs every request and echoes back the request content."""

    def do_GET(self):
        body = json.dumps({
            "method": "GET",
            "path": self.path,
            "headers": dict(self.headers),
        }).encode("utf-8")
        self._respond(body)

    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(content_length) if content_length else b""
        body = json.dumps({
            "method": "POST",
            "path": self.path,
            "headers": dict(self.headers),
            "body": raw.decode("utf-8", errors="replace"),
        }).encode("utf-8")
        self._respond(body)

    def do_PUT(self):
        content_length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(content_length) if content_length else b""
        body = json.dumps({
            "method": "PUT",
            "path": self.path,
            "headers": dict(self.headers),
            "body": raw.decode("utf-8", errors="replace"),
        }).encode("utf-8")
        self._respond(body)

    def do_DELETE(self):
        body = json.dumps({
            "method": "DELETE",
            "path": self.path,
            "headers": dict(self.headers),
        }).encode("utf-8")
        self._respond(body)

    def _respond(self, response_body: bytes):
        logger.info("─" * 60)
        logger.info("Incoming request:")
        logger.info("  %s %s", self.command, self.path)
        logger.info("  Headers: %s", dict(self.headers))
        logger.info("  Response body: %s", response_body.decode("utf-8"))

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response_body)))
        self.end_headers()
        self.wfile.write(response_body)

    def log_message(self, format, *args):
        logger.info("  %s", format % args)


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "0.0.0.0"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8080

    server = http.server.HTTPServer((host, port), EchoHandler)
    logger.info("Echo server listening on %s:%s", host, port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("Shutting down…")
        server.server_close()


if __name__ == "__main__":
    main()
