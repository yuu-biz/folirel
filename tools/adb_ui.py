# -*- coding: utf-8 -*-
r"""Tiny adb + uiautomator driver for manual emulator checks (development helper, not used by the app or CI).

Run with `python -X utf8 tools/adb_ui.py ...`. Uses %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe.
Note: `type` uses `adb shell input text`, which supports ASCII only.

usage: adb_ui.py [-s SERIAL] dump | texts | tap TEXT [--exact] [--desc] [--index N] | tapxy X Y | type TEXT |
               key KEYCODE | shot PATH | wait TEXT [SECONDS] | swipe X1 Y1 X2 Y2 [MS]
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
args = sys.argv[1:]
serial = None
if args and args[0] == "-s":
    serial = args[1]
    args = args[2:]


def adb(*a, binary=False):
    cmd = [ADB] + (["-s", serial] if serial else []) + list(a)
    out = subprocess.run(cmd, capture_output=True)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def dump():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        raw = adb("shell", "cat", "/sdcard/ui.xml")
        if raw.strip().startswith("<?xml"):
            return ET.fromstring(raw[raw.index("<?xml"):].encode("utf-8"))
        time.sleep(1)
    raise SystemExit("dump failed")


def nodes(root):
    for n in root.iter("node"):
        yield n


def center(n):
    b = re.findall(r"\d+", n.get("bounds"))
    x1, y1, x2, y2 = map(int, b)
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(text, exact=False, desc=False):
    root = dump()
    found = []
    for n in nodes(root):
        values = [n.get("content-desc", "")] if desc else [n.get("text", ""), n.get("content-desc", "")]
        for v in values:
            if (exact and v == text) or (not exact and text in v):
                found.append(n)
                break
    return found


if not args:
    raise SystemExit(__doc__)
cmd = args[0]
if cmd == "dump":
    print(adb("shell", "uiautomator", "dump", "/dev/tty"))
elif cmd == "texts":
    for n in nodes(dump()):
        t = n.get("text") or ""
        d = n.get("content-desc") or ""
        if t or d:
            print(f"{n.get('bounds')} text={t!r} desc={d!r} cls={n.get('class').split('.')[-1]} click={n.get('clickable')}")
elif cmd == "tap":
    text = args[1]
    exact = "--exact" in args
    desc = "--desc" in args
    index = int(args[args.index("--index") + 1]) if "--index" in args else 0
    found = find(text, exact, desc)
    if len(found) <= index:
        raise SystemExit(f"not found: {text}")
    x, y = center(found[index])
    adb("shell", "input", "tap", str(x), str(y))
    print(f"tapped {text!r} at {x},{y}")
elif cmd == "tapxy":
    adb("shell", "input", "tap", args[1], args[2])
elif cmd == "type":
    adb("shell", "input", "text", args[1].replace(" ", "%s"))
elif cmd == "key":
    adb("shell", "input", "keyevent", args[1])
elif cmd == "swipe":
    adb("shell", "input", "swipe", *args[1:])
elif cmd == "shot":
    data = adb("exec-out", "screencap", "-p", binary=True)
    with open(args[1], "wb") as f:
        f.write(data)
    print("saved", args[1], len(data))
elif cmd == "wait":
    text = args[1]
    limit = float(args[2]) if len(args) > 2 else 20
    start = time.time()
    while time.time() - start < limit:
        if find(text):
            print(f"found {text!r} after {time.time() - start:.1f}s")
            sys.exit(0)
        time.sleep(1)
    raise SystemExit(f"timeout waiting for {text!r}")
else:
    raise SystemExit(__doc__)
