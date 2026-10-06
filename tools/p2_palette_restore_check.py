# -*- coding: utf-8 -*-
r"""Development helper for P2-T10 / P2-T35 (not used by the app or CI): a colour set and a mode that were chosen in Settings > Appearance
come back after the process was killed and Android restored the screen. Test emulator only.

  python -X utf8 tools/p2_palette_restore_check.py --out reports-local/p2-palette-restore-API37

Needs the app and the test APK installed (`gradlew :app:installDebug :app:installDebugAndroidTest`). It does not uninstall anything.

What it does
  1. runs `P2StateSetup` (`-e p2evidence true`): stores the Sand colour set and the Dark mode, two categories;
  2. starts the app, opens the drawer > Settings > Appearance, and reads what is on the screen: the chosen rows (`checked`) and the colours
     drawn (pixels of the screenshot, within a few steps of the Sand dark primary and surface);
  3. Home, `am kill` (only a background app can be killed; its task stays), starts the app again: Android restores the page that was open,
     and the same is read again; the settings file must not have been written while the screen was rebuilt (same time, size and content).
The exit code is 1 when a check failed. The screen is read with uiautomator (English UI).
"""
import argparse
import os
import struct
import sys
import time
import zlib

sys.path.insert(0, os.path.dirname(__file__))
import p1_restore_check as r  # noqa: E402

# Sand, dark (ui/theme/Palettes.kt): primary and surface.
SAND_DARK_PRIMARY = (0xFF, 0xB8, 0x6B)
SAND_DARK_SURFACE = (0x16, 0x12, 0x0C)


def png_pixels(data):
    """(width, height, rows of RGB tuples) of an 8-bit RGB or RGBA PNG, without any imaging library."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, idat, width, height, ctype = 8, b"", 0, 0, 0
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height, depth, ctype = struct.unpack(">IIBB", body[:10])
            assert depth == 8 and ctype in (2, 6), f"unsupported PNG (depth {depth}, type {ctype})"
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length
    bpp = 4 if ctype == 6 else 3
    raw = zlib.decompress(idat)
    stride = width * bpp
    prev = bytearray(stride)
    rows = []
    for y in range(height):
        start = y * (stride + 1)
        filt, line = raw[start], bytearray(raw[start + 1:start + 1 + stride])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if filt == 1:
                line[i] = (line[i] + a) & 255
            elif filt == 2:
                line[i] = (line[i] + b) & 255
            elif filt == 3:
                line[i] = (line[i] + (a + b) // 2) & 255
            elif filt == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
        rows.append([(line[x * bpp], line[x * bpp + 1], line[x * bpp + 2]) for x in range(width)])
    return width, height, rows


def count_near(rows, colour, tolerance=3):
    n = 0
    for row in rows:
        for px in row:
            if all(abs(px[i] - colour[i]) <= tolerance for i in range(3)):
                n += 1
    return n


def screenshot(path):
    data = r.adb("exec-out", "screencap", "-p", binary=True)
    with open(path, "wb") as f:
        f.write(data)
    return png_pixels(data)


def open_appearance():
    root = r.wait_for(lambda d: r.find_desc(d, "Open navigation"), 15)
    if root is None:
        raise SystemExit("the home screen did not come up")
    r.tap(r.find_desc(root, "Open navigation"))
    root = r.wait_for(lambda d: r.find_text(d, "Settings"))
    r.tap(r.find_text(root, "Settings"))
    root = r.wait_for(lambda d: r.find_text(d, "Appearance"))
    r.tap(r.find_text(root, "Appearance"))
    root = r.wait_for(lambda d: r.find_text(d, "Preview"), 15)
    if root is None:
        raise SystemExit("the Appearance page did not open")


def read_page(label, out, name):
    root = r.dump()
    checked = r.checked_rows(root)
    r.check(f"{label}: the Appearance page is shown", r.find_text(root, "Preview") is not None)
    r.check(f"{label}: Sand and Dark are the chosen rows", "Sand" in checked and "Dark" in checked and "Classic" not in checked, f"{checked}")
    _w, _h, rows = screenshot(os.path.join(out, name))
    primary, surface = count_near(rows, SAND_DARK_PRIMARY), count_near(rows, SAND_DARK_SURFACE)
    r.check(f"{label}: the Sand dark colours are drawn (primary {primary} px, surface {surface} px)",
            primary >= 12 and surface > 10_000)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    adb = r.adb
    adb("shell", "svc", "power", "stayon", "true")
    out = adb("shell", "am", "instrument", "-w", "-e", "p2evidence", "true", "-e", "class", "io.github.yuubiz.folirel.P2StateSetup",
              f"{r.PKG}.test/androidx.test.runner.AndroidJUnitRunner")
    r.check("setup helper ran (Sand, Dark)", "OK (1 test)" in out, out[-300:])
    adb("shell", "am", "force-stop", r.PKG)
    r.launch()
    open_appearance()
    read_page("before the kill", args.out, "before-kill.png")
    before = r.pull_state("before", args.out)
    settings_before, stat_before = r.settings_of(before), r.settings_file_stat()
    r.check("the stored settings hold Sand and Dark", settings_before.get("color_palette", (None, ""))[1] == "SAND" and settings_before.get("theme", (None, ""))[1] == "DARK",
            f"{settings_before.get('color_palette')} {settings_before.get('theme')}")

    pid_before = r.pid()
    adb("shell", "input", "keyevent", "3")  # Home: the app goes to the background with its screen state saved
    time.sleep(2)
    for _attempt in range(10):
        adb("shell", "am", "kill", r.PKG)
        time.sleep(1.5)
        if r.pid() == "":
            break
    r.check("the process is gone", r.pid() == "", f"pid before {pid_before}, now '{r.pid()}'")
    r.launch()
    pid_after = r.pid()
    r.check("a new process was started", bool(pid_after) and pid_after != pid_before, f"{pid_before} -> {pid_after}")
    root = r.wait_for(lambda d: r.find_text(d, "Preview"), 20)
    r.check("restored: Android brought back the Appearance page", root is not None)
    if root is not None:
        time.sleep(1.5)
        read_page("after the kill", args.out, "after-kill.png")
    after = r.pull_state("after", args.out)
    r.check("the settings file was not written while the screen was rebuilt (same time and size)", r.settings_file_stat() == stat_before, f"{stat_before} -> {r.settings_file_stat()}")
    r.check("every setting is as it was", r.settings_of(after) == settings_before)
    print(f"RESULT: {'OK' if all(r.results) else 'FAILED'} ({sum(r.results)}/{len(r.results)} checks passed)")
    return 0 if all(r.results) else 1


if __name__ == "__main__":
    sys.exit(main())
