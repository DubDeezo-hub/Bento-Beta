#!/usr/bin/env python3
"""Puts a right-to-left mark (U+200F) at the start of right-to-left strings that begin with a Latin word.

  python3 .github/scripts/rtl_marks.py ar fa iw ur

Without it, a line like "FTP متصل" is laid out left-to-right because its first strong letter is Latin. Safe to run
again: strings that already start with the mark are left alone.
"""
import os
import re
import sys

RES = os.environ.get("RES_DIR", "app/src/main/res")
RTL = re.compile(r"[֐-ࣿיִ-﷿ﹰ-﻿]")
MARK = "‏"


def fix_file(lang):
    path = f"{RES}/values-{lang}/strings.xml"
    text = open(path, encoding="utf-8").read()
    count = 0

    def fix(m):
        nonlocal count
        value = m.group(2)
        if value.startswith(MARK) or not RTL.search(value):
            return m.group(0)
        first = re.search(r"[A-Za-z]|" + RTL.pattern, value)
        if first and not RTL.match(first.group(0)):
            count += 1
            return m.group(1) + MARK + value + m.group(3)
        return m.group(0)

    text = re.sub(r'(<string name="[^"]+"[^>]*>)(.*?)(</string>)', fix, text, flags=re.S)
    open(path, "w", encoding="utf-8").write(text)
    print(f"{lang}: marked {count}")


if __name__ == "__main__":
    for lang in sys.argv[1:] or ["ar", "fa", "iw", "ur"]:
        fix_file(lang)
