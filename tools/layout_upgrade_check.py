# -*- coding: utf-8 -*-
r"""Development helper: does an upgrade keep the list layout the user had? (the 0.3.1 -> 0.3.2 rename of the list layouts)

  python -X utf8 tools/layout_upgrade_check.py [-s SERIAL] OLD_APK NEW_APK [OUT_DIR]

For each value 0.3.1 could store under `list_mode` (MAGAZINE, LIST, CARD) it: installs OLD_APK over the existing app data
(`-r -d`; same signature and applicationId), writes the settings file the way 0.3.1 left it (only the old key), starts the
app and records what is on screen (uiautomator dump + screenshot); then installs NEW_APK over it WITHOUT uninstalling,
starts it and records again. It FAILS (exit code 1) unless, for every value:
  - the app that is installed after each install really is the APK that was asked for (its SHA-256 is compared with the
    file's; OLD and NEW must be different files), so a failed install cannot turn into "the same app twice";
  - after the upgrade the new key holds the expected new name (MAGAZINE->LIST, LIST->COMPACT_LIST, CARD->MAGAZINE) and
    the old `list_mode` is still exactly what 0.3.1 stored;
  - the two screens show the same articles at the same places (and there is something on screen);
and the three layouts must look different from each other (otherwise "same screen" would prove nothing).
Any adb command that fails stops the check with exit code 2. The app needs articles to show (import an OPML first).
Test devices only: it replaces the app and rewrites its settings file. Not used by the app or CI.
Its own tests: python -X utf8 -m unittest tools.test_layout_upgrade_check
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(__file__))
import compare_app_state as cas  # noqa: E402

PKG = "io.github.yuubiz.feeddeck"
SETTINGS = "files/datastore/settings.preferences_pb"
EXPECTED_NEW_NAME = {"MAGAZINE": "LIST", "LIST": "COMPACT_LIST", "CARD": "MAGAZINE"}


def find_adb():
    """adb from ANDROID_HOME / ANDROID_SDK_ROOT if present there, otherwise from PATH."""
    exe = "adb.exe" if os.name == "nt" else "adb"
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(var)
        if root and os.path.exists(os.path.join(root, "platform-tools", exe)):
            return os.path.join(root, "platform-tools", exe)
    return shutil.which("adb") or exe


class AdbError(Exception):
    """An adb command did not succeed. The check must not go on as if it had."""


class Adb:
    def __init__(self, serial=None, adb_path=None, runner=subprocess.run):
        self.serial = serial
        self.adb_path = adb_path or find_adb()
        self.runner = runner

    def run(self, *args, binary=False):
        cmd = [self.adb_path] + (["-s", self.serial] if self.serial else []) + list(args)
        result = self.runner(cmd, capture_output=True)
        if result.returncode != 0:
            err = (result.stderr or b"").decode("utf-8", "replace").strip()
            raise AdbError(f"adb {' '.join(args)} failed with exit code {result.returncode}: {err}")
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")


class Device:
    """The operations the check needs, on a real device through adb."""

    def __init__(self, adb, out_dir):
        self.adb, self.out_dir = adb, out_dir

    def install(self, apk):
        out = self.adb.run("install", "-r", "-d", apk)
        if "Success" not in out:
            raise AdbError(f"install of {apk} did not report Success: {out.strip()}")

    def installed_identity(self):
        """(sha256 of the installed base.apk, versionName, versionCode)."""
        path = self.adb.run("shell", "pm", "path", PKG).strip().splitlines()[0].removeprefix("package:").strip()
        digest = self.adb.run("shell", "sha256sum", path).split()[0].lower()
        dump = self.adb.run("shell", "dumpsys", "package", PKG)
        name = re.search(r"versionName=(\S+)", dump)
        code = re.search(r"versionCode=(\d+)", dump)
        return digest, name and name.group(1), code and int(code.group(1))

    def force_stop(self):
        self.adb.run("shell", "am", "force-stop", PKG)

    def start(self):
        self.adb.run("shell", "am", "start", "-n", f"{PKG}/.app.MainActivity")

    def read_settings(self):
        return cas.decode_preferences(self.adb.run("exec-out", "run-as", PKG, "cat", SETTINGS, binary=True))

    def write_settings(self, prefs):
        with tempfile.NamedTemporaryFile(delete=False, suffix=".pb") as f:
            f.write(cas.encode_preferences(prefs))
        try:
            self.adb.run("push", f.name, "/data/local/tmp/settings.pb")
            self.adb.run("shell", "run-as", PKG, "cp", "/data/local/tmp/settings.pb", SETTINGS)
        finally:
            os.unlink(f.name)

    def capture(self, tag):
        time.sleep(9)
        raw = ""
        for _ in range(3):
            self.adb.run("shell", "uiautomator", "dump", "/sdcard/ui.xml")
            raw = self.adb.run("shell", "cat", "/sdcard/ui.xml")
            if raw.strip().startswith("<?xml"):
                break
            time.sleep(1)
        else:
            raise AdbError("uiautomator dump did not produce a document")
        root = ET.fromstring(raw[raw.index("<?xml"):].encode("utf-8"))
        items = []
        for n in root.iter("node"):
            text = n.get("text") or ""
            if not text or " · " in text:  # the source line carries a relative time ("5 minutes ago")
                continue
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
            if m and int(m.group(2)) > 560:  # below the toolbar, tabs and filter chips
                items.append((text, tuple(int(g) for g in m.groups())))
        os.makedirs(self.out_dir, exist_ok=True)
        with open(os.path.join(self.out_dir, tag + ".png"), "wb") as f:
            f.write(self.adb.run("exec-out", "screencap", "-p", binary=True))
        return items


def sha256_of(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def check_upgrade(device, old_apk, new_apk, old_sha, new_sha, log=print):
    """Runs the whole check; returns the list of problems (empty = every layout was kept)."""
    problems = []
    if old_sha == new_sha:
        return ["OLD_APK and NEW_APK are the same file: comparing them would prove nothing"]
    screens_before = {}
    for stored, expected_new in EXPECTED_NEW_NAME.items():
        device.install(old_apk)
        sha, name, code = device.installed_identity()
        if sha != old_sha:
            problems.append(f"{stored}: after installing OLD_APK the installed app is not OLD_APK (sha256 {sha[:12]}…)")
            continue
        device.force_stop()
        prefs = device.read_settings()
        prefs.pop("list_layout", None)
        prefs["list_mode"] = ("string", stored)
        device.write_settings(prefs)
        device.start()
        before = device.capture(f"{stored}-before")
        device.force_stop()
        device.install(new_apk)  # an upgrade: no uninstall, the data stays
        sha2, name2, code2 = device.installed_identity()
        if sha2 != new_sha:
            problems.append(f"{stored}: after installing NEW_APK the installed app is not NEW_APK (sha256 {sha2[:12]}…)")
            continue
        device.start()
        after = device.capture(f"{stored}-after")
        device.force_stop()
        settings = device.read_settings()
        screens_before[stored] = before
        if not before:
            problems.append(f"{stored}: nothing was on screen before the upgrade (import some articles first)")
        if before != after:
            problems.append(f"{stored}: the screen changed with the upgrade ({len(before)} items before, {len(after)} after)")
        if settings.get("list_layout") != ("string", expected_new):
            problems.append(f"{stored}: list_layout after the upgrade is {settings.get('list_layout')}, expected {('string', expected_new)}")
        if settings.get("list_mode") != ("string", stored):
            problems.append(f"{stored}: the old list_mode is {settings.get('list_mode')} after the upgrade, expected {('string', stored)} to be kept")
        log(f"{stored}: installed {name}/{code} -> {name2}/{code2}; {len(before)} items before, {len(after)} after; "
            f"list_layout={settings.get('list_layout')} list_mode={settings.get('list_mode')}")
    shown = [tuple(v) for v in screens_before.values()]
    if len(shown) == len(EXPECTED_NEW_NAME) and len(set(shown)) != len(shown):
        problems.append("two of the three layouts looked the same before the upgrade: the check cannot tell them apart")
    return problems


def main(argv):
    args = list(argv)
    serial = None
    if args and args[0] == "-s":
        serial, args = args[1], args[2:]
    if len(args) < 2:
        print(__doc__)
        return 2
    old_apk, new_apk = args[0], args[1]
    out_dir = args[2] if len(args) > 2 else "reports-local/v32-layout"
    try:
        old_sha, new_sha = sha256_of(old_apk), sha256_of(new_apk)
    except OSError as e:
        print("APK ERROR:", e)
        return 2
    try:
        problems = check_upgrade(Device(Adb(serial), out_dir), old_apk, new_apk, old_sha, new_sha)
    except AdbError as e:
        print("ADB ERROR:", e)
        return 2
    for p in problems:
        print("PROBLEM:", p)
    print("RESULT:", "ALL THREE LAYOUTS KEPT" if not problems else f"{len(problems)} PROBLEM(S)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
