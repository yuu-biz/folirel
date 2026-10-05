# -*- coding: utf-8 -*-
r"""Development helper for P1-T29 (not used by the app or CI): the order, pins and display overrides that the reader set in 0.5.0 survive a
normal restart and a process death with the screen state restored by Android, and nothing overwrites the selected category while
the screen is being rebuilt. Test emulator only (the app is uninstalled first).

  python -X utf8 tools/p1_restore_check.py --apk artifacts/Folirel-0.5.0-debug.apk --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk --out reports-local/p1-t29

What it does
  1. installs the APK and the test APK, runs `P1RestoreSetup#configure` (eight categories, two pinned, a saved order, overrides of three
     categories, selected category Delta), and copies the database and the settings (the BEFORE state);
  2. normal restart (force-stop, launch): reads what the screen shows (selected category, the order and pins in the organizer, the layout
     of each category in the display panel) and checks it against what was set;
  3. process death: selects Epsilon, types a search text (kept only in the saved screen state), presses Home, kills the process
     (`am kill`, which only kills a background app and keeps its task), launches the app again and checks that Android restored the screen
     (same category, the search text) and that every setting is as it was.
  Overwriting by a transient value is checked by the settings file itself: DataStore writes a file only when a value changes, so the file's
  modification time, size and decoded content must be the same before and after each restart/restoration, and the database rows (including
  category_preferences) must be equal. The exit code is 1 when anything failed.

The screen is read with uiautomator (English UI): `checked` marks the chosen row of a choice, `selected` the highlighted tab.
"""
import argparse
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(__file__))
import compare_app_state as cas  # noqa: E402

PKG = "io.github.yuubiz.folirel"
EXPECTED_ORDER = ["Zeta", "Gamma", "Epsilon", "Alpha", "Beta", "Delta", "Eta", "Theta"]
EXPECTED_PINNED = ["Zeta", "Gamma"]
# What each category's display panel must show: (layout row that is checked, arrangement row, columns row) - None: not looked at.
EXPECTED_LAYOUT = {
    "Delta": ("Compact list", None, None),
    "Beta": ("Magazine", "Same size for every card", "2 columns"),
    "Eta": ("Use the default (List)", None, "2 columns"),
    "Epsilon": ("Use the default (List)", None, None),
}
TABLES = {"accounts": ("id",), "feeds": ("id",), "categories": ("id",), "feed_categories": ("feedId", "categoryId"),
          "articles": ("id",), "article_states": ("articleId",), "category_preferences": ("categoryId",)}


def find_adb():
    exe = "adb.exe" if os.name == "nt" else "adb"
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(var)
        if root and os.path.exists(os.path.join(root, "platform-tools", exe)):
            return os.path.join(root, "platform-tools", exe)
    return shutil.which("adb") or exe


ADB = find_adb()
results = []


def adb(*a, binary=False, check=False):
    out = subprocess.run([ADB] + list(a), capture_output=True)
    if check and out.returncode:
        raise SystemExit(f"adb {' '.join(a)} failed: {out.stderr.decode('utf-8', 'replace')}")
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS  " if ok else "FAIL  ") + name + (f"  [{detail}]" if detail and not ok else ""))
    if ok and detail:
        print("      " + detail)


# ---- reading the screen

def dump():
    for _ in range(4):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        raw = adb("shell", "cat", "/sdcard/ui.xml")
        if raw.strip().startswith("<?xml"):
            return ET.fromstring(raw[raw.index("<?xml"):].encode("utf-8"))
        time.sleep(1)
    raise SystemExit("uiautomator dump failed")


def bounds(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return x1, y1, x2, y2


def centre(n):
    x1, y1, x2, y2 = bounds(n)
    return (x1 + x2) // 2, (y1 + y2) // 2


def texts(root):
    return [n.get("text") for n in root.iter("node") if n.get("text")]


def find_text(root, text, exact=True):
    for n in root.iter("node"):
        t = n.get("text") or ""
        if (t == text) if exact else (text in t):
            return n
    return None


def find_desc(root, desc):
    """The topmost node with this description (the toolbar button, not the one on an article row)."""
    hits = [n for n in root.iter("node") if n.get("content-desc") == desc]
    return min(hits, key=lambda n: bounds(n)[1]) if hits else None


def tap(n):
    x, y = centre(n)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1.2)


def wait_for(predicate, seconds=12):
    end = time.time() + seconds
    while time.time() < end:
        root = dump()
        found = predicate(root)
        if found is not None and found is not False:
            return root
        time.sleep(0.8)
    return None


def back():
    adb("shell", "input", "keyevent", "4")
    time.sleep(1.0)


def parent_map(root):
    return {c: p for p in root.iter("node") for c in p}


def checked_rows(root):
    """Texts inside a row that is `checked` (a choice that is on): one row of each group."""
    parents = parent_map(root)
    out = []
    for n in root.iter("node"):
        if n.get("checked") == "true":
            out += [t.get("text") for t in n.iter("node") if t.get("text")]
    return out


def selected_tab_text(root):
    for n in root.iter("node"):
        if n.get("selected") == "true" and not n.get("text"):
            x1, y1, x2, y2 = bounds(n)
            for t in root.iter("node"):
                if t.get("text") and x1 <= centre(t)[0] <= x2 and y1 <= centre(t)[1] <= y2:
                    return t.get("text")
    return None


def subtitle_node(root):
    """The small 'range . order' line under the toolbar title."""
    for n in root.iter("node"):
        if re.fullmatch(r"(All|Unread|Saved) · (Newest|Oldest) first", n.get("text") or ""):
            return n
    return None


def title_text(root):
    """The category name in the toolbar: the text just above the 'range . order' line."""
    sub = subtitle_node(root)
    if sub is None:
        return None
    x1, y1, x2, y2 = bounds(sub)
    best = None
    for n in root.iter("node"):
        if n.get("text") and n is not sub:
            nx1, ny1, nx2, ny2 = bounds(n)
            if ny2 <= y1 + 4 and ny1 >= y1 - 120 and abs(nx1 - x1) < 20:
                best = n
    return best.get("text") if best is not None else None


# ---- state copies

def pull_state(prefix, out):
    for ext in ("", "-wal", "-shm"):
        data = adb("exec-out", "run-as", PKG, "cat", f"databases/folirel.db{ext}", binary=True)
        with open(os.path.join(out, f"{prefix}-folirel.db{ext}"), "wb") as f:
            f.write(data)
    data = adb("exec-out", "run-as", PKG, "cat", "files/datastore/settings.preferences_pb", binary=True)
    with open(os.path.join(out, f"{prefix}-settings.preferences_pb"), "wb") as f:
        f.write(data)
    return os.path.join(out, prefix)


def settings_file_stat():
    return adb("shell", "run-as", PKG, "stat", "-c", "%Y.%s", "files/datastore/settings.preferences_pb").strip()


def db_rows(prefix):
    conn = cas.open_copy(prefix)
    return {t: cas.rows(conn, t, k) for t, k in TABLES.items()}


def settings_of(prefix):
    with open(f"{prefix}-settings.preferences_pb", "rb") as f:
        return cas.decode_preferences(f.read())


def launch():
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(4)


def pid():
    return adb("shell", "pidof", PKG).strip()


# ---- what the screen shows

def organizer_order():
    root = dump()
    tap(find_desc(root, "More options"))
    root = wait_for(lambda r: find_text(r, "Organize categories and tabs"))
    tap(find_text(root, "Organize categories and tabs"))
    root = wait_for(lambda r: find_text(r, "Tab order (the hierarchy is not changed)"))
    ts = texts(root)
    # A short screen shows only the first rows: read the rest after scrolling, keeping the order of first appearance.
    for _scroll in range(4):
        if all(n in ts for n in EXPECTED_ORDER):
            break
        adb("shell", "input", "swipe", "540", "1800", "540", "500", "400")
        time.sleep(1.5)
        for t in texts(dump()):
            if t not in ts:
                ts.append(t)
    names = [t for t in ts if t in EXPECTED_ORDER]
    pinned_header = ts.index("Pinned") if "Pinned" in ts else None
    others_header = ts.index("Other categories") if "Other categories" in ts else None
    first_other = ts.index(names[len(EXPECTED_PINNED)]) if len(names) > len(EXPECTED_PINNED) else None
    back()
    return names, pinned_header, others_header, first_other


def panel_layout(category):
    """Selects [category] in the picker if needed, opens the display panel and returns the checked rows (and the details sheet's)."""
    root = wait_for(lambda r: subtitle_node(r) is not None, 12)  # back on the home screen (it follows the organizer or a sheet)
    if root is None:
        raise SystemExit("the home screen did not come back")
    if title_text(root) != category:
        tap(subtitle_node(root))
        root = wait_for(lambda r: any(n.get("text") == category for n in r.iter("node")) and find_text(r, "Search categories", exact=False) is not None)
        row = None
        for n in root.iter("node"):
            if n.get("text") == category:
                row = n
        tap(row)
        wait_for(lambda r: title_text(r) == category)
    root = None
    for _attempt in range(3):  # a tap that lands while a sheet or the keyboard is still moving is simply made again
        tap(find_desc(dump(), "Display and filters"))
        root = wait_for(lambda r: find_text(r, "Layout of this category") is not None, 6)
        if root is not None:
            break
        back()
    if root is None:
        raise SystemExit(f"the display panel of {category} did not open")
    layout = [t for t in checked_rows(root) if t in ("Use the default (List)", "Use the default (Compact list)", "Use the default (Magazine)", "List", "Compact list", "Magazine")]
    details = []
    if find_text(root, "Arrangement and columns…") is not None:
        tap(find_text(root, "Arrangement and columns…"))
        droot = wait_for(lambda r: find_text(r, f"Display of {category}") is not None)
        details = checked_rows(droot)
        # The sheet is taller than the screen: look at the rest as well.
        for _scroll in range(3):  # a short screen needs more than one scroll to reach the columns
            adb("shell", "input", "swipe", "540", "1900", "540", "700", "400")
            time.sleep(1)
            details += checked_rows(dump())
        back()
    back()
    return layout, details


def observe(label, out):
    """Everything the screen can tell about the saved settings; returns a dict and prints PASS/FAIL against what was set."""
    root = dump()
    check(f"{label}: the selected category is Delta (title and highlighted tab)", title_text(root) == "Delta" and selected_tab_text(root) == "Delta",
          f"title={title_text(root)} tab={selected_tab_text(root)}")
    names, pinned_header, others_header, first_other = organizer_order()
    check(f"{label}: order of the tabs (organizer)", names == EXPECTED_ORDER, f"{names}")
    check(f"{label}: pinned group = Zeta, Gamma, the others after the 'Other categories' header",
          pinned_header is not None and others_header is not None and pinned_header < others_header < first_other, f"headers {pinned_header}/{others_header}/{first_other}")
    for category, (layout, arrangement, columns) in EXPECTED_LAYOUT.items():
        got_layout, got_details = panel_layout(category)
        ok = layout in got_layout
        if arrangement:
            ok = ok and arrangement in got_details
        if columns:
            ok = ok and columns in got_details
        check(f"{label}: {category} shows layout '{layout}'" + (f", arrangement '{arrangement}'" if arrangement else "") + (f", columns '{columns}'" if columns else ""),
              ok, f"layout={got_layout} details={got_details}")
    # The reader was only looking: the panel and the picker wrote nothing new except the last selected category.


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--test-apk", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--previous-apk", help="install this release first, store the state in it, then install --apk over it (no uninstall) before restarting")
    parser.add_argument("--keep-install", action="store_true", help="do not uninstall and set up again")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    if not args.keep_install:
        adb("uninstall", PKG)
        print(adb("install", args.previous_apk or args.apk, check=True).strip().splitlines()[-1])
        adb("install", "-t", args.test_apk, check=True)
        adb("shell", "svc", "power", "stayon", "true")
        out = adb("shell", "am", "instrument", "-w", "-e", "p1evidence", "true", "-e", "class", "io.github.yuubiz.folirel.P1RestoreSetup",
                  f"{PKG}.test/androidx.test.runner.AndroidJUnitRunner")
        check("setup helper ran", "OK (1 test)" in out, out[-300:])
    # BEFORE: what the setup stored (nothing has been started yet).
    before = pull_state("before", args.out)
    stat_before = settings_file_stat()
    rows_before, settings_before = db_rows(before), settings_of(before)
    prefs = rows_before["category_preferences"]
    check("setup stored preference rows for the changed categories", len(prefs) == 8, f"{len(prefs)} rows")
    check("selected category stored", settings_before.get("last_scope", (None, ""))[1].startswith("category:"), f"{settings_before.get('last_scope')}")

    if args.previous_apk and not args.keep_install:
        # The state was stored by the previous release; this release is installed over it, the data stays.
        print(adb("install", "-r", args.apk, check=True).strip().splitlines()[-1])
        adb("shell", "svc", "power", "stayon", "true")
        # The test APK is installed again (it has to match this release's classes).
        adb("install", "-r", "-t", args.test_apk, check=True)

    # ---- normal restart
    adb("shell", "am", "force-stop", PKG)
    launch()
    wait_for(lambda r: title_text(r) is not None)
    # Straight after the screen is up, before anything is touched: nothing may have been written (a transient scope or index must not be saved).
    after_launch = pull_state("after-launch", args.out)
    check("restart: the settings file was not written while the screen was built (same time and size)", settings_file_stat() == stat_before, f"{stat_before} -> {settings_file_stat()}")
    check("restart: every setting, including the selected category, is as it was", settings_of(after_launch) == settings_before)
    check("restart: database rows are equal (articles, states, categories, memberships, category_preferences)", db_rows(after_launch) == rows_before)
    observe("restart", args.out)
    adb("shell", "screencap", "-p", "/sdcard/p1-t29-restart.png")
    adb("pull", "/sdcard/p1-t29-restart.png", os.path.join(args.out, "restart.png"))

    # ---- process death with the saved screen state
    adb("shell", "am", "force-stop", PKG)
    launch()
    root = dump()
    tap(subtitle_node(root))
    root = wait_for(lambda r: any(n.get("text") == "Epsilon" for n in r.iter("node")) and find_text(r, "Search categories", exact=False) is not None)
    row = [n for n in root.iter("node") if n.get("text") == "Epsilon"][-1]
    tap(row)
    root = wait_for(lambda r: title_text(r) == "Epsilon")
    check("choice: Epsilon selected through the picker", root is not None)
    tap(find_desc(dump(), "Search"))
    root = wait_for(lambda r: any(n.get("class", "").endswith("EditText") for n in r.iter("node")))
    tap([n for n in root.iter("node") if n.get("class", "").endswith("EditText")][0])
    adb("shell", "input", "text", "12")
    # The search action of the keyboard closes it: on API 28 uiautomator reads a stale screen while the keyboard is up.
    adb("shell", "input", "keyevent", "66")
    time.sleep(1.5)
    # The list follows the typed text after a short debounce; a slower emulator needs longer.
    root = wait_for(lambda r: bool([t for t in texts(r) if t.startswith("Epsilon 記事")]) and all("12" in t for t in texts(r) if t.startswith("Epsilon 記事")), 15) or dump()
    shown = [t for t in texts(root) if t.startswith("Epsilon 記事")]
    check("search '12' is on screen before the kill", bool(shown) and all("12" in t for t in shown), f"{shown[:3]}")
    pre_kill = pull_state("pre-kill", args.out)
    stat_before_kill = settings_file_stat()
    pid_before = pid()
    adb("shell", "input", "keyevent", "3")  # Home: the app goes to the background with its screen state saved
    time.sleep(2)
    # `am kill` only takes a background (cached) process; it may need a moment after Home before the app counts as one.
    for _attempt in range(10):
        adb("shell", "am", "kill", PKG)
        time.sleep(1.5)
        if pid() == "":
            break
    check("the process is gone", pid() == "", f"pid before {pid_before}, now '{pid()}'")
    launch()
    pid_after = pid()
    check("a new process was started", bool(pid_after) and pid_after != pid_before, f"{pid_before} -> {pid_after}")
    root = wait_for(lambda r: selected_tab_text(r) == "Epsilon", 15)
    check("restored: the same category is shown (highlighted tab)", root is not None, f"tab={selected_tab_text(dump())}")
    time.sleep(1.5)
    root = dump()
    shown = [t for t in texts(root) if t.startswith("Epsilon 記事")]
    edit = [n.get("text") for n in root.iter("node") if n.get("class", "").endswith("EditText")]
    check("restored: the search text came back from the saved screen state", "12" in edit and bool(shown) and all("12" in t for t in shown), f"edit={edit} articles={shown[:3]}")
    adb("shell", "screencap", "-p", "/sdcard/p1-t29-restored.png")
    adb("pull", "/sdcard/p1-t29-restored.png", os.path.join(args.out, "restored.png"))
    stat_after_restore = settings_file_stat()
    restored = pull_state("after-restore", args.out)
    check("restored: the settings file was not written at all while the screen was rebuilt (same time and size)", stat_after_restore == stat_before_kill,
          f"{stat_before_kill} -> {stat_after_restore}")
    rows_restored, settings_restored = db_rows(restored), settings_of(restored)
    rows_pre = db_rows(pre_kill)
    check("restored: database rows are equal to before the kill", rows_restored == rows_pre)
    check("restored: every setting is equal to before the kill (selected category Epsilon)", settings_restored == settings_of(pre_kill) and settings_restored.get("last_scope", (None, ""))[1].startswith("category:"),
          f"{settings_restored.get('last_scope')}")
    # The order, pins and overrides again, after the restoration (the dialogs and the picker are used to look; they select categories).
    tap(find_desc(dump(), "Close search"))  # leaves the search (it is not part of what is being checked from here on)
    time.sleep(1)
    names, pinned_header, others_header, first_other = organizer_order()
    check("restored: order of the tabs and the pinned group", names == EXPECTED_ORDER and pinned_header < others_header < first_other, f"{names}")
    for category, (layout, arrangement, columns) in EXPECTED_LAYOUT.items():
        got_layout, got_details = panel_layout(category)
        ok = layout in got_layout and (not arrangement or arrangement in got_details) and (not columns or columns in got_details)
        check(f"restored: {category} shows layout '{layout}'", ok, f"layout={got_layout} details={got_details}")
    final = pull_state("final", args.out)
    rows_final = db_rows(final)
    check("the preference rows are the same at the end (looking changed nothing)", rows_final["category_preferences"] == rows_before["category_preferences"])
    print(f"RESULT: {'OK' if all(results) else 'FAILED'} ({sum(results)}/{len(results)} checks passed)")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
