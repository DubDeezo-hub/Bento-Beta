#!/usr/bin/env python3
"""Fake console for testing the Bento app without a PS5.

Run it in Termux on the same phone. In the app, tap the card at the top and enter 127.0.0.1
as the console's IP address; the three lights on the card turn green once this is running.
  Install tab : port 12800 -> mimics etaHEN (/upload) and RPI (/api/install), prints the URL.
                When the URL comes from "Install from this phone" (the phone's own file server), this fake
                console downloads it like a PS5 would (HEAD, a few byte ranges, then the whole file, up to
                64 MB; set FAKE_PS5_FULL=1 for no limit) and prints the result: "self-test PASSED".
  Payload tab : port 9021  -> saves received bytes to ~/fake_ps5/payloads/
  Upload tab  : port 2121  -> user "anonymous", empty password, remote folder /data
                              files land in ~/fake_ps5/ftp/data/
  Files tab   : same FTP server (2121). A few sample folders and files are created in
                ~/fake_ps5/ftp/ so there is something to open, rename, download and delete.
                Downloads from the app land in the phone's Downloads/PS5 folder.
Setup:  pkg install python && pip install pyftpdlib
"""
import http.client
import ipaddress
import json
import logging
import os
import socketserver
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

BASE = os.path.expanduser("~/fake_ps5")
os.makedirs(f"{BASE}/ftp/data", exist_ok=True)
os.makedirs(f"{BASE}/payloads", exist_ok=True)


def seed_samples():
    """Creates a few sample files for the app's Files tab (only the ones that are missing)."""
    samples = {
        "readme.txt": b"Fake PS5 file system for testing the Bento app.\n",
        "data/notes.txt": b"Rename me, download me, or delete me.\n",
        "data/sample.bin": bytes(range(256)) * 1024,
        "user/home/hello.txt": b"A file inside nested folders.\n",
    }
    for rel, content in samples.items():
        path = os.path.join(BASE, "ftp", rel)
        if not os.path.exists(path):
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as f:
                f.write(content)


seed_samples()


def fetch(url, method="GET", rng=None):
    """One request to the phone's file server. Returns (response, connection); the caller reads the body."""
    parts = urllib.parse.urlsplit(url)
    conn = http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=20)
    headers = {"Range": rng} if rng else {}
    conn.request(method, parts.path + (("?" + parts.query) if parts.query else ""), headers=headers)
    return conn.getresponse(), conn


def self_test_download(url):
    """Acts like the PS5 does after it is told to install from a link: asks for the size, for a few byte ranges
    (the way a downloader resumes), and then downloads the file. Prints what it finds."""
    tag = "[install/fetch]"
    try:
        r, c = fetch(url, "HEAD")
        r.read()
        c.close()
        if r.status != 200:
            raise RuntimeError(f"HEAD answered {r.status}")
        size = int(r.getheader("Content-Length"))
        if r.getheader("Accept-Ranges") != "bytes":
            raise RuntimeError("the server does not offer byte ranges (no 'Accept-Ranges: bytes')")
        print(f"{tag} HEAD ok: {size} bytes, ranges offered", flush=True)
        if size == 0:
            raise RuntimeError("the file is empty")

        first = min(16, size)
        r, c = fetch(url, rng=f"bytes=0-{first - 1}")
        body = r.read()
        c.close()
        if r.status != 206 or len(body) != first or r.getheader("Content-Range") != f"bytes 0-{first - 1}/{size}":
            raise RuntimeError(f"range 0-{first - 1}: status {r.status}, {len(body)} bytes, {r.getheader('Content-Range')}")
        print(f"{tag} range from the start ok ({first} bytes)", flush=True)

        tail = min(10, size)
        r, c = fetch(url, rng=f"bytes={size - tail}-")
        body = r.read()
        c.close()
        if r.status != 206 or len(body) != tail or r.getheader("Content-Range") != f"bytes {size - tail}-{size - 1}/{size}":
            raise RuntimeError(f"open-ended range: status {r.status}, {len(body)} bytes, {r.getheader('Content-Range')}")
        print(f"{tag} range to the end ok ({tail} bytes)", flush=True)

        r, c = fetch(url, rng=f"bytes={size}-")
        r.read()
        c.close()
        if r.status != 416:
            raise RuntimeError(f"a range past the end should be answered with 416, got {r.status}")
        print(f"{tag} range past the end refused with 416, as it should be", flush=True)

        limit = None if os.environ.get("FAKE_PS5_FULL") else 64 * 1024 * 1024
        r, c = fetch(url)
        if r.status != 200:
            raise RuntimeError(f"download answered {r.status}")
        got = 0
        started = time.time()
        while True:
            chunk = r.read(1024 * 1024)
            if not chunk:
                break
            got += len(chunk)
            if limit is not None and got >= limit and got < size:
                break
        c.close()
        seconds = max(time.time() - started, 0.001)
        if limit is not None and got < size:
            print(f"{tag} stopped the download after {got} bytes of {size} (test limit; FAKE_PS5_FULL=1 for all)", flush=True)
        elif got != size:
            raise RuntimeError(f"downloaded {got} bytes but the file has {size}")
        else:
            print(f"{tag} downloaded all {got} bytes in {seconds:.1f} s ({got / seconds / 1e6:.1f} MB/s)", flush=True)
        print(f"{tag} self-test PASSED", flush=True)
    except Exception as e:  # noqa: BLE001 - a test tool: report anything
        print(f"{tag} self-test FAILED: {e}", flush=True)


def is_phone_link(url):
    """True for a link to a device on the local network or on this phone (what "Install from this phone" gives);
    links to sites on the internet are only printed, never downloaded."""
    try:
        host = urllib.parse.urlsplit(url).hostname
        ip = ipaddress.ip_address(host)
        return url.startswith("http://") and (ip.is_private or ip.is_loopback)
    except (ValueError, TypeError):
        return False


def start_self_test(url):
    """Runs the self-test beside the install request (a real console also downloads after it has answered)."""
    if is_phone_link(url):
        threading.Thread(target=self_test_download, args=(url,), daemon=True).start()


class InstallHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if self.path == "/upload":  # etaHEN style: multipart form with a "url" field
            i = body.find(b'name="url"')
            j = body.find(b"\r\n\r\n", i) + 4 if i >= 0 else -1
            k = body.find(b"\r\n--", j) if j > 0 else -1
            url = body[j:k].decode(errors="replace") if k > j > 0 else "?"
            print(f"[install/etaHEN] url={url}", flush=True)
            reply = b"SUCCESS: queued"
            to_fetch = [url]
        elif self.path == "/api/install":  # Remote Package Installer style (JSON)
            print(f"[install/RPI] {body.decode(errors='replace')}", flush=True)
            reply = b'{"success":true}'
            try:
                to_fetch = [urllib.parse.unquote(p) for p in json.loads(body).get("packages", [])]
            except Exception:  # noqa: BLE001
                to_fetch = []
        else:
            print(f"[install] {self.path} {body.decode(errors='replace')}", flush=True)
            reply = b"ok"
            to_fetch = []
        self.send_response(200)
        self.end_headers()
        self.wfile.write(reply)
        self.wfile.flush()
        for u in to_fetch:
            start_self_test(u)

    def log_message(self, *args):
        pass


_payload_names = threading.Lock()


def new_payload_path():
    """A file of its own for every payload. A chain with no pause sends several in the same second, so the second one
    gets _2 at the end of its name, the third _3, and so on; nothing is overwritten."""
    stamp = int(time.time())
    with _payload_names:
        n = 1
        while True:
            suffix = "" if n == 1 else f"_{n}"
            path = f"{BASE}/payloads/payload_{stamp}{suffix}.bin"
            try:
                open(path, "xb").close()  # takes the name, so a payload that arrives at the same moment gets the next one
                return path
            except FileExistsError:
                n += 1


class PayloadHandler(socketserver.BaseRequestHandler):
    def handle(self):
        first = self.request.recv(65536)
        if not first:
            return  # the app's status lights connect and disconnect without sending anything
        path = new_payload_path()
        total = 0
        with open(path, "wb") as f:
            chunk = first
            while chunk:
                f.write(chunk)
                total += len(chunk)
                chunk = self.request.recv(65536)
        print(f"[payload] received {total} bytes -> {path}", flush=True)


class ReusableTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True


def main():
    threading.Thread(
        target=HTTPServer(("0.0.0.0", 12800), InstallHandler).serve_forever, daemon=True
    ).start()
    threading.Thread(
        target=ReusableTCPServer(("0.0.0.0", 9021), PayloadHandler).serve_forever, daemon=True
    ).start()

    class QuietChecks(logging.Filter):
        """Hides the connect/disconnect lines of the app's status lights (they never log in)."""

        def filter(self, record):
            return "-[] FTP session" not in record.getMessage()

    logging.getLogger("pyftpdlib").addFilter(QuietChecks())

    authorizer = DummyAuthorizer()
    authorizer.add_anonymous(f"{BASE}/ftp", perm="elradfmwMT")
    handler = FTPHandler
    handler.authorizer = authorizer
    print("Fake console running: install 12800, payload 9021, ftp 2121. Ctrl+C to stop.", flush=True)
    FTPServer(("0.0.0.0", 2121), handler).serve_forever()


if __name__ == "__main__":
    main()
