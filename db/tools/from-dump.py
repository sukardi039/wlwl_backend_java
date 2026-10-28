#!/usr/bin/env python3
"""Regenerate ../schema.sql from a `mysqldump --no-data` of the wlwl database.

The dump is not stored in this repo (it describes a production schema and is
large), so this script takes its path:

    python3 db/tools/from-dump.py /path/to/wlwl-schema-dump.sql > db/schema.sql

Two deliberate transformations, both required for a re-runnable cutover script:

1. `DROP TABLE IF EXISTS \`x\`;` before each table is REMOVED and
   `CREATE TABLE` becomes `CREATE TABLE IF NOT EXISTS`. The raw dump is
   destructive; running it against the live database would drop production data.
2. mysqldump's placeholder views (`CREATE VIEW x AS SELECT 1 AS col, ...`) are
   dropped, and its final `/*!50001 VIEW ... */` definitions become
   `CREATE OR REPLACE VIEW`. Placeholders only exist to satisfy dependency
   ordering inside the dump. `DEFINER=`root`@`localhost`` is stripped too, since
   the deploying account does not exist on the target server.

Everything else — column order, types, defaults, indexes, foreign keys,
AUTO_INCREMENT counters, ENGINE/CHARSET (utf8mb3) — is copied verbatim. Column
order matters: the PHP runs ~52 `SELECT *` paths and the port preserves the
result row order.
"""
import re
import sys

TABLE_RE = re.compile(
    r"^CREATE TABLE `(?P<name>[^`]+)` \(\n(?P<body>.*?)\n\)(?P<tail> ENGINE=.*?);$",
    re.MULTILINE | re.DOTALL,
)
VIEW_RE = re.compile(
    r"/\*!50001 VIEW `(?P<name>[^`]+)` AS (?P<body>select .*?) \*/;",
    re.MULTILINE | re.DOTALL,
)

HEADER = """-- wlwl schema for MySQL 8 — generated from the production `mysqldump --no-data`
-- (2026-10-26, MySQL 8.0.40, db `wlwl`). Regenerate with db/tools/from-dump.py.
--
-- Safe to run more than once: tables use CREATE TABLE IF NOT EXISTS, views use
-- CREATE OR REPLACE VIEW, and nothing is dropped. The original dump's
-- `DROP TABLE IF EXISTS` statements were deliberately removed — they would
-- destroy a populated database.
--
-- Charset is utf8mb3 on purpose. The PHP connected with `SET NAMES utf8`, which
-- is utf8mb3 in MySQL, so utf8mb4 here would change 4-byte-character handling
-- and break parity. application.yml pins the same collation on the JDBC side.
--
-- Column order is load-bearing (the backend has ~52 `SELECT *` paths).

SET NAMES utf8mb3;
SET SQL_MODE = 'NO_AUTO_VALUE_ON_ZERO';
SET FOREIGN_KEY_CHECKS = 0;   -- tables are alphabetical, FKs point both ways

"""

FOOTER = """
-- Views are created after every table so their dependencies exist.
SET FOREIGN_KEY_CHECKS = 1;
"""


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    dump = open(sys.argv[1], encoding="utf-8").read()

    tables = list(TABLE_RE.finditer(dump))
    views = list(VIEW_RE.finditer(dump))
    if not tables:
        print("no CREATE TABLE found — wrong file?", file=sys.stderr)
        return 1

    out = [HEADER]
    for m in tables:
        out.append(
            "CREATE TABLE IF NOT EXISTS `%s` (\n%s\n)%s;\n\n"
            % (m.group("name"), m.group("body"), m.group("tail"))
        )

    out.append("-- ------------------------------------------------------------------ views\n\n")
    for v in views:
        out.append("CREATE OR REPLACE VIEW `%s` AS %s;\n\n" % (v.group("name"), v.group("body")))

    out.append(FOOTER)
    sys.stdout.write("".join(out))

    names = [t.group("name") for t in tables]
    print(
        "-- generated: %d tables, %d views" % (len(names), len(views)),
        file=sys.stderr,
    )
    dupes = {n for n in names if names.count(n) > 1}
    if dupes:
        print("duplicate table names: %s" % dupes, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
