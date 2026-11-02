#!/usr/bin/env python3
"""Writes Android Lint's findings as a short table for the run page: how many errors and warnings, and the most common kinds.

  lint_summary.py <lint-results-debug.xml>     (prints Markdown)
"""
import collections
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1] if len(sys.argv) > 1 else "app/build/reports/lint-results-debug.xml"
print("## Android Lint")
print()
try:
    root = ET.parse(path).getroot()
except (OSError, ET.ParseError):
    print("Lint did not write a report (see lint.log in the run).")
    sys.exit(0)

issues = root.findall("issue")
by_level = collections.Counter(i.get("severity", "?") for i in issues)
kinds = collections.Counter((i.get("severity", "?"), i.get("id", "?"), i.get("summary", "")) for i in issues)
errors = by_level.get("Error", 0) + by_level.get("Fatal", 0)
print(f"**{errors} errors, {by_level.get('Warning', 0)} warnings.** The full report is in the Phone test's Artifacts.")
if kinds:
    print()
    print("| level | kind | count |")
    print("|---|---|---|")
    order = {"Fatal": 0, "Error": 1, "Warning": 2}
    for (level, kind, summary), n in sorted(kinds.items(), key=lambda kv: (order.get(kv[0][0], 3), -kv[1]))[:25]:
        print(f"| {level} | {kind}: {summary.replace('|', '/')} | {n} |")
