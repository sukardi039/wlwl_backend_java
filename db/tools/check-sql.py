#!/usr/bin/env python3
"""Validate db/schema.sql and db/seed-dev.sql without a MySQL server.

Checks that both files parse as MySQL DDL/DML, and that the object counts match
what the port expects. Runs in CI (pip install sqlglot) and locally:

    python3 db/tools/check-sql.py
"""
import sys

EXPECTED_TABLES = 40
EXPECTED_VIEWS = 5

try:
    import sqlglot
    from sqlglot import exp
except ImportError:
    print("sqlglot is required: python3 -m pip install sqlglot", file=sys.stderr)
    sys.exit(2)


def main() -> int:
    failures = []

    schema = open("db/schema.sql", encoding="utf-8").read()
    seed = open("db/seed-dev.sql", encoding="utf-8").read()

    stmts = [s for s in sqlglot.parse(schema, read="mysql") if s]
    creates = [s for s in stmts if isinstance(s, exp.Create)]
    tables = [s for s in creates if (s.args.get("kind") or "").upper() == "TABLE"]
    views = [s for s in creates if (s.args.get("kind") or "").upper() == "VIEW"]

    print(f"schema.sql: {len(tables)} tables, {len(views)} views "
          f"({sum(len(list(t.find_all(exp.ColumnDef))) for t in tables)} columns, "
          f"{sum(len(list(t.find_all(exp.ForeignKey))) for t in tables)} FKs)")

    if len(tables) != EXPECTED_TABLES:
        failures.append(f"expected {EXPECTED_TABLES} tables, found {len(tables)}")
    if len(views) != EXPECTED_VIEWS:
        failures.append(f"expected {EXPECTED_VIEWS} views, found {len(views)}")

    # schema.sql must never destroy data: a stray DROP is a cutover incident.
    # Comments are stripped first — the header explains that the dump's DROP
    # statements were removed, and that prose must not trip the check.
    code_only = "\n".join(
        ln for ln in schema.splitlines() if not ln.strip().startswith("--")
    ).lower()
    for bad in ("drop table", "drop database", "truncate "):
        if bad in code_only:
            failures.append(f"schema.sql contains {bad.upper()!r}")

    seed_stmts = [s for s in sqlglot.parse(seed, read="mysql") if s]
    inserts = [s for s in seed_stmts if isinstance(s, exp.Insert)]
    updates = [s for s in seed_stmts if isinstance(s, exp.Update)]
    print(f"seed-dev.sql: {len(inserts)} inserts, {len(updates)} updates")
    if not inserts:
        failures.append("seed-dev.sql has no INSERT statements")

    for f in failures:
        print("FAIL: " + f, file=sys.stderr)
    if failures:
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
