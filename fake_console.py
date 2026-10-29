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
                It runs a pretend game, "Fake Game" (pid 91): health (4 bytes) at 0x20001234 counts down by 7
                every 4 seconds, gold 500 at 0x20002000, a float 1.0 at 0x20003000. Scans, the scan unlock,
                reads and writes behave like the real debugger, so you can find the health and freeze it.
                Unknown-value scans, pattern search ("Find again") and write-many are faked too.
  Cheat files : http://127.0.0.1:12800/cheats/ as the Memory tab's cheat source serves a cheat file for
                Fake Game: "Infinite Health" stops the health from going down.
Setup:  pkg install python && pip install pyftpdlib
"""
import http.client
import ipaddress
import json
import logging
import os
import random
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


FAKE_CHEAT_INDEX = "PPSA01234_01.000.json=Fake Game\n"
FAKE_CHEAT_FILE = json.dumps({
    "name": "Fake Game", "id": "PPSA01234", "version": "01.000", "process": "eboot.bin",
    "mods": [
        {"name": "Infinite Health", "type": "checkbox",
         "memory": [{"offset": "200", "on": "90909090", "off": "29C88907"}]},
        {"name": "Max Gold", "type": "button",
         "memory": [{"offset": "1FC02000", "on": "9F860100", "off": "F4010000"}]},
        {"name": "Needs a section (not supported)", "type": "checkbox",
         "memory": [{"section": "2", "offset": "10", "on": "90", "off": "00"}]},
    ],
    "credits": ["fake_console.py"],
}, indent=2)


class InstallHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        """Cheat files for the Memory tab: http://127.0.0.1:12800/cheats/ as the cheat source."""
        files = {"/cheats/json.txt": FAKE_CHEAT_INDEX, "/cheats/json/PPSA01234_01.000.json": FAKE_CHEAT_FILE}
        body = files.get(self.path.split("?")[0])
        if body is None:
            self.send_response(404)
            self.end_headers()
            return
        data = body.encode()
        print(f"[cheats] sent {self.path}", flush=True)
        self.send_response(200)
        self.send_header("Content-Type", "application/json" if self.path.endswith(".json") else "text/plain")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

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
    MAX_HEALTH = 0x20001238  # int32 100, right after health (as games often keep them)
    GOLD = 0x20002000  # int32, 500
    STAMINA = 0x20003000  # float, 1.0

    def __init__(self):
        self.lock = threading.Lock()
        exe = bytearray(0x4000)
        exe[0:16] = b"\x7fELF\x02\x01\x01\x09" + bytes(8)
        exe[0x100:0x110] = b"Fake Game v1.00\x00"
        exe[0x200:0x204] = bytes.fromhex("29C88907")  # "sub eax, ecx; mov [rdi], eax": the code that takes damage
        heap = bytearray(0x10000)
        noise = random.Random(1234)  # some small numbers so not everything is zero (the same every run)
        for i in range(0, 0x10000, 4):
            heap[i:i + 4] = noise.randrange(0, 256).to_bytes(4, "little")
        self.regions = [  # name, start, bytes, prot
            ["executable", 0x00400000, exe, 5],
            ["heap", 0x20000000, heap, 3],
        ]
        self.put(self.HEALTH, (100).to_bytes(4, "little"))
        self.put(self.MAX_HEALTH, (100).to_bytes(4, "little"))
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
            if self.get(0x00400200, 1) == b"\x90":  # the "Infinite Health" cheat replaced the code with NOPs
                continue
            hp = int.from_bytes(self.get(self.HEALTH, 4), "little", signed=True)
            hp = 100 if hp <= 7 else hp - 7
            self.put(self.HEALTH, hp.to_bytes(4, "little", signed=True))


FAKE_GAME = None
FAKE_PROCESSES = [("kernel", 0), ("init", 1), ("SceSysCore.elf", 30), ("SceShellCore", 47),
                  ("SceShellUI", 48), ("SceAudioOut", 60), ("eboot.bin", GAME_PID)]


def fixed(text, size):
    return text.encode()[:size].ljust(size, b"\x00")


# Scanning, as ps5debug-NG's scan.c and scan_compare.c do it
SCAN_FORMATS = {0: "<B", 1: "<b", 2: "<H", 3: "<h", 4: "<I", 5: "<i", 6: "<Q", 7: "<q", 8: "<f", 9: "<d"}
NEEDS_VALUE = [1, 1, 1, 1, 1, 0, 1, 0, 1, 0, 0, 0, 1]
NEEDS_EXTRA = [0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0]
NEEDS_PREVIOUS = [0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0]


def wrap_int(value, fmt):
    """What C does when a sum overflows the value type."""
    if fmt[-1] in "fd":
        return value
    bits = struct.calcsize(fmt) * 8
    value &= (1 << bits) - 1
    if fmt[-1].islower() and value >= 1 << (bits - 1):
        value -= 1 << bits
    return value


def scan_compare(cmp, vtype, scan, mem, prev):
    fmt = SCAN_FORMATS[vtype]
    m = struct.unpack(fmt, mem)[0]
    if cmp == 0:
        if vtype in (8, 9):
            sv = struct.unpack(fmt, scan)[0]
            if scan == bytes(len(scan)):
                return mem == bytes(len(mem))
            return abs(sv - m) <= abs(sv) * 1e-6  # close to the server's fuzzy compare
        return scan == mem
    if cmp in (2, 3):
        sv = struct.unpack(fmt, scan)[0]
        return m > sv if cmp == 2 else m < sv
    if prev is None:
        return False
    if cmp == 4:
        lo, hi = struct.unpack(fmt, scan)[0], struct.unpack(fmt, prev)[0]
        return lo <= m <= hi if hi > lo else hi <= m <= lo
    p = struct.unpack(fmt, prev)[0]
    if cmp == 5:
        return m > p
    if cmp == 7:
        return m < p
    if cmp in (6, 8):
        d = struct.unpack(fmt, scan)[0]
        return m == wrap_int(p + d if cmp == 6 else p - d, fmt)
    if cmp == 9:
        return m != p
    if cmp == 10:
        return m == p
    return False


def lfsr_keystream():
    s1, s2, s3, s4 = 200, 300, 400, 500
    out = bytearray()
    for _ in range(256):
        s1 = ((s1 << 18) & 0xFFF80000) ^ (((s1 ^ (s1 << 6)) & 0xFFFFFFFF) >> 13)
        s2 = ((s2 << 2) & 0xFFFFFFE0) ^ (((s2 ^ (s2 << 2)) & 0xFFFFFFFF) >> 27)
        s3 = ((s3 << 7) & 0xFFFFF800) ^ (((s3 ^ (s3 << 13)) & 0xFFFFFFFF) >> 21)
        s4 = ((s4 << 13) & 0xFFF00000) ^ (((s4 ^ (s4 << 3)) & 0xFFFFFFFF) >> 12)
        out.append((s1 ^ s2 ^ s3 ^ s4) & 0xFF)
    return bytes(out)


KEYSTREAM = lfsr_keystream()
SENTINEL = b"\xff" * 8


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
                    changed = game.get(addr, length) != data
                    ok = pid == GAME_PID and game.put(addr, data)
                    if ok and changed:  # a frozen value is written again and again; only print real changes
                        print(f"[memory] wrote {length} bytes at 0x{addr:X}: {data.hex(' ')}", flush=True)
                    self.u32(DBG_OK if ok else DBG_ERROR)
                elif cmd == 0xBDDD0004:  # notify: the text follows the 8-byte body
                    _, length = struct.unpack("<II", body[:8])
                    text = self.recv_exact(length).rstrip(b"\x00").decode(errors="replace")
                    print(f"[memory] notification on the console: {text}", flush=True)
                    self.u32(DBG_OK)
                elif cmd == 0xBDAACCFF:  # scan unlock: challenge, answer = challenge XOR keystream
                    magic2, flags = struct.unpack("<II", body[:8])
                    if magic2 != 0xBB40E64D:
                        self.u32(0xF0000003)
                        continue
                    self.u32(DBG_OK)
                    challenge = os.urandom(64)
                    send(struct.pack("<H", 64) + challenge)
                    answer = self.recv_exact(64)
                    good = answer == bytes(c ^ KEYSTREAM[i] for i, c in enumerate(challenge))
                    if good and flags & 2:
                        self.unlocked = True
                    self.u32(DBG_OK if good else 0xF0000003)
                elif cmd == 0xBDAA000D:  # first map: where the game is loaded
                    (pid,) = struct.unpack("<I", body[:4])
                    self.u32(DBG_OK)
                    send(struct.pack("<Q", game.regions[0][1] if pid == GAME_PID else 0))
                elif cmd == 0xBDAACC04:  # write many: ack, entries, then the result
                    pid, count, flags = struct.unpack("<III", body[:12])
                    self.u32(DBG_OK)
                    ok = True
                    for _ in range(count):
                        addr, length = struct.unpack("<QI", self.recv_exact(12))
                        data = self.recv_exact(length)
                        ok = (pid == GAME_PID and game.put(addr, data)) and ok
                    self.u32(DBG_OK if ok else DBG_ERROR)
                elif cmd == 0xBDAA0501:  # pattern search: the address if it occurs exactly once
                    pid, addr, length, max_matches, stop, plen = struct.unpack("<IQIBBI", body[:22])
                    self.u32(DBG_OK)
                    pattern = self.recv_exact(plen)
                    mask = self.recv_exact(plen)
                    self.u32(DBG_OK)
                    mem = (game.get(addr, length) if pid == GAME_PID else None) or bytes(length)
                    keep = [i for i in range(plen) if mask[i] == 1]
                    found = [j for j in range(0, length - plen + 1) if all(mem[j + i] == pattern[i] for i in keep)]
                    send(struct.pack("<Q", addr + found[0] if len(found) == 1 else 0))
                    self.u32(DBG_OK)
                elif cmd == 0xBDAACC10:  # turbo scan engines: resident, snapshot, segments
                    self.u32(DBG_OK)
                    send(struct.pack("<IIII", 1, 0x04 | 0x08 | 0x10, 1, 0))
                elif cmd == 0xBDAACC16:  # region speeds
                    pid = struct.unpack("<I", body[:4])[0]
                    if not getattr(self, "unlocked", False):
                        self.u32(0xF0000003)
                        continue
                    regions = game.regions if pid == GAME_PID else []
                    self.u32(DBG_OK)
                    self.u32(len(regions))
                    for name, start, data, prot in regions:
                        send(struct.pack("<QQIIII", start, start + len(data), prot, 0, 2000, 0))
                    self.u32(DBG_OK)
                elif cmd == 0xBDAACC11:  # turbo first scan (only the snapshot kind is faked)
                    self.turbo_start(game, body)
                elif cmd == 0xBDAACC12:  # turbo next scan of the kept search
                    self.turbo_count(game, body)
                elif cmd == 0xBDAACC13:  # survivors of the kept search
                    start, count, _ = struct.unpack("<III", body[:12])
                    sess = getattr(self, "session", None)
                    self.u32(DBG_OK)
                    if not sess:
                        self.u32(0)
                    else:
                        size, items = sess
                        part = items[start:start + count]
                        self.u32(len(part))
                        send(b"".join(struct.pack("<Q", a) + cur + prev for a, cur, prev in part))
                    self.u32(DBG_OK)
                elif cmd == 0xBDAACC14:  # end the kept search
                    self.session = None
                    self.u32(DBG_OK)
                elif cmd == 0xBDAACC01:  # first scan
                    self.scan_start(game, body)
                elif cmd == 0xBDAACC02:  # next scan
                    self.scan_count(game, body)
                elif cmd == 0xBDAACC03:  # read many
                    pid, count = struct.unpack("<II", body[:8])
                    if not getattr(self, "unlocked", False):
                        self.u32(0xF0000003)
                        continue
                    self.u32(DBG_OK)
                    entries = self.recv_exact(12 * count)
                    for i in range(count):
                        addr, length = struct.unpack("<QI", entries[12 * i:12 * i + 12])
                        data = game.get(addr, length) if pid == GAME_PID else None
                        send(data if data is not None else bytes(length))
                    send(SENTINEL)
                else:
                    print(f"[memory] command 0x{cmd:08X} is not faked; answering with an error", flush=True)
                    self.u32(DBG_ERROR)
        except (ConnectionError, OSError):
            return


def _send_blocks(send, records):
    """Result blocks: u64 length + records, at most 0x3FFE8 bytes each, then the sentinel."""
    block = bytearray()
    for r in records:
        if len(block) + len(r) > 0x3FFE8:
            send(struct.pack("<Q", len(block)) + block)
            block = bytearray()
        block += r
    if block:
        send(struct.pack("<Q", len(block)) + block)
    send(SENTINEL)


def _scan_start(self, game, body):
    pid, address, length, vtype, cmp, align, len_data = struct.unpack("<IQIBBBI", body[:23])
    if not getattr(self, "unlocked", False) or cmp > 12 or len_data == 0 or vtype not in SCAN_FORMATS:
        self.u32(0xF0000003)
        return
    size = struct.calcsize(SCAN_FORMATS[vtype])
    needs = ((1 << cmp) & 0x114F) != 0 or cmp == 4
    self.u32(DBG_OK)
    pattern = self.recv_exact(len_data) if needs else b""
    self.u32(DBG_OK)
    step = align or size
    mem = (game.get(address, length) if pid == GAME_PID else None) or bytes(length)
    scan = pattern[:size]
    hi = pattern[size:2 * size] if cmp == 4 else None
    records = []
    for j in range(0, length - size + 1, step):
        v = mem[j:j + size]
        if scan_compare(cmp, vtype, scan, v, hi):
            records.append(struct.pack("<I", j) + v)
    _send_blocks(self.request.sendall, records)
    self.u32(DBG_OK)


def _scan_count(self, game, body):
    pid, base, vtype, cmp, len_data = struct.unpack("<IQBBI", body[:18])
    if not getattr(self, "unlocked", False) or cmp > 12 or vtype not in SCAN_FORMATS:
        self.u32(0xF0000003)
        return
    size = struct.calcsize(SCAN_FORMATS[vtype])
    self.u32(DBG_OK)
    needs = NEEDS_VALUE[cmp] or NEEDS_EXTRA[cmp]
    pattern = self.recv_exact(len_data) if needs and len_data else b""
    with_prev = NEEDS_PREVIOUS[cmp]
    entry = 4 + size if with_prev else 4
    scan = pattern[:size]
    hi = pattern[size:2 * size] if cmp == 4 and len(pattern) >= 2 * size else None
    while True:
        (chunk_len,) = struct.unpack("<I", self.recv_exact(4))
        if chunk_len == 0xFFFFFFFF:
            break
        chunk = self.recv_exact(chunk_len)
        records = []
        for off in range(0, chunk_len - entry + 1, entry):
            (o,) = struct.unpack("<I", chunk[off:off + 4])
            prev = chunk[off + 4:off + 4 + size] if with_prev else hi
            v = (game.get(base + o, size) if pid == GAME_PID else None) or bytes(size)
            if scan_compare(cmp, vtype, scan, v, prev):
                records.append(struct.pack("<I", o) + v)
        _send_blocks(self.request.sendall, records)
    self.u32(DBG_OK)


def _turbo_start(self, game, body):
    pid, address, length, vtype, cmp, align, len_data, flags = struct.unpack("<IQIBBBII", body[:27])
    if not getattr(self, "unlocked", False) or not flags & 0x04 or vtype not in SCAN_FORMATS:
        self.u32(0xF0000003)
        return
    size = struct.calcsize(SCAN_FORMATS[vtype])
    self.u32(DBG_OK)
    self.u32(DBG_OK)
    segs = []
    if flags & 0x10:
        (n,) = struct.unpack("<I", self.recv_exact(4))
        for _ in range(n):
            segs.append(struct.unpack("<QI", self.recv_exact(12)))
    else:
        segs = [(address, length)]
    total = sum(l for _, l in segs)
    send = self.request.sendall
    send(struct.pack("<QQ", total // size, total))
    items = []
    done = 0
    for a, l in segs:
        mem = (game.get(a, l) if pid == GAME_PID else None) or bytes(l)
        for j in range(0, l - size + 1, align or size):
            v = mem[j:j + size]
            if v != bytes(size):  # the real one leaves out zeros too
                items.append((a + j, v, v))
        done += l
        send(struct.pack("<Q", done))
    send(SENTINEL)
    self.session = (size, items)
    print(f"[memory] snapshot of {len(items)} values", flush=True)
    send(struct.pack("<IQ", 1, len(items)))
    self.u32(DBG_OK)


def _turbo_count(self, game, body):
    pid, base, vtype, cmp, len_data, flags = struct.unpack("<IQBBII", body[:22])
    sess = getattr(self, "session", None)
    if not getattr(self, "unlocked", False) or vtype not in SCAN_FORMATS:
        self.u32(0xF0000003)
        return
    self.u32(DBG_OK)
    needs = NEEDS_VALUE[cmp] or NEEDS_EXTRA[cmp]
    pattern = self.recv_exact(len_data) if needs and len_data else b""
    send = self.request.sendall
    if not sess:
        send(SENTINEL + struct.pack("<Q", 0))
        self.u32(DBG_OK)
        return
    size, items = sess
    scan = pattern[:size]
    hi = pattern[size:2 * size] if cmp == 4 else None
    kept = []
    for a, cur, _ in items:
        now = (game.get(a, size) if pid == GAME_PID else None) or bytes(size)
        if scan_compare(cmp, vtype, scan, now, hi if cmp == 4 else cur):
            kept.append((a, now, cur))
    self.session = (size, kept)
    send(SENTINEL + struct.pack("<Q", len(kept)))
    self.u32(DBG_OK)


DebugHandler.turbo_start = _turbo_start
DebugHandler.turbo_count = _turbo_count
DebugHandler.scan_start = _scan_start
DebugHandler.scan_count = _scan_count


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
