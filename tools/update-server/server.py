#!/usr/bin/env python3
import os
from http.server import HTTPServer, SimpleHTTPRequestHandler
from socketserver import ThreadingMixIn


class UpdateHandler(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    os.chdir("/var/www")
    server = ThreadingHTTPServer(("0.0.0.0", 80), UpdateHandler)
    server.serve_forever()


if __name__ == "__main__":
    main()
