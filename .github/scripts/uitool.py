#!/usr/bin/env python3
"""Small helpers for ui_smoke.sh. Reads a `uiautomator dump` of the phone screen and the app's own string files.

  uitool.py find   <dump.xml> <text> [<text> ...]   prints "centerX centerY left right" of the lowest node showing one of the texts
  uitool.py findfmt <dump.xml> <format string>      the same for a text with %1$s style blanks, e.g. "Version %1$s is available"
  uitool.py haspkg <dump.xml> <package>             exit 0 when something on screen belongs to the package
  uitool.py texts  <dump.xml>                       the texts on screen, for the failure messages
  uitool.py string <key> [<values dir>]             the text of a string resource (default: the English one)
  uitool.py resdir <language tag>                   the resource folder Android uses for that language, e.g. he -> values-iw
  uitool.py langs                                   the language tags the app is translated into (from locales_config.xml)

The res folder is taken from $RES_DIR (default app/src/main/res). Exit status 1 means "not found".
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

RES = os.environ.get("RES_DIR", "app/src/main/res")
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
BLANK = re.compile(r"%(?:\d+\$)?[sd]")

# Languages whose resources Android files under an older code
ALIASES = {"he": "iw", "id": "in", "yi": "ji"}


def squash(s):
    return " ".join((s or "").split())


def screen_nodes(path):
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return []
    found = []
    for n in root.iter("node"):
        m = BOUNDS.match(n.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = (int(g) for g in m.groups())
        found.append({
            "text": squash(n.get("text")),
            "desc": squash(n.get("content-desc")),
            "pkg": n.get("package", ""),
            "box": (x1, y1, x2, y2),
        })
    return found


def pieces(s):
    """A merged button can show its texts joined together; match any single piece too."""
    out = {s}
    for sep in (", ", "\n"):
        if sep in s:
            out.update(p.strip() for p in s.split(sep))
    return out


def print_hit(node):
    x1, y1, x2, y2 = node["box"]
    print((x1 + x2) // 2, (y1 + y2) // 2, x1, x2)


def cmd_find(argv):
    wanted = {squash(a) for a in argv[1:] if squash(a)}
    hits = [n for n in screen_nodes(argv[0])
            if wanted & (pieces(n["text"]) | pieces(n["desc"]))]
    if not hits:
        return 1
    # The bottom bar is the lowest thing on screen, so when a word shows up twice the lowest one is the tab
    print_hit(max(hits, key=lambda n: (n["box"][1] + n["box"][3])))
    return 0


def cmd_findfmt(argv):
    parts = BLANK.split(argv[1])
    pattern = re.compile(".+?".join(re.escape(squash(p)) for p in parts))
    for n in screen_nodes(argv[0]):
        if (n["text"] and pattern.search(n["text"])) or (n["desc"] and pattern.search(n["desc"])):
            print_hit(n)
            return 0
    return 1


def cmd_haspkg(argv):
    return 0 if any(n["pkg"] == argv[1] for n in screen_nodes(argv[0])) else 1


def cmd_texts(argv):
    seen = []
    for n in screen_nodes(argv[0]):
        for t in (n["text"], n["desc"]):
            if t and t not in seen:
                seen.append(t)
    print(" | ".join(seen)[:700])
    return 0


def unescape(s):
    s = s.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        s = s[1:-1]
    return (s.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")
             .replace("\\@", "@").replace("\\?", "?").replace("\\\\", "\\"))


def cmd_string(argv):
    key = argv[0]
    folder = argv[1] if len(argv) > 1 else "values"
    path = os.path.join(RES, folder, "strings.xml")
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return 1
    for s in root.iter("string"):
        if s.get("name") == key:
            print(unescape("".join(s.itertext())))
            return 0
    return 1


def candidates(tag):
    tag = tag.replace("_", "-")
    parts = tag.split("-")
    lang = ALIASES.get(parts[0], parts[0])
    if len(parts) == 1:
        return ["values-" + lang]
    region = parts[1]
    return ["values-%s-r%s" % (lang, region), "values-" + lang]


def cmd_resdir(argv):
    tag = argv[0]
    if tag == "en":
        print("values")
        return 0
    for c in candidates(tag):
        if os.path.isfile(os.path.join(RES, c, "strings.xml")):
            print(c)
            return 0
    return 1


def cmd_langs(argv):
    root = ET.parse(os.path.join(RES, "xml", "locales_config.xml")).getroot()
    for el in root.iter("locale"):
        name = el.get("{http://schemas.android.com/apk/res/android}name")
        if name:
            print(name)
    return 0


COMMANDS = {
    "find": (cmd_find, 2), "findfmt": (cmd_findfmt, 2), "haspkg": (cmd_haspkg, 2), "texts": (cmd_texts, 1),
    "string": (cmd_string, 1), "resdir": (cmd_resdir, 1), "langs": (cmd_langs, 0),
}


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        sys.stderr.write(__doc__)
        return 2
    fn, need = COMMANDS[sys.argv[1]]
    args = sys.argv[2:]
    if len(args) < need:
        sys.stderr.write("missing arguments for " + sys.argv[1] + "\n")
        return 2
    return fn(args)


if __name__ == "__main__":
    sys.exit(main())
