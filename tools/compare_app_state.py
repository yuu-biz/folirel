# -*- coding: utf-8 -*-
r"""Development helper for the upgrade check (V31-T01): compares two copies of the app's Room DB and DataStore and
FAILS (exit code 1) when something that must not change did.

  python -X utf8 tools/compare_app_state.py --mode install       BEFORE_PREFIX AFTER_PREFIX
  python -X utf8 tools/compare_app_state.py --mode after-refresh BEFORE_PREFIX AFTER_PREFIX

Each prefix names the files `<prefix>-folirel.db` (+ `-wal`, `-shm`) and `<prefix>-settings.preferences_pb`, pulled with
`adb exec-out run-as ... cat`. Not used by the app or CI; its own tests: `python -X utf8 -m unittest tools.test_compare_app_state`.

Modes
  install        right after the overwrite install, before any fetch: nothing may be added, removed or changed in the
                 subscriptions, categories, articles and reading state, and the settings must be equal key by key and
                 value by value (the DataStore protobuf is decoded; the file bytes are not compared).
  after-refresh  after one ordinary refresh: new articles are allowed, and an existing article may have the columns a
                 refresh rewrites (see REFRESH_COLUMNS); its id, key, feed and reading/saved state may not change.
                 User settings must be equal except the refresh bookkeeping keys (SETTINGS_ALLOWED_TO_MOVE).

Duplicate links: one link in one feed can legitimately belong to two articles (a feed may list it twice), so existing
duplicates are the baseline; a (feed, link) that has MORE articles after than before is an error in both modes.
"""
import argparse
import os
import shutil
import sqlite3
import sys
import tempfile

# Rows that must keep their primary key and (in install mode) every column.
TABLES = {
    "accounts": ("id",),
    "feeds": ("id",),
    "categories": ("id",),
    "feed_categories": ("feedId", "categoryId"),
    "articles": ("id",),
    "article_states": ("articleId",),
}

# After a refresh these columns may change on an existing row (what updateMetadata / updateFeedMetadata write).
REFRESH_COLUMNS = {
    "articles": {"lastSeenAt", "title", "summaryText", "author", "publishedAt", "imageUrl", "linkUrl"},
    "feeds": {"feedTitle", "siteUrl"},
}

# Settings that move by themselves with a refresh (not user choices).
SETTINGS_ALLOWED_TO_MOVE = {"last_refresh_attempt"}


# ---- DataStore preferences (protobuf) --------------------------------------------------------------------------

def _varint(data, i):
    result = shift = 0
    while True:
        b = data[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7


def _fields(data):
    """Yields (field_number, wire_type, value) of one protobuf message."""
    i = 0
    while i < len(data):
        tag, i = _varint(data, i)
        number, wire = tag >> 3, tag & 7
        if wire == 0:
            value, i = _varint(data, i)
        elif wire == 1:
            value, i = data[i:i + 8], i + 8
        elif wire == 2:
            length, i = _varint(data, i)
            value, i = data[i:i + length], i + length
        elif wire == 5:
            value, i = data[i:i + 4], i + 4
        else:
            raise ValueError(f"unsupported wire type {wire}")
        yield number, wire, value


def decode_preferences(data):
    """androidx.datastore PreferenceMap -> {key: (type, value)}; the type is part of what is compared."""
    names = {1: "boolean", 2: "float", 3: "integer", 4: "long", 5: "string", 6: "string_set", 7: "double", 8: "bytes"}
    out = {}
    for number, _, entry in _fields(data):
        if number != 1:
            continue
        key = value = None
        for n, _, v in _fields(entry):
            if n == 1:
                key = v.decode("utf-8")
            elif n == 2:
                for vn, _, vv in _fields(v):
                    kind = names.get(vn, f"field{vn}")
                    if kind == "string":
                        vv = vv.decode("utf-8")
                    elif kind == "string_set":
                        vv = tuple(sorted(x.decode("utf-8") for _, _, x in _fields(vv)))
                    elif kind == "boolean":
                        vv = bool(vv)
                    value = (kind, vv)
        if key is not None:
            out[key] = value
    return out


def encode_preferences(prefs):
    """Inverse of decode_preferences for boolean/integer/long/string (used by the tests to build fixtures)."""
    def varint(n):
        out = bytearray()
        while True:
            b = n & 0x7F
            n >>= 7
            out.append(b | (0x80 if n else 0))
            if not n:
                return bytes(out)

    def field(number, wire, payload):
        return varint(number << 3 | wire) + payload

    def length_delimited(number, payload):
        return field(number, 2, varint(len(payload)) + payload)

    numbers = {"boolean": 1, "integer": 3, "long": 4, "string": 5}
    out = b""
    for key, (kind, value) in prefs.items():
        if kind == "string":
            inner = length_delimited(numbers[kind], value.encode("utf-8"))
        elif kind == "boolean":
            inner = field(numbers[kind], 0, varint(int(value)))
        else:
            inner = field(numbers[kind], 0, varint(value))
        out += length_delimited(1, length_delimited(1, key.encode("utf-8")) + length_delimited(2, inner))
    return out


# ---- comparison ------------------------------------------------------------------------------------------------

def open_copy(prefix):
    tmp = tempfile.mkdtemp()
    for ext in ("", "-wal", "-shm"):
        src = f"{prefix}-folirel.db{ext}"
        if os.path.exists(src):
            shutil.copy(src, os.path.join(tmp, f"app.db{ext}"))
    return sqlite3.connect(os.path.join(tmp, "app.db"))


def rows(conn, table, key_columns):
    cur = conn.execute(f"select * from {table}")
    cols = [d[0] for d in cur.description]
    return {tuple(r[cols.index(k)] for k in key_columns): dict(zip(cols, r)) for r in cur.fetchall()}


def table_exists(conn, name):
    return conn.execute("select 1 from sqlite_master where type = 'table' and name = ?", (name,)).fetchone() is not None


def link_counts(conn):
    return {(f, l): n for f, l, n in conn.execute(
        "select feedId, linkUrl, count(*) from articles where linkUrl is not null group by feedId, linkUrl")}


def compare(before_conn, after_conn, before_settings, after_settings, mode):
    """Returns the list of problems (empty = the state is as it must be) and a list of informational lines."""
    if mode not in ("install", "after-refresh"):
        raise ValueError(mode)
    problems, info = [], []
    for table, key_columns in TABLES.items():
        before, after = rows(before_conn, table, key_columns), rows(after_conn, table, key_columns)
        allowed = REFRESH_COLUMNS.get(table, set()) if mode == "after-refresh" else set()
        missing = [k for k in before if k not in after]
        added = [k for k in after if k not in before]
        changed = {}
        for k in before:
            if k in after:
                diff = {c: (before[k][c], after[k].get(c)) for c in before[k] if before[k][c] != after[k].get(c) and c not in allowed}
                if diff:
                    changed[k] = diff
        info.append(f"{table}: before={len(before)} after={len(after)} missing={len(missing)} changed={len(changed)} added={len(added)}")
        problems += [f"{table}: row {k} is missing after" for k in missing]
        problems += [f"{table}: row {k} changed: {d}" for k, d in changed.items()]
        if mode == "install":
            problems += [f"{table}: row {k} was added" for k in added]
        else:
            # New articles (and their reading state) are what a refresh brings; nothing else may appear.
            if table not in ("articles", "article_states"):
                problems += [f"{table}: row {k} was added" for k in added]
    if mode == "after-refresh":
        new_articles = {k[0] for k in rows(after_conn, "articles", ("id",)) if k not in rows(before_conn, "articles", ("id",))}
        new_states = {k[0] for k in rows(after_conn, "article_states", ("articleId",)) if k not in rows(before_conn, "article_states", ("articleId",))}
        problems += [f"article_states: state of new article {a} is missing" for a in new_articles - new_states]
        problems += [f"article_states: state for {a} has no article" for a in new_states - new_articles]
    # Schema 4 adds category_preferences (the intended difference). Updating chooses nothing for the reader, so it holds no row: every
    # category has the defaults (no saved place, not pinned, nothing overridden).
    if table_exists(after_conn, "category_preferences"):
        n = after_conn.execute("select count(*) from category_preferences").fetchone()[0]
        info.append(f"category_preferences: new table, {n} rows")
        if n:
            problems.append(f"category_preferences: {n} rows right after the update (every category must have the defaults)")
    cb, ca = link_counts(before_conn), link_counts(after_conn)
    for key, n in ca.items():
        if n > 1 and n > cb.get(key, 0):
            problems.append(f"new duplicate link in feed {key[0]}: {key[1]} is now on {n} articles (was {cb.get(key, 0)})")
    keep_read = sum(1 for r in before_conn.execute("select isRead from article_states") if r[0])
    keep_saved = sum(1 for r in before_conn.execute("select isSaved from article_states") if r[0])
    info.append(f"read/saved before: {keep_read}/{keep_saved}")

    moved = SETTINGS_ALLOWED_TO_MOVE if mode == "after-refresh" else set()
    for key in sorted(set(before_settings) | set(after_settings)):
        if key in moved:
            continue
        if before_settings.get(key) != after_settings.get(key):
            problems.append(f"setting {key}: {before_settings.get(key)} -> {after_settings.get(key)}")
    info.append(f"settings: {len(before_settings)} keys before, {len(after_settings)} after (decoded, typed)")
    return problems, info


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", required=True, choices=["install", "after-refresh"])
    parser.add_argument("before")
    parser.add_argument("after")
    args = parser.parse_args(argv)
    with open(f"{args.before}-settings.preferences_pb", "rb") as f:
        sb = decode_preferences(f.read())
    with open(f"{args.after}-settings.preferences_pb", "rb") as f:
        sa = decode_preferences(f.read())
    problems, info = compare(open_copy(args.before), open_copy(args.after), sb, sa, args.mode)
    print("\n".join(info))
    for p in problems:
        print("PROBLEM:", p)
    print(f"RESULT ({args.mode}):", "OK" if not problems else f"{len(problems)} PROBLEM(S)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
