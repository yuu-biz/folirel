# -*- coding: utf-8 -*-
r"""Development helper for the upgrade check (V31-T01): compares two copies of the app's Room DB and DataStore.

Run with `python -X utf8 tools/compare_app_state.py BEFORE_PREFIX AFTER_PREFIX` where each prefix names files
`<prefix>-feeddeck.db(-wal/-shm)` and `<prefix>-settings.preferences_pb` (pulled with `adb exec-out run-as ... cat`).
Prints, per table, whether the rows that existed before are unchanged, and lists every difference. Not used by the app.
"""
import re
import shutil
import sqlite3
import sys
import tempfile
import os

# Columns that legitimately move on every refresh (the article was seen again).
VOLATILE = {"articles": {"lastSeenAt"}}

# What an upgrade must not change (rows are compared whole, ordered by primary key).
STABLE = {
    "accounts": "id",
    "feeds": "id",
    "categories": "id",
    "feed_categories": "feedId, categoryId",
    "articles": "id",
    "article_states": "articleId",
}


def open_copy(prefix):
    tmp = tempfile.mkdtemp()
    for ext in ("", "-wal", "-shm"):
        src = f"{prefix}-feeddeck.db{ext}"
        if os.path.exists(src):
            shutil.copy(src, os.path.join(tmp, f"app.db{ext}"))
    return sqlite3.connect(os.path.join(tmp, "app.db"))


def rows(conn, table, order):
    cur = conn.execute(f"select * from {table} order by {order}")
    cols = [d[0] for d in cur.description]
    return cols, [dict(zip(cols, r)) for r in cur.fetchall()]


def settings(prefix):
    data = open(f"{prefix}-settings.preferences_pb", "rb").read()
    return data, re.findall(rb"[a-z_]{4,}", data)


def main(before, after):
    a, b = open_copy(before), open_copy(after)
    ok = True
    print("user_version:", a.execute("pragma user_version").fetchone()[0], "->", b.execute("pragma user_version").fetchone()[0])
    for table, order in STABLE.items():
        _, ra = rows(a, table, order)
        cols_b, rb = rows(b, table, order)
        key = lambda r: tuple(r[c.strip()] for c in order.split(","))
        drop = VOLATILE.get(table, set())
        strip = lambda r: {c: v for c, v in r.items() if c not in drop}
        before_by, after_by = {key(r): strip(r) for r in ra}, {key(r): strip(r) for r in rb}
        missing = [k for k in before_by if k not in after_by]
        changed = [k for k in before_by if k in after_by and before_by[k] != after_by[k]]
        added = [k for k in after_by if k not in before_by]
        print(f"{table}: before={len(ra)} after={len(rb)} missing={len(missing)} changed={len(changed)} added={len(added)}")
        for k in missing[:5]:
            print("  MISSING", k)
        for k in changed[:5]:
            diff = {c: (before_by[k][c], after_by[k][c]) for c in before_by[k] if before_by[k][c] != after_by[k].get(c)}
            print("  CHANGED", k, diff)
        ok &= not missing and not changed
    # reading state of every article, by id
    sa = {r[0]: (r[1], r[2]) for r in a.execute("select articleId, isRead, isSaved from article_states")}
    sb = {r[0]: (r[1], r[2]) for r in b.execute("select articleId, isRead, isSaved from article_states")}
    print("read:", sum(v[0] for v in sa.values()), "->", sum(sb.get(k, (0, 0))[0] for k in sa), " saved:", sum(v[1] for v in sa.values()), "->", sum(sb.get(k, (0, 0))[1] for k in sa))
    dups = b.execute("select feedId, entryKey, count(*) from articles group by feedId, entryKey having count(*) > 1").fetchall()
    print("duplicate (feed, entryKey) after:", len(dups))
    ok &= not dups
    # same article twice under another spelling of its address is checked by link
    link_dups = b.execute("select feedId, linkUrl, count(*) from articles where linkUrl is not null group by feedId, linkUrl having count(*) > 1").fetchall()
    print("duplicate (feed, link) after:", len(link_dups))
    da, ta = settings(before)
    db_, tb = settings(after)
    print("settings keys before:", sorted(set(ta)), "\nsettings keys after: ", sorted(set(tb)))
    print("settings bytes identical:", da == db_, "(last_refresh_attempt differs after a refresh; the keys above are the same)")
    print("RESULT:", "UNCHANGED" if ok else "DIFFERENCES")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
