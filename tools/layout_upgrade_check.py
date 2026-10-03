# -*- coding: utf-8 -*-
r"""Development helper: does an upgrade keep the list layout the user had? (the 0.3.1 -> 0.3.2 rename of the list layouts)

  python -X utf8 tools/layout_upgrade_check.py [-s SERIAL] OLD_APK NEW_APK [OUT_DIR]

For each value 0.3.1 could store under `list_mode` (MAGAZINE, LIST, CARD) it: installs OLD_APK over the existing app data
(`-r -d`, the signature and applicationId are the same), writes the settings file the way 0.3.1 left it (only the old key),
starts the app and records what is on screen (uiautomator dump + screenshot); then installs NEW_APK over it WITHOUT
uninstalling, starts it and records again. The two screens must show the same articles at the same places. The app needs
articles to show (import an OPML first); nothing here touches reading state. Development helper, not used by the app or CI.
"""
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(__file__))
import compare_app_state as cas  # noqa: E402

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
PKG = "io.github.yuubiz.feeddeck"
SETTINGS = "files/datastore/settings.preferences_pb"
args = sys.argv[1:]
serial = None
if args and args[0] == "-s":
    serial, args = args[1], args[2:]
OLD, NEW = args[0], args[1]
OUT = args[2] if len(args) > 2 else "reports-local/v32-layout"
os.makedirs(OUT, exist_ok=True)


def adb(*a, binary=False):
    out = subprocess.run([ADB] + (["-s", serial] if serial else []) + list(a), capture_output=True)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def pull_settings():
    return cas.decode_preferences(adb("exec-out", "run-as", PKG, "cat", SETTINGS, binary=True))


def push_settings(prefs):
    with tempfile.NamedTemporaryFile(delete=False, suffix=".pb") as f:
        f.write(cas.encode_preferences(prefs))
    adb("push", f.name, "/data/local/tmp/settings.pb")
    adb("shell", "run-as", PKG, "cp", "/data/local/tmp/settings.pb", SETTINGS)
    os.unlink(f.name)


def screen(tag):
    time.sleep(9)
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        raw = adb("shell", "cat", "/sdcard/ui.xml")
        if raw.strip().startswith("<?xml"):
            break
        time.sleep(1)
    root = ET.fromstring(raw[raw.index("<?xml"):].encode("utf-8"))
    items = []
    for n in root.iter("node"):
        text = n.get("text") or ""
        if not text or " · " in text:  # the source line carries a relative time ("5 minutes ago")
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if m and int(m.group(2)) > 560:  # below the toolbar, tabs and filter chips
            items.append((text, tuple(int(g) for g in m.groups())))
    with open(os.path.join(OUT, tag + ".png"), "wb") as f:
        f.write(adb("exec-out", "screencap", "-p", binary=True))
    return items


def start():
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/.app.MainActivity")


failures = 0
for stored in ("MAGAZINE", "LIST", "CARD"):
    adb("install", "-r", "-d", OLD)
    adb("shell", "am", "force-stop", PKG)
    prefs = pull_settings()
    prefs.pop("list_layout", None)
    prefs["list_mode"] = ("string", stored)
    push_settings(prefs)
    start()
    before = screen(f"{stored}-before")
    adb("shell", "am", "force-stop", PKG)
    adb("install", "-r", NEW)  # an upgrade: no uninstall, the data stays
    start()
    after = screen(f"{stored}-after")
    adb("shell", "am", "force-stop", PKG)
    settings_after = pull_settings()
    same = before == after and len(before) > 0
    failures += not same
    print(f"{stored}: {len(before)} items before, {len(after)} after, identical={same}; "
          f"settings after: list_mode={settings_after.get('list_mode')} list_layout={settings_after.get('list_layout')}")
    if not same:
        print("  only before:", [x for x in before if x not in after][:4])
        print("  only after: ", [x for x in after if x not in before][:4])
print("RESULT:", "ALL THREE LAYOUTS UNCHANGED" if not failures else f"{failures} LAYOUT(S) CHANGED")
sys.exit(1 if failures else 0)
