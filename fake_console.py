#!/usr/bin/env python3
"""Fake console for testing the Bento app without a PS5.

Run it in Termux on the same phone. In the app, tap the card at the top and enter 127.0.0.1
as the console's IP address; the three lights on the card turn green once this is running.
It stands in for both consoles: a PS5 (etaHEN) and a PS4 (GoldHEN) reach the same ports here.
  Install tab : port 12800 -> mimics etaHEN (/upload) and the Remote Package Installer (/api/install),
                prints the URL. On a PS5 the installer is etaHEN's; on a PS4 it is flatz's RPI app.
                When the URL comes from "Install from this phone" (the phone's own file server), this fake
                console downloads it like a console would (HEAD, a few byte ranges, then the whole file, up to
                64 MB; set FAKE_PS5_FULL=1 for no limit) and prints the result: "self-test PASSED".
  Payload tab : port 9021 (PS5 etaHEN) and port 9090 (PS4 GoldHEN) -> saves received bytes to
                ~/fake_ps5/payloads/. The loader port on the console card decides which one the app uses.
  Upload tab  : port 2121  -> user "anonymous", empty password, remote folder /data
                              files land in ~/fake_ps5/ftp/data/
  Files tab   : same FTP server (2121). A few sample folders and files are created in
                ~/fake_ps5/ftp/ so there is something to open, rename, download and delete.
                Downloads from the app land in the phone's Downloads/PS5 folder.
  Memory tab  : port 7744 -> mimics ps5debug-NG (the real one is on port 744, but Android does not let an app
                like Termux open a port below 1024, so set the Memory tab's port to 7744 for this fake).
                It runs a pretend game, "Fake Game" (pid 91), whose health counts down every few seconds,
                so there is something to find and change.
Setup:  pkg install python && pip install pyftpdlib
"""
import http.client
import ipaddress
import json
import logging
import os
import socketserver
import struct
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


# ---------------------------------------------------------------------------------------------
# Fake ps5debug-NG (the Memory tab). Speaks the same wire protocol as the real debugger payload, for the commands
# the app uses. Status words are sent already bit-swapped, like the real server does.
# ---------------------------------------------------------------------------------------------

DEBUG_PORT = int(os.environ.get("FAKE_DEBUG_PORT", "7744"))
DBG_MAGIC = 0xFFAABBCC
DBG_OK = 0x80000000
DBG_ERROR = 0xF0000001
GAME_PID = 91


class FakeGame:
    """A pretend game's memory: an executable region and a heap with a few values worth cheating on."""

    HEALTH = 0x20001234  # int32, counts down by 7 every 4 seconds, back to 100 when it reaches 0
    GOLD = 0x20002000  # int32, 500
    STAMINA = 0x20003000  # float, 1.0

    def __init__(self):
        self.lock = threading.Lock()
        exe = bytearray(0x4000)
        exe[0:16] = b"\x7fELF\x02\x01\x01\x09" + bytes(8)
        exe[0x100:0x110] = b"Fake Game v1.00\x00"
        heap = bytearray(0x10000)
        for i in range(0, 0x10000, 4):  # some noise so not everything is zero
            heap[i:i + 4] = ((i * 2654435761) & 0xFF).to_bytes(4, "little")
        self.regions = [  # name, start, bytes, prot
            ["executable", 0x00400000, exe, 5],
            ["heap", 0x20000000, heap, 3],
        ]
        self.put(self.HEALTH, (100).to_bytes(4, "little"))
        self.put(self.GOLD, (500).to_bytes(4, "little"))
        self.put(self.STAMINA, struct.pack("<f", 1.0))
        threading.Thread(target=self.take_damage, daemon=True).start()

    def find(self, addr, length):
        for r in self.regions:
            if r[1] <= addr and addr + length <= r[1] + len(r[2]):
                return r
        return None

    def get(self, addr, length):
        with self.lock:
            r = self.find(addr, length)
            return None if r is None else bytes(r[2][addr - r[1]:addr - r[1] + length])

    def put(self, addr, data):
        with self.lock:
            r = self.find(addr, len(data))
            if r is None:
                return False
            r[2][addr - r[1]:addr - r[1] + len(data)] = data
            return True

    def take_damage(self):
        while True:
            time.sleep(4)
            hp = int.from_bytes(self.get(self.HEALTH, 4), "little", signed=True)
            hp = 100 if hp <= 7 else hp - 7
            self.put(self.HEALTH, hp.to_bytes(4, "little", signed=True))


FAKE_GAME = None
FAKE_PROCESSES = [("kernel", 0), ("init", 1), ("SceSysCore.elf", 30), ("SceShellCore", 47),
                  ("SceShellUI", 48), ("SceAudioOut", 60), ("eboot.bin", GAME_PID)]


def fixed(text, size):
    return text.encode()[:size].ljust(size, b"\x00")


class DebugHandler(socketserver.BaseRequestHandler):
    def recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.request.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("closed")
            buf += chunk
        return buf

    def u32(self, v):
        self.request.sendall(struct.pack("<I", v & 0xFFFFFFFF))

    def handle(self):
        game = FAKE_GAME
        try:
            while True:
                magic, cmd, datalen = struct.unpack("<III", self.recv_exact(12))
                if magic != DBG_MAGIC:
                    continue  # the real server silently drops these too
                body = self.recv_exact(datalen) if datalen else b""
                send = self.request.sendall
                if cmd == 0xBD000001:  # version
                    self.u32(3)
                    send(b"1.3")
                elif cmd == 0xBD000500:  # firmware
                    send(struct.pack("<H", 1040))
                elif cmd == 0xBD000501:  # branding
                    text = b"ps5debug-NG by OSR v1.3.2 [fake console]\x001.1"
                    self.u32(len(text))
                    send(text)
                elif cmd in (0xBDAACC06, 0xBDDD0005):  # nop, info
                    self.u32(DBG_OK)
                elif cmd == 0xBDAA0001:  # process list
                    self.u32(DBG_OK)
                    self.u32(len(FAKE_PROCESSES))
                    send(b"".join(fixed(n, 32) + struct.pack("<i", p) for n, p in FAKE_PROCESSES))
                elif cmd == 0xBDDD0006:  # foreground app
                    self.u32(DBG_OK)
                    send(struct.pack("<I", GAME_PID) + fixed("PPSA01234", 16) + fixed("UP0000-PPSA01234_00-FAKEGAME00000000", 64)
                         + fixed("Fake Game", 40) + fixed("01.000", 16))
                elif cmd == 0xBDAA0004:  # memory maps
                    (pid,) = struct.unpack("<I", body[:4])
                    regions = game.regions if pid == GAME_PID else []
                    self.u32(DBG_OK)
                    self.u32(len(regions))
                    for name, start, data, prot in regions:
                        send(fixed(name, 32) + struct.pack("<QQQH", start, start + len(data), 0, prot))
                elif cmd == 0xBDAA0002:  # read
                    pid, addr, length = struct.unpack("<IQI", body[:16])
                    data = game.get(addr, length) if pid == GAME_PID else None
                    self.u32(DBG_OK)  # like the real one: always success, and zeros where nothing is mapped
                    send(data if data is not None else bytes(length))
                elif cmd == 0xBDAA0003:  # write: ack, then the bytes, then the result
                    pid, addr, length = struct.unpack("<IQI", body[:16])
                    self.u32(DBG_OK)
                    data = self.recv_exact(length)
                    ok = pid == GAME_PID and game.put(addr, data)
                    if ok:
                        print(f"[memory] wrote {length} bytes at 0x{addr:X}: {data.hex(' ')}", flush=True)
                    self.u32(DBG_OK if ok else DBG_ERROR)
                elif cmd == 0xBDDD0004:  # notify: the text follows the 8-byte body
                    _, length = struct.unpack("<II", body[:8])
                    text = self.recv_exact(length).rstrip(b"\x00").decode(errors="replace")
                    print(f"[memory] notification on the console: {text}", flush=True)
                    self.u32(DBG_OK)
                else:
                    print(f"[memory] command 0x{cmd:08X} is not faked; answering with an error", flush=True)
                    self.u32(DBG_ERROR)
        except (ConnectionError, OSError):
            return


def start_fake_debugger():
    global FAKE_GAME
    FAKE_GAME = FakeGame()
    try:
        server = ReusableTCPServer(("0.0.0.0", DEBUG_PORT), DebugHandler)
    except OSError as e:
        print(f"[memory] could not open port {DEBUG_PORT} ({e}); the Memory tab has nothing to talk to", flush=True)
        return
    threading.Thread(target=server.serve_forever, daemon=True).start()


def main():
    start_fake_debugger()
    threading.Thread(
        target=HTTPServer(("0.0.0.0", 12800), InstallHandler).serve_forever, daemon=True
    ).start()
    for loader_port in (9021, 9090):  # 9021 is the PS5's etaHEN loader, 9090 the PS4's GoldHEN loader
        threading.Thread(
            target=ReusableTCPServer(("0.0.0.0", loader_port), PayloadHandler).serve_forever, daemon=True
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
    print(f"Fake console running: install 12800, payload 9021/9090, ftp 2121, memory {DEBUG_PORT}. Ctrl+C to stop.", flush=True)
    FTPServer(("0.0.0.0", 2121), handler).serve_forever()


if __name__ == "__main__":
    main()
