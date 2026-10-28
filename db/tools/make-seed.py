#!/usr/bin/env python3
"""Build db/seed-dev.sql from the H2 test fixture, and check it against db/schema.sql.

The H2 fixture is the only place this repo has row data, and those rows exist to
drive parity assertions ("pkg2's active=0 makes staffsignall fail for U4"), not to
be production configuration. So the output is a DEVELOPMENT seed: enough to boot
the service and exercise every module locally, with fake companies and staff.

The important part is the check: every INSERT's columns and every UPDATE's SET
columns are verified against the real MySQL schema. H2 tolerates the fixture being
written against a slightly different DDL; MySQL does not, and this is what catches
that drift before someone runs the seed on a real database.

    python3 db/tools/make-seed.py src/test/resources/schema-test.sql db/schema.sql > db/seed-dev.sql
"""
import re
import sys

HEADER = """-- DEVELOPMENT SEED — synthetic data, NOT production configuration.
--
-- Generated from src/test/resources/schema-test.sql by db/tools/make-seed.py, then
-- machine-checked against db/schema.sql (every INSERT/UPDATE column must exist).
--
-- These rows exist to make the parity suite meaningful: fake companies (pkg1..pkg5),
-- fake staff, and just enough catalogue rows (baseinfo, profileclass, action, params)
-- for each module to answer. Do NOT load this into a production database: it would
-- overwrite real configuration with test fixtures.
--
-- For a real deployment the schema comes from db/schema.sql and the DATA comes from
-- the legacy database, e.g.
--   mysqldump --no-create-info --complete-insert wlwl > wlwl-data.sql
--   mysql wlwl_new < wlwl-data.sql
--

SET NAMES utf8mb3;
SET FOREIGN_KEY_CHECKS = 0;

"""

FOOTER = "\nSET FOREIGN_KEY_CHECKS = 1;\n"

INSERT_RE = re.compile(
    r"^insert\s+into\s+(\w+)\s*\(([^)]*)\)\s*values(.*?);\s*$",
    re.IGNORECASE | re.MULTILINE | re.DOTALL,
)
UPDATE_RE = re.compile(
    r"^update\s+(\w+)\s+set\s+(.*?);\s*$", re.IGNORECASE | re.MULTILINE | re.DOTALL
)
CREATE_RE = re.compile(r"CREATE TABLE IF NOT EXISTS `([^`]+)` \((.*?)\n\) ENGINE", re.DOTALL)


def schema_columns(path):
    sql = open(path, encoding="utf-8").read()
    cols = {}
    for m in CREATE_RE.finditer(sql):
        cols[m.group(1).lower()] = {
            c.lower() for c in re.findall(r"^\s+`([^`]+)`", m.group(2), re.MULTILINE)
        }
    return cols


def strip_comments(text):
    """Drop `--` comments to end-of-line, trailing ones included.

    Trailing comments matter: the fixture has `('DC3','U6');   -- Boom: no drilldetail`,
    and a full-line-only stripper leaves the `;` mid-line, so a `;\\s*$` statement
    terminator runs past it and swallows the NEXT insert. That silently skipped
    column-checking one table. The fixture has no `--` inside string literals
    (verified before relying on this).
    """
    return re.sub(r"--[^\n]*", "", text)


def unquote(ident: str) -> str:
    """Fixture quoting: `key` is reserved and 1In4 starts with a digit, so identifiers
    appear as `key` / "key" / "1In4". Strip any of those wrappers before comparing."""
    ident = ident.strip()
    for q in ('`', '"', "'"):
        if len(ident) > 2 and ident.startswith(q) and ident.endswith(q):
            ident = ident[1:-1]
    return ident


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    body = strip_comments(open(sys.argv[1], encoding="utf-8").read())
    cols = schema_columns(sys.argv[2])

    inserts, updates, problems = [], [], []

    for m in INSERT_RE.finditer(body):
        table, collist = m.group(1).lower(), m.group(2)
        names = [unquote(c) for c in collist.split(",") if c.strip()]
        inserts.append(m.group(0).strip().rstrip(";") + ";")
        if table not in cols:
            problems.append(f"INSERT {m.group(1)}: table not in schema")
            continue
        missing = [c for c in names if c.lower() not in cols[table]]
        if missing:
            problems.append(f"INSERT {table}: columns not in schema: {missing}")

    for m in UPDATE_RE.finditer(body):
        table, sets = m.group(1).lower(), m.group(2)
        # only the SET list carries columns; the WHERE clause follows it
        assigns = re.split(r"\bwhere\b", sets, maxsplit=1, flags=re.IGNORECASE)[0]
        names = [unquote(s.split("=")[0]) for s in assigns.split(",") if "=" in s]
        updates.append(m.group(0).strip().rstrip(";") + ";")
        if table not in cols:
            problems.append(f"UPDATE {m.group(1)}: table not in schema")
            continue
        missing = [c for c in names if c.lower() not in cols[table]]
        if missing:
            problems.append(f"UPDATE {table}: columns not in schema: {missing}")

    sys.stdout.write(HEADER + "\n".join(inserts) + "\n\n" + "\n".join(updates) + FOOTER)
    print(
        f"-- {len(inserts)} inserts, {len(updates)} updates, {len(problems)} problems",
        file=sys.stderr,
    )
    for p in problems:
        print("   " + p, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
