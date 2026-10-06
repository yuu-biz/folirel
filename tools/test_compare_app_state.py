# -*- coding: utf-8 -*-
"""Tests of tools/compare_app_state.py itself (V31-C01): the checker must really fail on the differences it is for.

Run: python -X utf8 -m unittest tools.test_compare_app_state   (from the repository root)
The databases are built from the app's exported Room schema (app/schemas/.../3.json), so the tables are the real ones.
"""
import json
import os
import sqlite3
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import compare_app_state as cas  # noqa: E402

SCHEMA = os.path.join(os.path.dirname(__file__), "..", "app", "schemas", "io.github.yuubiz.folirel.data.db.AppDatabase", "3.json")


def new_db():
    conn = sqlite3.connect(":memory:")
    with open(SCHEMA, encoding="utf-8") as f:
        entities = json.load(f)["database"]["entities"]
    for e in entities:
        conn.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    return conn


def seed(conn):
    conn.execute("insert into accounts values ('acc','LOCAL','Local',1)")
    conn.execute("insert into feeds values ('f1','acc','https://a/rss','https://a/rss',null,'Feed',null,1,0,1)")
    conn.execute("insert into categories values ('c1','acc','Tech','[\"Tech\"]')")
    conn.execute("insert into feed_categories values ('f1','c1')")
    for i in (1, 2, 3):
        conn.execute(
            "insert into articles values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (f"a{i}", "acc", "f1", f"id:{i}", None, f"https://a/{i}", f"title {i}", "s", None, 1000 + i, 1000 + i, 1000 + i, None, 2000),
        )
        conn.execute("insert into article_states values (?,?,?,?)", (f"a{i}", 0, 0, 1))
    conn.execute("update article_states set isRead = 1 where articleId = 'a1'")
    conn.execute("update article_states set isSaved = 1 where articleId = 'a2'")
    conn.commit()
    return conn


SETTINGS = {
    "confirm_http_open": ("boolean", False),
    "card_columns": ("integer", 2),
    "theme": ("string", "DARK"),
    "refresh_interval_minutes": ("integer", 360),
    "last_refresh_attempt": ("long", 111),
}


def run(mode, mutate=None, settings_after=None):
    before = seed(new_db())
    after = seed(new_db())
    if mutate:
        mutate(after)
        after.commit()
    sa = dict(SETTINGS)
    if settings_after:
        sa.update(settings_after)
    return cas.compare(before, after, dict(SETTINGS), sa, mode)[0]


class CompareAppStateTest(unittest.TestCase):
    def assertFails(self, problems, text):
        self.assertTrue(any(text in p for p in problems), f"expected a problem containing {text!r}, got {problems}")

    def test_identical_state_passes_in_both_modes(self):
        self.assertEqual([], run("install"))
        self.assertEqual([], run("after-refresh"))

    def test_a_changed_boolean_setting_fails_in_both_modes(self):
        for mode in ("install", "after-refresh"):
            self.assertFails(run(mode, settings_after={"confirm_http_open": ("boolean", True)}), "confirm_http_open")

    def test_a_changed_value_or_type_of_a_setting_fails(self):
        self.assertFails(run("install", settings_after={"card_columns": ("integer", 3)}), "card_columns")
        self.assertFails(run("install", settings_after={"theme": ("string", "LIGHT")}), "theme")
        self.assertFails(run("install", settings_after={"card_columns": ("long", 2)}), "card_columns")

    def test_a_missing_or_new_setting_fails(self):
        before, after = dict(SETTINGS), dict(SETTINGS)
        del after["theme"]
        after["new_key"] = ("boolean", True)
        problems = cas.compare(seed(new_db()), seed(new_db()), before, after, "install")[0]
        self.assertFails(problems, "theme")
        self.assertFails(problems, "new_key")

    def test_refresh_bookkeeping_setting_may_move_only_after_a_refresh(self):
        moved = {"last_refresh_attempt": ("long", 999)}
        self.assertEqual([], run("after-refresh", settings_after=moved))
        self.assertFails(run("install", settings_after=moved), "last_refresh_attempt")

    def test_settings_are_compared_decoded_not_as_bytes(self):
        a = cas.encode_preferences(SETTINGS)
        b = cas.encode_preferences(dict(reversed(list(SETTINGS.items()))))
        self.assertNotEqual(a, b)  # another order is another byte string ...
        self.assertEqual(cas.decode_preferences(a), cas.decode_preferences(b))  # ... but the same settings
        flipped = dict(SETTINGS, confirm_http_open=("boolean", True))
        self.assertNotEqual(cas.decode_preferences(a), cas.decode_preferences(cas.encode_preferences(flipped)))

    def test_a_real_datastore_file_is_decoded_to_the_values_that_were_set(self):
        # Written by the real 0.3.0 app on an emulator after these choices were made in its settings screen.
        path = os.path.join(os.path.dirname(__file__), "fixtures", "datastore-settings-0.3.0.preferences_pb")
        with open(path, "rb") as f:
            prefs = cas.decode_preferences(f.read())
        self.assertEqual(("boolean", False), prefs["confirm_http_open"])
        self.assertEqual(("string", "CARD"), prefs["list_mode"])
        self.assertEqual(("string", "EVEN"), prefs["card_style"])
        self.assertEqual(("integer", 2), prefs["card_columns"])
        self.assertEqual(("string", "DARK"), prefs["theme"])
        self.assertEqual(("integer", 360), prefs["refresh_interval_minutes"])
        self.assertEqual(("long", 1791046343529), prefs["last_refresh_attempt"])

    def test_a_new_link_duplicate_fails_in_both_modes(self):
        def duplicate(conn):
            conn.execute(
                "insert into articles values ('a9','acc','f1','url:https://a/1',null,'https://a/1','again','s',null,5,5,5,null,2000)")
            conn.execute("insert into article_states values ('a9',0,0,1)")
        self.assertFails(run("after-refresh", duplicate), "new duplicate link")
        self.assertFails(run("install", duplicate), "new duplicate link")

    def test_a_duplicate_that_already_existed_is_baseline(self):
        before, after = seed(new_db()), seed(new_db())
        for conn in (before, after):
            conn.execute(
                "insert into articles values ('a8','acc','f1','url:https://a/1',null,'https://a/1','listed twice','s',null,5,5,5,null,2000)")
            conn.execute("insert into article_states values ('a8',0,0,1)")
        self.assertEqual([], cas.compare(before, after, dict(SETTINGS), dict(SETTINGS), "install")[0])

    def test_a_changed_read_or_saved_state_fails(self):
        for sql in ("update article_states set isRead = 0 where articleId = 'a1'", "update article_states set isSaved = 0 where articleId = 'a2'"):
            for mode in ("install", "after-refresh"):
                self.assertFails(run(mode, lambda c, s=sql: c.execute(s)), "article_states")

    def test_a_new_article_is_allowed_only_after_a_refresh(self):
        def new_article(conn):
            conn.execute(
                "insert into articles values ('n1','acc','f1','id:new',null,'https://a/new','new','s',null,9,9,9,null,3000)")
            conn.execute("insert into article_states values ('n1',0,0,3)")
        self.assertEqual([], run("after-refresh", new_article))
        self.assertFails(run("install", new_article), "was added")

    def test_a_new_article_without_state_fails(self):
        def stateless(conn):
            conn.execute(
                "insert into articles values ('n1','acc','f1','id:new',null,'https://a/new','new','s',null,9,9,9,null,3000)")
        self.assertFails(run("after-refresh", stateless), "state of new article")

    def test_what_a_refresh_rewrites_is_allowed_but_identity_is_not(self):
        self.assertEqual([], run("after-refresh", lambda c: c.execute("update articles set title = 'edited', lastSeenAt = 3000 where id = 'a1'")))
        self.assertFails(run("install", lambda c: c.execute("update articles set title = 'edited' where id = 'a1'")), "articles")
        self.assertFails(run("after-refresh", lambda c: c.execute("update articles set entryKey = 'id:other' where id = 'a1'")), "entryKey")
        self.assertFails(run("after-refresh", lambda c: c.execute("update feeds set fetchUrl = 'https://b/rss' where id = 'f1'")), "fetchUrl")

    def test_removed_rows_fail(self):
        self.assertFails(run("after-refresh", lambda c: c.execute("delete from article_states where articleId = 'a3'")), "missing")
        self.assertFails(run("after-refresh", lambda c: c.execute("delete from feed_categories")), "missing")


class CategoryPreferencesTableTest(unittest.TestCase):
    """Schema 4 adds category_preferences: the new table is expected, but it must be empty right after the update."""

    @staticmethod
    def with_table(conn, rows=0):
        conn.execute("create table category_preferences (categoryId text not null primary key, tabOrder integer, isPinned integer not null default 0, "
                     "listModeOverride text, cardStyleOverride text, cardColumnsOverride integer)")
        for i in range(rows):
            conn.execute("insert into category_preferences (categoryId) values (?)", (f"c{i}",))
        conn.commit()

    def test_a_new_empty_table_is_the_intended_difference(self):
        before, after = seed(new_db()), seed(new_db())
        self.with_table(after)
        problems, info = cas.compare(before, after, dict(SETTINGS), dict(SETTINGS), "install")
        self.assertEqual([], problems)
        self.assertIn("category_preferences: new table, 0 rows", info)

    def test_a_row_right_after_the_update_fails(self):
        problems = run("install", lambda after: self.with_table(after, rows=1))
        self.assertTrue(any("category_preferences" in p for p in problems), problems)

    def test_rows_that_the_previous_release_already_had_must_stay_exactly(self):
        before, after = seed(new_db()), seed(new_db())
        self.with_table(before, rows=3)
        self.with_table(after, rows=3)
        problems, info = cas.compare(before, after, dict(SETTINGS), dict(SETTINGS), "install")
        self.assertEqual([], problems)
        self.assertIn("category_preferences: kept, 3 rows before, 3 after", info)
        after.execute("update category_preferences set isPinned = 1 where categoryId = 'c1'")
        problems, _ = cas.compare(before, after, dict(SETTINGS), dict(SETTINGS), "install")
        self.assertTrue(any("category_preferences" in p for p in problems), problems)
        after.execute("update category_preferences set isPinned = 0 where categoryId = 'c1'")
        after.execute("delete from category_preferences where categoryId = 'c2'")
        problems, _ = cas.compare(before, after, dict(SETTINGS), dict(SETTINGS), "install")
        self.assertTrue(any("category_preferences" in p for p in problems), problems)

    def test_user_data_is_still_compared_with_the_new_table_present(self):
        def mutate(after):
            self.with_table(after)
            after.execute("update article_states set isRead = 1 where articleId = 'a3'")
        problems = run("install", mutate)
        self.assertTrue(any("article_states" in p for p in problems), problems)


if __name__ == "__main__":
    unittest.main()
