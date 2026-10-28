#!/usr/bin/env python3
"""Cross-check the SQL embedded in the Java source against db/schema.sql.

The parity suite runs on H2 with a fixture that is (deliberately) only a slice of
production. That means a query can be wrong against the REAL schema and still pass
every test — which is exactly how the `taskefficiency.efficiency` phantom column
survived: no Java code named it, but a `select *` echoed it and the fixture declared
it, so only a MySQL schema check would ever have caught it.

This tool extracts SQL-looking string literals from src/main/java, parses them as
MySQL, and verifies:
  * every referenced TABLE exists in db/schema.sql
  * every qualified COLUMN (alias.column) exists on the table it resolves to
  * every column in an INSERT column list exists

It is a static check, not a substitute for running against a real database: values,
types and MySQL-vs-H2 semantics are out of scope. Statements it cannot parse are
listed rather than silently ignored.

    python3 db/tools/check-sql-usage.py
"""
import pathlib
import re
import sys

import sqlglot
from sqlglot import exp

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA = pathlib.Path(__import__("os").environ.get("SCHEMA_PATH", ROOT / "db" / "schema.sql"))
SRC = ROOT / "src" / "main" / "java"

SQL_START = re.compile(r'"(?:select|insert|update|delete)\b', re.IGNORECASE)

# tables that are absent from production ON PURPOSE. staffsignin/put.php writes a "tasks"
# table that does not exist in wlwl; the owner ruled (2026-10-28) to keep the branch so the
# request 500s exactly like the PHP instead of lying. See the ledger's "Rulings - decided".
ALLOWED_MISSING_TABLES = {"tasks"}

COMMENTS = re.compile(r"/\*.*?\*/|//[^\n]*", re.DOTALL)

# datetime units parse as bare Column nodes: timestampadd(MINUTE, ?, now())
SQL_UNIT_WORDS = {"MICROSECOND", "SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH",
                  "QUARTER", "YEAR", "DAY_HOUR", "DAY_MINUTE", "DAY_SECOND", "HOUR_MINUTE"}
STRING_LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')


def schema_model():
    """table -> set(columns), lowercased; views included."""
    sql = SCHEMA.read_text()
    model = {}
    for m in re.finditer(r"CREATE (?:TABLE|OR REPLACE VIEW)[^(]*?`([^`]+)`\s*(?:AS|\((.*?)\n\) ENGINE)",
                         sql, re.DOTALL):
        name, body = m.group(1).lower(), m.group(2) or ""
        if body:
            cols = {c.lower() for c in re.findall(r"^\s+`([^`]+)`", body, re.MULTILINE)}
        else:                                   # view: SELECT list aliases
            cols = set()
            select = sql[m.start():m.start() + 20000]
            for alias in re.finditer(r"\bAS `([^`]+)`", select):
                cols.add(alias.group(1).lower())
        model[name] = cols
    return model


def java_sql_strings(path):
    """Reassemble `+`-joined string literals that start with a SQL verb.

    Java builds queries as  "select * from t where a = ?" + " and b = ?"  so the
    literals have to be joined before the parser sees them.
    """
    # strip comments first: javadoc blocks quote real SQL and would otherwise be
    # "checked" as if they were executable statements
    text = COMMENTS.sub(lambda m: " " * len(m.group(0)), path.read_text())
    out = []
    for m in SQL_START.finditer(text):
        # walk forward from the opening quote, joining adjacent literals
        i = m.start()
        parts = []
        while True:
            lit = STRING_LIT.match(text, i)
            if not lit:
                break
            parts.append(lit.group(1))
            j = lit.end()
            gap = re.match(r"\s*\+\s*", text[j:])
            if not gap:
                break
            i = j + gap.end()
        out.append("".join(parts))
    return out


def main() -> int:
    model = schema_model()
    tables = set(model)

    statements, unparsed = [], []
    for path in sorted(SRC.rglob("*.java")):
        for raw in java_sql_strings(path):
            sql = raw.replace('\\"', '"').replace("``", "`").replace("?.", "?.")
            statements.append((path, sql))

    print(f"schema: {len(tables)} relations; java: {len(statements)} SQL literals")

    bad_tables, bad_columns, checked_cols = {}, {}, 0
    for path, sql in statements:
        probe = re.sub(r"\?", "NULL", sql)            # make bound params parseable
        try:
            tree = sqlglot.parse_one(probe, read="mysql")
        except Exception:
            unparsed.append((path, sql))
            continue
        if tree is None:
            unparsed.append((path, sql))
            continue

        aliases = {}
        for t in tree.find_all(exp.Table):
            name = (t.name or "").lower()
            if not name:
                continue
            if name not in tables and name not in ALLOWED_MISSING_TABLES:
                bad_tables.setdefault((path, sql), set()).add(name)
            alias = (t.alias or "").lower()
            if alias:
                aliases[alias] = name

        # INSERT column lists
        if isinstance(tree, exp.Insert):
            target = tree.this
            tbl = target.name.lower() if hasattr(target, "name") else ""
            if tbl in model:
                for col in tree.find_all(exp.Column):
                    if col.table or col.name == "*":
                        continue
                    checked_cols += 1
                    if col.name.lower() not in model[tbl]:
                        bad_columns.setdefault((path, sql), set()).add(f"{tbl}.{col.name}")

        # UPDATE ... SET columns are unqualified, so they need the target table
        if isinstance(tree, exp.Update):
            target = tree.this
            tbl = (target.name or "").lower() if hasattr(target, "name") else ""
            if tbl in model:
                # ONLY the UPDATE's own SET expressions: find_all(EQ) would also pick up
                # `drillId = ?` inside a sub-select and blame it on the target table.
                for assignment in tree.args.get("expressions") or []:
                    left = assignment.this if isinstance(assignment, exp.EQ) else None
                    if isinstance(left, exp.Column) and not left.table:
                        checked_cols += 1
                        if left.name.lower() not in model[tbl]:
                            bad_columns.setdefault((path, sql), set()).add(f"{tbl}.{left.name}")

        # single-table statements: unqualified columns must exist on that table too.
        # (With a join, an unqualified name could belong to any of them, so it is skipped.)
        real = {(t.name or "").lower() for t in tree.find_all(exp.Table)} & tables
        if isinstance(tree, exp.Select) and len(real) == 1:
            only = next(iter(real))
            for col in tree.find_all(exp.Column):
                if col.table or col.name == "*" or col.name.upper() in SQL_UNIT_WORDS:
                    continue
                checked_cols += 1
                if col.name.lower() not in model[only]:
                    bad_columns.setdefault((path, sql), set()).add(f"{only}.{col.name}")

        # qualified columns, e.g. `m`.`skillsValue` or d.state
        for col in tree.find_all(exp.Column):
            if col.name == "*":                   # table.* is a wildcard, not a column
                continue
            qualifier = (col.table or "").lower()
            if not qualifier:
                continue
            target = aliases.get(qualifier, qualifier)
            if target not in model:
                continue                              # table problem already reported
            checked_cols += 1
            if col.name.lower() not in model[target]:
                bad_columns.setdefault((path, sql), set()).add(f"{target}.{col.name}")

    def report(title, hits):
        print(f"\n{title}: {len(hits)} statement(s)")
        for (path, sql), names in hits.items():
            print(f"  {path.relative_to(ROOT)}")
            print(f"    {sql[:160]}")
            print(f"    -> {', '.join(sorted(names))}")

    report("UNKNOWN TABLES", bad_tables)
    report("UNKNOWN COLUMNS", bad_columns)

    print(f"\nqualified columns verified: {checked_cols}")
    print(f"statements not parseable: {len(unparsed)}")
    if unparsed:
        for path, sql in unparsed[:10]:
            print(f"  {path.relative_to(ROOT)}: {sql[:120]}")

    ok = not bad_tables and not bad_columns
    print("\n" + ("OK" if ok else "PROBLEMS FOUND"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
