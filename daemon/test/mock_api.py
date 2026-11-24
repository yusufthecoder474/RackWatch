#!/usr/bin/env python3
"""Minimal mock of POST /api/v1/readings for the daemon smoke test.

Environment:
  MOCK_PORT        port to listen on (default 18080)
  MOCK_STATUS_FILE a file holding the status code to return; re-read on
                   every request, so the smoke test can flip 200 -> 500
                   -> 200 without restarting the mock
  MOCK_STATUS      fallback status when the file is absent (default 200)
  MOCK_LOG         file to append one received body per line
  MOCK_REQUIRE_KEY expected X-API-Key value; empty means "any key is fine"

Only stdlib is used. Any path other than /api/v1/readings gets a 404.
"""

import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("MOCK_PORT", "18080"))
STATUS_FILE = os.environ.get("MOCK_STATUS_FILE", "")
DEFAULT_STATUS = int(os.environ.get("MOCK_STATUS", "200"))
LOG = os.environ.get("MOCK_LOG", "")
REQUIRE_KEY = os.environ.get("MOCK_REQUIRE_KEY", "")


def current_status():
    if STATUS_FILE:
        try:
            with open(STATUS_FILE, encoding="utf-8") as f:
                return int(f.read().strip())
        except (OSError, ValueError):
            pass
    return DEFAULT_STATUS


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # keep stdout clean
        pass

    def do_POST(self):  # noqa: N802 - BaseHTTPRequestHandler naming
        if self.path != "/api/v1/readings":
            self.send_response(404)
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", "0") or "0")
        body = self.rfile.read(length) if length else b""
        if REQUIRE_KEY and self.headers.get("X-API-Key") != REQUIRE_KEY:
            self.send_response(401)
            self.end_headers()
            return
        if LOG:
            with open(LOG, "a", encoding="utf-8") as f:
                f.write(body.decode("utf-8", "replace") + "\n")
        self.send_response(current_status())
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"ok"}')


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
