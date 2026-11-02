#!/usr/bin/env python3
"""Checks every translation against the English strings.

  python3 .github/scripts/check_strings.py            all languages
  python3 .github/scripts/check_strings.py de ja      only these folders (values-de, values-ja)

For each values-*/strings.xml it reports:
  missing       keys English has that the language lacks (translatable="false" keys are skipped)
  extra         keys the language has that English no longer has
  placeholders  %1$s / %d blanks that differ from English (a crash or a lost value at run time)
  apostrophes   a ' not written as \\' (aapt refuses to build)
  bad xml       a file that does not parse
Exit status 1 when anything is wrong.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

RES = os.environ.get("RES_DIR", "app/src/main/res")
BLANK = re.compile(r"%(\d+\$)?[sd]")
LOOSE_APOS = re.compile(r'<string name="([^"]+)"[^>]*>[^<]*(?<!\\)\'')


def load(path):
    root = ET.parse(path).getroot()
    texts, fixed = {}, set()
    for e in root:
        if e.tag != "string":
            continue
        name = e.get("name")
        texts[name] = "".join(e.itertext())
        if e.get("translatable") == "false":
            fixed.add(name)
    return texts, fixed


def main(langs):
    en, fixed = load(f"{RES}/values/strings.xml")
    paths = sorted(glob.glob(f"{RES}/values-*/strings.xml"))
    if langs:
        paths = [p for p in paths if p.split("/")[-2].removeprefix("values-") in langs]
    bad = 0
    for p in paths:
        lang = p.split("/")[-2].removeprefix("values-")
        try:
            d, _ = load(p)
        except ET.ParseError as e:
            print(f"{lang}: bad xml: {e}")
            bad += 1
            continue
        missing = [k for k in en if k not in d and k not in fixed]
        extra = [k for k in d if k not in en]
        blanks = [k for k in d if k in en and sorted(BLANK.findall(en[k])) != sorted(BLANK.findall(d[k]))]
        apos = LOOSE_APOS.findall(open(p, encoding="utf-8").read())
        problems = []
        if missing:
            problems.append(f"missing {len(missing)} ({', '.join(missing[:5])}{'…' if len(missing) > 5 else ''})")
        if extra:
            problems.append(f"extra {len(extra)} ({', '.join(extra[:5])})")
        if blanks:
            problems.append(f"placeholders differ: {', '.join(blanks[:5])}")
        if apos:
            problems.append(f"unescaped apostrophe: {', '.join(apos[:5])}")
        if problems:
            bad += 1
            print(f"{lang}: " + "; ".join(problems))
    print(f"{len(paths)} languages checked, {bad} with problems")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(set(sys.argv[1:])))
