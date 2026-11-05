/* crimson-unpack: receives a whole folder from Crimson Multitool over one connection and writes it in place.

   A folder of many small files over FTP costs a few round trips per file. Here the phone sends every folder and file
   one after the other on a single TCP stream, and this payload writes them as they arrive, so a folder goes about as
   fast as one big file. Nothing is stored in between (no archive on the console).

   It only uses ordinary socket and file calls: no kernel patches, no system folders. Files are only written under
   /data, /mnt/usb* and /mnt/ext* (a Linux build for the tests can be allowed anywhere with -DUNPACK_ANY_DEST).

   Protocol, version 1 (all numbers big-endian):
     PING:   the client sends "PING\n", the payload answers "CRIMSON-UNPACK 1\n" and hangs up.
     Folder: the client sends "CRIMSON-UNPACK 1\n", then "DEST <absolute folder>\n", then records:
               'D' u16 length, path               a folder (made with its parents)
               'F' u16 length, path, u64 size, data   a file (made or replaced)
               'E'                                the end
             The payload answers "OK <files> <bytes>\n" after 'E', or "ERR <reason>\n" at the first problem and hangs up.
     Paths are relative to DEST, "/"-separated, without "." or ".." parts, at most 1024 bytes.

   Port 9046. A second copy started while one runs finds the port taken and quits. */

#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define UNPACK_PORT 9046
#define UNPACK_HELLO "CRIMSON-UNPACK 1\n"
#define PATH_MAX_LEN 1024
#define LINE_MAX_LEN 2048
#define COPY_BUF (1024 * 1024)

static char g_buf[COPY_BUF];

/* Reads exactly n bytes. 0 when done, -1 when the connection ended or failed. */
static int read_full(int fd, void *dst, size_t n) {
  char *p = dst;
  while (n > 0) {
    ssize_t r = recv(fd, p, n, 0);
    if (r < 0 && errno == EINTR) continue;
    if (r <= 0) return -1;
    p += r;
    n -= (size_t)r;
  }
  return 0;
}

static int write_full(int fd, const void *src, size_t n) {
  const char *p = src;
  while (n > 0) {
    ssize_t w = write(fd, p, n);
    if (w < 0 && errno == EINTR) continue;
    if (w <= 0) return -1;
    p += w;
    n -= (size_t)w;
  }
  return 0;
}

static void say(int fd, const char *fmt, ...) {
  char line[512];
  va_list ap;
  va_start(ap, fmt);
  int n = vsnprintf(line, sizeof(line), fmt, ap);
  va_end(ap);
  if (n > 0) {
    if ((size_t)n >= sizeof(line)) n = sizeof(line) - 1;
    send(fd, line, (size_t)n, 0);
  }
}

/* Reads one line ending in '\n' (without it). -1 when it doesn't come or is too long. */
static int read_line(int fd, char *line, size_t max) {
  size_t n = 0;
  while (n + 1 < max) {
    char c;
    if (read_full(fd, &c, 1)) return -1;
    if (c == '\n') {
      line[n] = 0;
      return 0;
    }
    line[n++] = c;
  }
  return -1;
}

/* Where files may go: the console's user data and its USB drives, never the system. */
static int dest_allowed(const char *dest) {
  if (dest[0] != '/' || strstr(dest, "/../") || strstr(dest, "/./")) return 0;
  size_t n = strlen(dest);
  if (n >= 3 && strcmp(dest + n - 3, "/..") == 0) return 0;
#ifdef UNPACK_ANY_DEST
  return 1;
#else
  return strcmp(dest, "/data") == 0 || strncmp(dest, "/data/", 6) == 0 || strncmp(dest, "/mnt/usb", 8) == 0 ||
         strncmp(dest, "/mnt/ext", 8) == 0;
#endif
}

/* A path inside DEST: relative, no empty, "." or ".." parts, no backslashes. */
static int rel_ok(const char *p) {
  if (!p[0] || p[0] == '/' || strchr(p, '\\')) return 0;
  const char *part = p;
  while (1) {
    const char *slash = strchr(part, '/');
    size_t len = slash ? (size_t)(slash - part) : strlen(part);
    if (len == 0) return 0;
    if (len == 1 && part[0] == '.') return 0;
    if (len == 2 && part[0] == '.' && part[1] == '.') return 0;
    if (!slash) return 1;
    part = slash + 1;
  }
}

/* Makes [path] and every folder above it. */
static int mkdirs(const char *path) {
  char tmp[PATH_MAX_LEN * 2 + 8];
  size_t n = strlen(path);
  if (n >= sizeof(tmp)) return -1;
  memcpy(tmp, path, n + 1);
  for (size_t i = 1; i <= n; i++) {
    if (tmp[i] == '/' || tmp[i] == 0) {
      char keep = tmp[i];
      tmp[i] = 0;
      if (mkdir(tmp, 0777) != 0 && errno != EEXIST) return -1;
      tmp[i] = keep;
    }
  }
  return 0;
}

/* Reads a record's path into full = DEST + "/" + path. -1 (after saying why) when it isn't acceptable. */
static int read_path(int fd, const char *dest, char *full, size_t max) {
  uint8_t lenb[2];
  if (read_full(fd, lenb, 2)) return -1;
  size_t len = ((size_t)lenb[0] << 8) | lenb[1];
  char rel[PATH_MAX_LEN + 1];
  if (len == 0 || len > PATH_MAX_LEN) {
    say(fd, "ERR bad path length\n");
    return -1;
  }
  if (read_full(fd, rel, len)) return -1;
  rel[len] = 0;
  if (memchr(rel, 0, len) || !rel_ok(rel)) {
    say(fd, "ERR bad path\n");
    return -1;
  }
  if ((size_t)snprintf(full, max, "%s/%s", dest, rel) >= max) {
    say(fd, "ERR path too long\n");
    return -1;
  }
  return 0;
}

/* One folder upload, from the version line on. */
static void serve_folder(int fd) {
  char line[LINE_MAX_LEN];
  if (read_line(fd, line, sizeof(line)) || strncmp(line, "DEST ", 5) != 0) {
    say(fd, "ERR expected DEST\n");
    return;
  }
  char dest[PATH_MAX_LEN + 1];
  size_t dl = strlen(line + 5);
  if (dl == 0 || dl > PATH_MAX_LEN) {
    say(fd, "ERR bad destination\n");
    return;
  }
  memcpy(dest, line + 5, dl + 1);
  while (dl > 1 && dest[dl - 1] == '/') dest[--dl] = 0;
  if (!dest_allowed(dest)) {
    say(fd, "ERR destination not allowed: %s\n", dest);
    return;
  }
  if (mkdirs(dest)) {
    say(fd, "ERR mkdir %s: %s\n", dest, strerror(errno));
    return;
  }

  unsigned long files = 0;
  unsigned long long bytes = 0;
  char full[PATH_MAX_LEN * 2 + 8];
  while (1) {
    char kind;
    if (read_full(fd, &kind, 1)) return; /* the phone went away: nothing to answer */
    if (kind == 'E') {
      say(fd, "OK %lu %llu\n", files, bytes);
      return;
    }
    if (kind != 'D' && kind != 'F') {
      say(fd, "ERR bad record\n");
      return;
    }
    if (read_path(fd, dest, full, sizeof(full))) return;
    if (kind == 'D') {
      if (mkdirs(full)) {
        say(fd, "ERR mkdir %s: %s\n", full, strerror(errno));
        return;
      }
      continue;
    }
    uint8_t sz[8];
    if (read_full(fd, sz, 8)) return;
    uint64_t size = 0;
    for (int i = 0; i < 8; i++) size = (size << 8) | sz[i];
    /* The folder above it, in case the phone did not name it on its own */
    char *slash = strrchr(full, '/');
    if (slash && slash != full) {
      *slash = 0;
      int bad = mkdirs(full);
      *slash = '/';
      if (bad) {
        say(fd, "ERR mkdir for %s: %s\n", full, strerror(errno));
        return;
      }
    }
    int out = open(full, O_WRONLY | O_CREAT | O_TRUNC, 0777);
    if (out < 0) {
      say(fd, "ERR open %s: %s\n", full, strerror(errno));
      return;
    }
    uint64_t left = size;
    while (left > 0) {
      size_t want = left > COPY_BUF ? COPY_BUF : (size_t)left;
      ssize_t r = recv(fd, g_buf, want, 0);
      if (r < 0 && errno == EINTR) continue;
      if (r <= 0) {
        close(out);
        return;
      }
      if (write_full(out, g_buf, (size_t)r)) {
        say(fd, "ERR write %s: %s\n", full, strerror(errno));
        close(out);
        return;
      }
      left -= (uint64_t)r;
    }
    close(out);
    files++;
    bytes += size;
  }
}

static void serve(int fd) {
  char line[LINE_MAX_LEN];
  if (read_line(fd, line, sizeof(line))) return;
  if (strcmp(line, "PING") == 0) {
    say(fd, UNPACK_HELLO);
    return;
  }
  if (strcmp(line, "CRIMSON-UNPACK 1") != 0) {
    say(fd, "ERR unknown version\n");
    return;
  }
  serve_folder(fd);
}

int main(void) {
  signal(SIGPIPE, SIG_IGN);
  int port = UNPACK_PORT;
  const char *env = getenv("UNPACK_PORT"); /* the tests pick a free port */
  if (env && atoi(env) > 0) port = atoi(env);

  int srv = socket(AF_INET, SOCK_STREAM, 0);
  if (srv < 0) return 1;
  int one = 1;
  setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
  struct sockaddr_in addr;
  memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(INADDR_ANY);
  addr.sin_port = htons((uint16_t)port);
  /* Port taken: a copy is already running, and that one does the work */
  if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) != 0 || listen(srv, 4) != 0) {
    close(srv);
    return 0;
  }
  printf("crimson-unpack listening on %d\n", port);
  fflush(stdout);

  while (1) {
    int c = accept(srv, NULL, NULL);
    if (c < 0) {
      if (errno == EINTR) continue;
      sleep(1);
      continue;
    }
    int big = COPY_BUF;
    setsockopt(c, SOL_SOCKET, SO_RCVBUF, &big, sizeof(big));
    setsockopt(c, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    serve(c); /* one folder at a time: the phone sends them one after the other */
    close(c);
  }
}
