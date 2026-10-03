# -*- coding: utf-8 -*-
"""Tests of tools/layout_upgrade_check.py itself: a failed install, a wrong app, a wrong setting or a changed screen must
never pass. Run: python -X utf8 -m unittest tools.test_layout_upgrade_check   (from the repository root)
A fake device stands in for adb; the real Adb wrapper is tested with a fake process runner."""
import os
import subprocess
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import layout_upgrade_check as luc  # noqa: E402

OLD, NEW = "old.apk", "new.apk"
SHA = {OLD: "a" * 64, NEW: "b" * 64}
# What each stored value draws (distinct lists, so that "the layouts differ" can be checked).
SCREEN = {"MAGAZINE": [("one", (0, 600, 900, 700))], "LIST": [("two", (0, 600, 900, 660))], "CARD": [("three", (0, 600, 450, 900))]}


class FakeDevice:
    """Behaves like the real app pair unless told to misbehave."""

    def __init__(self, **faults):
        self.faults = faults
        self.installed = None
        self.settings = {"theme": ("string", "DARK")}
        self.stored = None

    def install(self, apk):
        if self.faults.get("install_fails_for") == apk:
            raise luc.AdbError(f"install of {apk} did not report Success: Failure [INSTALL_FAILED]")
        if self.faults.get("silent_install_failure_for") == apk:
            return  # adb said nothing wrong, but the installed app stays what it was
        self.installed = apk

    def installed_identity(self):
        return SHA.get(self.installed, "0" * 64), "0.3.x", 5

    def force_stop(self):
        pass

    def start(self):
        if self.installed == NEW and self.faults.get("migration") != "none":
            # the new app migrates on its first start
            if "list_layout" not in self.settings and "list_mode" in self.settings:
                mapped = {"MAGAZINE": "LIST", "LIST": "COMPACT_LIST", "CARD": "MAGAZINE"}[self.settings["list_mode"][1]]
                if self.faults.get("migration") == "wrong":
                    mapped = "LIST"
                self.settings["list_layout"] = ("string", mapped)
            if self.faults.get("migration") == "drops_old_key":
                self.settings.pop("list_mode", None)

    def read_settings(self):
        return dict(self.settings)

    def write_settings(self, prefs):
        self.settings = dict(prefs)
        self.stored = prefs["list_mode"][1]

    def capture(self, tag):
        if self.faults.get("empty_screen"):
            return []
        if self.faults.get("same_layout_for_all"):
            return [("same", (0, 600, 900, 700))]
        screen = list(SCREEN[self.stored])
        if tag.endswith("after") and self.faults.get("screen_changes_after_upgrade"):
            screen = screen + [("extra", (0, 700, 900, 800))]
        return screen


def run(**faults):
    return luc.check_upgrade(FakeDevice(**faults), OLD, NEW, SHA[OLD], SHA[NEW], log=lambda *_: None)


class LayoutUpgradeCheckTest(unittest.TestCase):
    def assertProblem(self, problems, text):
        self.assertTrue(any(text in p for p in problems), f"expected a problem containing {text!r}, got {problems}")

    def test_a_correct_upgrade_passes(self):
        self.assertEqual([], run())

    def test_an_install_that_fails_stops_the_check_it_cannot_pass(self):
        for apk in (OLD, NEW):
            with self.assertRaises(luc.AdbError):
                run(install_fails_for=apk)

    def test_an_install_that_did_not_change_the_app_is_not_a_pass(self):
        # adb reports no error but the old app is still installed after "installing" the new one: the two screens would be the same app.
        self.assertProblem(run(silent_install_failure_for=NEW), "not NEW_APK")
        self.assertProblem(run(silent_install_failure_for=OLD), "not OLD_APK")

    def test_the_same_file_as_old_and_new_is_refused(self):
        problems = luc.check_upgrade(FakeDevice(), OLD, OLD, SHA[OLD], SHA[OLD], log=lambda *_: None)
        self.assertProblem(problems, "same file")

    def test_a_wrong_migrated_value_fails(self):
        self.assertProblem(run(migration="wrong"), "list_layout")

    def test_a_missing_migration_fails(self):
        self.assertProblem(run(migration="none"), "list_layout")

    def test_a_dropped_or_changed_old_key_fails(self):
        self.assertProblem(run(migration="drops_old_key"), "list_mode")

    def test_a_screen_that_changed_fails(self):
        self.assertProblem(run(screen_changes_after_upgrade=True), "screen changed")

    def test_an_empty_screen_fails(self):
        self.assertProblem(run(empty_screen=True), "nothing was on screen")

    def test_layouts_that_look_the_same_cannot_prove_anything(self):
        self.assertProblem(run(same_layout_for_all=True), "looked the same")


class FakeProcess:
    def __init__(self, code, out=b"", err=b""):
        self.returncode, self.stdout, self.stderr = code, out, err


class AdbWrapperTest(unittest.TestCase):
    def test_a_failing_adb_command_raises(self):
        adb = luc.Adb(adb_path="adb", runner=lambda cmd, capture_output: FakeProcess(1, b"", b"device offline"))
        with self.assertRaises(luc.AdbError) as ctx:
            adb.run("shell", "am", "start")
        self.assertIn("exit code 1", str(ctx.exception))
        self.assertIn("device offline", str(ctx.exception))

    def test_a_successful_command_returns_its_output(self):
        adb = luc.Adb(serial="emulator-5554", adb_path="adb", runner=lambda cmd, capture_output: FakeProcess(0, b"ok"))
        self.assertEqual("ok", adb.run("devices"))

    def test_install_without_success_text_is_an_error(self):
        out = {"install": b"Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"}
        adb = luc.Adb(adb_path="adb", runner=lambda cmd, capture_output: FakeProcess(0, out["install"]))
        with self.assertRaises(luc.AdbError):
            luc.Device(adb, "out").install("x.apk")

    def test_a_missing_apk_file_is_an_error_not_a_pass(self):
        self.assertEqual(2, luc.main([__file__, __file__ + ".does-not-exist"]))

    def test_main_exits_2_on_an_adb_error_and_1_on_a_problem(self):
        original = luc.check_upgrade
        try:
            luc.check_upgrade = lambda *a, **k: (_ for _ in ()).throw(luc.AdbError("boom"))
            self.assertEqual(2, luc.main([__file__, os.path.join(os.path.dirname(__file__), "compare_app_state.py")]))
            luc.check_upgrade = lambda *a, **k: ["a problem"]
            self.assertEqual(1, luc.main([__file__, os.path.join(os.path.dirname(__file__), "compare_app_state.py")]))
        finally:
            luc.check_upgrade = original


if __name__ == "__main__":
    unittest.main()
