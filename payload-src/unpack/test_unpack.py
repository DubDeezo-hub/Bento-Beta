"""Tests crimson-unpack (the Linux build): streams folders through it and checks every byte, and that bad paths and
destinations are refused. Run: python3 test_unpack.py path/to/unpack-linux"""

import os
import random
import socket
import struct
import subprocess
import sys
import tempfile
import time


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def connect(port):
    for _ in range(50):
        try:
            return socket.create_connection(("127.0.0.1", port), timeout=10)
        except OSError:
            time.sleep(0.1)
    raise RuntimeError("the payload does not answer")


def reply(sock):
    data = b""
    while not data.endswith(b"\n"):
        chunk = sock.recv(1)
        if not chunk:
            break
        data += chunk
    return data.decode()


def record(kind, path, data=None):
    raw = path.encode()
    out = kind.encode() + struct.pack(">H", len(raw)) + raw
    if kind == "F":
        out += struct.pack(">Q", len(data)) + data
    return out


def send_folder(port, dest, records):
    s = connect(port)
    s.sendall(b"CRIMSON-UNPACK 1\n" + f"DEST {dest}\n".encode() + b"".join(records) + b"E")
    answer = reply(s)
    s.close()
    return answer


def main(binary):
    port = free_port()
    env = dict(os.environ, UNPACK_PORT=str(port))
    proc = subprocess.Popen([binary], env=env, stdout=subprocess.PIPE)
    failures = 0

    def check(name, ok, detail=""):
        nonlocal failures
        print(("ok     " if ok else "FAILED ") + name + ("" if ok else f" -- {detail}"))
        if not ok:
            failures += 1

    try:
        s = connect(port)
        s.sendall(b"PING\n")
        check("answers PING", reply(s) == "CRIMSON-UNPACK 1\n")
        s.close()

        root = tempfile.mkdtemp()
        dest = os.path.join(root, "Mods")
        rng = random.Random(7)
        files = {
            "a.txt": b"hello\n",
            "empty.bin": b"",
            "sub/deep/b.bin": bytes(rng.getrandbits(8) for _ in range(300_000)),
            "sub/c d (1).txt": "naïve ünïcode ✓".encode(),
        }
        files.update({f"many/f{i:04d}.dat": os.urandom(rng.randint(0, 2000)) for i in range(500)})
        big = os.urandom(5 * 1024 * 1024 + 17)
        files["big/game.bin"] = big
        recs = [record("D", "empty-folder"), record("D", "sub/deep")]
        recs += [record("F", p, d) for p, d in files.items()]
        start = time.time()
        answer = send_folder(port, dest, recs)
        took = time.time() - start
        total = sum(len(d) for d in files.values())
        check("unpacks a folder", answer == f"OK {len(files)} {total}\n", answer)
        bad = [p for p, d in files.items() if open(os.path.join(dest, p), "rb").read() != d]
        check("every file has the right bytes", not bad, bad[:5])
        check("empty folders are made", os.path.isdir(os.path.join(dest, "empty-folder")))
        print(f"       {len(files)} files, {total / 1e6:.1f} MB in {took:.2f} s")

        # A file sent again replaces the old one
        answer = send_folder(port, dest, [record("F", "a.txt", b"new")])
        check("replaces a file", answer == "OK 1 3\n" and open(os.path.join(dest, "a.txt"), "rb").read() == b"new", answer)

        # Nothing may land outside the destination
        for bad_path in ["../escape.txt", "/etc/passwd", "a/../../x", "./x", "a//b", "a\\b"]:
            answer = send_folder(port, dest, [record("F", bad_path, b"x")])
            check(f"refuses the path {bad_path!r}", answer.startswith("ERR"), answer)
        check("nothing escaped", not os.path.exists(os.path.join(root, "escape.txt")))

        # Bad starts
        s = connect(port)
        s.sendall(b"CRIMSON-UNPACK 9\n")
        check("refuses an unknown version", reply(s).startswith("ERR"))
        s.close()
        answer = send_folder(port, "relative/path", [])
        check("refuses a relative destination", answer.startswith("ERR"), answer)
        answer = send_folder(port, dest + "/..", [])
        check("refuses a destination with ..", answer.startswith("ERR"), answer)

        # A phone that goes away in the middle doesn't stop the payload
        s = connect(port)
        s.sendall(b"CRIMSON-UNPACK 1\n" + f"DEST {dest}\n".encode() + b"F" + struct.pack(">H", 5) + b"x.bin" + struct.pack(">Q", 1000) + b"abc")
        s.close()
        time.sleep(0.3)
        s = connect(port)
        s.sendall(b"PING\n")
        check("keeps going after a cut connection", reply(s) == "CRIMSON-UNPACK 1\n")
        s.close()

        # A second copy finds the port taken and quits quietly
        second = subprocess.run([binary], env=env, timeout=10)
        check("a second copy quits", second.returncode == 0)
    finally:
        proc.kill()
    print(f"{failures} failed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
