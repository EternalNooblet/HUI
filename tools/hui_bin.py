#!/usr/bin/env python3
"""Resolve the hui binary for the test harnesses.

Prefers builds/{hui,hui.exe}, falls back to the legacy ./hui next to this
script. Set HUI_BIN to override explicitly.
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def hui_bin():
    over = os.environ.get("HUI_BIN")
    if over:
        return over
    exe = "hui.exe" if os.name == "nt" else "hui"
    cand = [os.path.join(ROOT, "builds", exe), os.path.join(ROOT, exe)]
    for c in cand:
        if os.path.exists(c):
            return c
    return cand[0]