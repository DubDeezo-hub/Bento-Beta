#!/usr/bin/env python3
"""Writes the unit test results as a short table for the run page: how many passed, and each one that failed.

  unit_summary.py <folder with the JUnit XML files>     (prints Markdown; the workflow adds it to the run's summary)
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

folder = sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results/testDebugUnitTest"
total = failed = skipped = 0
problems = []
for path in sorted(glob.glob(os.path.join(folder, "*.xml"))):
    try:
        suite = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in suite.iter("testcase"):
        total += 1
        if case.find("skipped") is not None:
            skipped += 1
        bad = case.find("failure")
        if bad is None:
            bad = case.find("error")
        if bad is not None:
            failed += 1
            cls = case.get("classname", "").rsplit(".", 1)[-1]
            text = bad.get("message") or (bad.text or "").strip()
            first = text.splitlines()[0] if text else ""
            problems.append(f"| ❌ | {cls} › {case.get('name')} — {first[:300].replace('|', '/')} |")

print("## Unit tests")
print()
if total == 0:
    print("No unit test results were found (the tests may not have started; see the log).")
else:
    print(f"**{total - failed - skipped} passed, {failed} failed" + (f", {skipped} skipped" if skipped else "") + f"** of {total}.")
    if problems:
        print()
        print("| | test |")
        print("|---|---|")
        print("\n".join(problems))
