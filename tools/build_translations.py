#!/usr/bin/env python3
"""Turns the Tilvar translations (<code>.json, machine-translated from
strings_en.json) into res/values-<code>/bb_strings.xml.   usage: build_translations.py <dir with json files>"""
import json
import os
import re
import sys

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
res = os.path.join(root, "app", "src", "main", "res")
src = sys.argv[1]
fname = sys.argv[2] if len(sys.argv) > 2 else "bb_strings.xml"


def esc(s: str) -> str:
    s = s.replace("\\", "\\\\").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if s[:1] in "@?":
        s = "\\" + s
    # printf placeholders with several arguments need positional form; Tilvar keeps ours, so nothing to do
    return s


n = 0
for fn in sorted(os.listdir(src)):
    if not fn.endswith(".json"):
        continue
    code = fn[:-5]
    d = json.load(open(os.path.join(src, fn), encoding="utf-8"))
    out = os.path.join(res, "values-" + code)
    os.makedirs(out, exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for k, v in d.items():
        lines.append(f'    <string name="{k}">{esc(v)}</string>')
    lines.append("</resources>")
    open(os.path.join(out, fname), "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
    n += 1
print(n, "languages written")
