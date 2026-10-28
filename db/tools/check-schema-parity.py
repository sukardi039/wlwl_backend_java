#!/usr/bin/env python3
"""Assert that db/schema.sql is structurally identical to the ORIGINAL wlwl database.

The port's correctness argument is 1:1 parity, and the database is part of the
contract: column order alone is load-bearing because the backend has ~150
`SELECT *` paths whose JSON key order comes straight from the column order.

Two reference tiers, strongest first:

  ./db/tools/check-schema-parity.py --dump prod-schema.sql
        Tier A. Compare against a real `mysqldump --no-data` of the original
        database. Every table, column (in order), type, nullability, default,
        extra, key, foreign key, table option and view body is compared exactly.
        This is the authoritative test; run it once on the production host:
            mysqldump --no-data --skip-dump-date wlwl > /tmp/prod-schema.sql

  ./db/tools/check-schema-parity.py
        Tier B. Compare against db/reference/wlwl_schema_real.md, the condensed
        dump summary committed to this repo. It asserts table set, column set
        and order, primary keys, AUTO_INCREMENT, NOT NULL/UQ/default where the
        doc states them, and every "there is no <column>" note. Anything the
        condensed doc does not state is reported as UNVERIFIED, never as parity.

Exit codes: 0 = every assertion matched, 1 = parity broken or unverified, 2 = usage.

  --self-test   prove the comparators detect drift (mutation test, no files needed)
"""

import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))          # repo root
sys.path.insert(0, HERE)

from schema_model import (Schema, Table, parse_ddl, parse_type,  # noqa: E402
                          normalise_default, split_top_level)

DOC_DEFAULT = os.path.join(ROOT, "db", "reference", "wlwl_schema_real.md")
SCHEMA_DEFAULT = os.path.join(ROOT, "db", "schema.sql")


# =====================================================================
# Tier A: DDL vs DDL
# =====================================================================

class Report:
    def __init__(self):
        self.failures = []
        self.unverified = []
        self.checks = 0

    def check(self, ok, where, message):
        self.checks += 1
        if not ok:
            self.failures.append(f"{where}: {message}")

    def flag(self, where, message):
        self.unverified.append(f"{where}: {message}")

    def ok(self):
        return not self.failures


def compare_ddl(ref: Schema, port: Schema, rep: Report, label="table"):
    ref_tables, port_tables = set(ref.tables), set(port.tables)
    for name in sorted(ref_tables - port_tables):
        rep.check(False, name, "table in the original but missing from db/schema.sql")
    for name in sorted(port_tables - ref_tables):
        rep.check(False, name, "table in db/schema.sql that the original does not have")

    ref_views, port_views = set(ref.views), set(port.views)
    for name in sorted(ref_views - port_views):
        rep.check(False, name, "view in the original but missing from db/schema.sql")
    for name in sorted(port_views - ref_views):
        rep.check(False, name, "view in db/schema.sql that the original does not have")
    for name in sorted(ref_views & port_views):
        rep.check(ref.views[name] == port.views[name], name,
                  "view body differs:\n    original: " + ref.views[name][:300] +
                  "\n    ported  : " + port.views[name][:300])

    for name in sorted(ref_tables & port_tables):
        a, b = ref.tables[name], port.tables[name]
        rep.check([c.name for c in a.columns] == [c.name for c in b.columns], name,
                  "column list/order differs:\n    original: " +
                  ", ".join(c.name for c in a.columns) +
                  "\n    ported  : " + ", ".join(c.name for c in b.columns))
        for ca in a.columns:
            cb = b.column(ca.name)
            if cb is None:
                continue
            for field, va, vb in (("type", ca.type, cb.type),
                                  ("nullable", ca.nullable, cb.nullable),
                                  ("default", ca.default, cb.default),
                                  ("extra", ca.extra, cb.extra),
                                  ("charset", ca.charset, cb.charset),
                                  ("collate", ca.collate, cb.collate),
                                  ("comment", ca.comment, cb.comment)):
                if field in ("charset", "collate", "comment") and va is None and vb is None:
                    continue
                rep.check(va == vb, f"{name}.{ca.name}",
                          f"{field}: original {va!r} vs ported {vb!r}")

        rep.check(a.primary_key == b.primary_key, name,
                  f"primary key: original {a.primary_key} vs ported {b.primary_key}")
        rep.check(a.uniques == b.uniques, name,
                  f"unique keys: original {a.uniques} vs ported {b.uniques}")
        rep.check(set(a.indexes) == set(b.indexes), name,
                  f"index set: original {sorted(a.indexes)} vs ported {sorted(b.indexes)}")
        for k in sorted(set(a.indexes) & set(b.indexes)):
            rep.check(a.indexes[k] == b.indexes[k], f"{name}.{k}",
                      f"index: original {a.indexes[k]} vs ported {b.indexes[k]}")
        rep.check(set(a.foreign_keys) == set(b.foreign_keys), name,
                  f"FK set: original {sorted(a.foreign_keys)} vs ported {sorted(b.foreign_keys)}")
        for k in sorted(set(a.foreign_keys) & set(b.foreign_keys)):
            rep.check(a.foreign_keys[k] == b.foreign_keys[k], f"{name}.{k}",
                      f"FK: original {a.foreign_keys[k]} vs ported {b.foreign_keys[k]}")

        for opt in ("engine", "charset", "collate", "comment"):
            va, vb = a.options.get(opt), b.options.get(opt)
            if va is None and vb is None:
                continue
            rep.check(va == vb, f"{name} [{opt}]",
                      f"table option: original {va!r} vs ported {vb!r}")
        ia, ib = a.options.get("auto_increment"), b.options.get("auto_increment")
        if ia != ib:
            # Counters reflect row history, not structure: a legitimate difference
            # between two dumps taken at different times.
            rep.flag(f"{name} [auto_increment]",
                     f"counter differs (original {ia}, ported {ib}) — data, not structure")

    return rep


# =====================================================================
# Tier B: condensed doc vs DDL
# =====================================================================

TYPE_ALIAS = {
    "v": "varchar", "int": "int", "bigint": "bigint", "text": "text",
    "longtext": "longtext", "datetime": "datetime", "date": "date",
    "timestamp": "timestamp", "char": "char", "varchar": "varchar",
    "decimal": "decimal",
}

DOC_TYPE_RE = re.compile(
    r"^(v\d+|int|bigint|text|longtext|datetime|date|timestamp|char\(\d+\)|"
    r"varchar\(\d+\)|decimal\(\d+,\s*\d+\))$", re.I)


def doc_type_to_model(tok):
    t = tok.strip().lower().replace(" ", "")
    m = re.match(r"^v(\d+)$", t)
    if m:
        return ("varchar", m.group(1), "")
    return parse_type(t)


def doc_default_to_model(tok):
    """`d0`, `d1.00`, `d''`, `d'>'`, `d'2023-01-01'`, `dCURRENT_TIMESTAMP`."""
    v = tok.strip()
    if v.startswith("d"):
        v = v[1:]
    return normalise_default(v)


class DocTable:
    def __init__(self, name):
        self.name = name
        self.columns = []       # list of dict(name, type|None, nn, pk, ai, uq, default|None)
        self.pk = ()
        self.notes = []         # free text
        self.absent_columns = []
        self.copy_of = None
        self.unenumerated = None
        self.no_pk = False
        self.no_ai = False
        self.no_fk = False
        self.uq = None

    def column(self, name):
        for c in self.columns:
            if c["name"] == name:
                return c
        return None


def _clean_tok(tok):
    tok = tok.replace("**", "").replace("`", "")
    tok = re.sub(r"\s+", " ", tok).strip()
    tok = tok.strip(";").strip()
    return tok


def _expand_names(name_part):
    """`skillsClass/moraleClass/efficiencyClass` -> three names.

    `impactAppFrom/To` -> `impactAppFrom`, `impactAppTo` (doc shorthand).
    """
    parts = [p.strip() for p in name_part.split("/") if p.strip()]
    if len(parts) == 1:
        return parts
    out = []
    for i, p in enumerate(parts):
        if i and len(p) <= 3 and out and out[-1].endswith("From"):
            out.append(out[-1][:-len("From")] + p)
        else:
            out.append(p)
    return out


def parse_doc(path):
    """Parse the condensed schema doc into {table: DocTable} + {view: text} + dead list."""
    text = open(path, encoding="utf-8").read()
    tables, views, dead = {}, {}, []
    section = ""
    current = None
    pending = []

    def flush():
        nonlocal pending
        if current is not None and pending:
            _flush_pending(current, " ".join(pending))
        pending = []

    for raw in text.splitlines():
        line = raw.rstrip()
        s = line.strip()

        if line.startswith("## "):
            flush()
            current = None
            section = line[3:].strip().lower()
            continue

        if section.startswith("tables that do not exist"):
            for token in re.findall(r"`([^`]+)`", line):
                if re.match(r"^[\w]+$", token):
                    dead.append(token)
            continue

        m = re.match(r"^- \*\*([\w]+)\*\*: (.*)$", line)
        if m and not section.startswith("views"):
            flush()
            current = DocTable(m.group(1))
            tables[current.name] = current
            pending = [m.group(2)]
            continue

        m = re.match(r"^- \*\*([\w]+)\*\* = (.*)$", line)
        if m:
            flush()
            current = None
            views[m.group(1)] = _clean_tok(m.group(2))
            continue

        if current is not None:
            if line.startswith("  - "):
                _apply_note(current, _clean_tok(line.strip()[2:]))
                continue
            if s and line.startswith("  "):
                pending.append(s)
                continue

    flush()
    return tables, views, dead


def _apply_note(tbl, note):
    tbl.notes.append(note)
    low = note.lower()
    for m in re.finditer(r"there is no `?([\w]+)`? column", low):
        tbl.absent_columns.append(m.group(1))
    m = re.search(r"no ([\w]+) / ([\w]+)", low)
    if m:
        tbl.absent_columns.extend([m.group(1), m.group(2)])
    if "no default" in low:
        tbl.notes.append("NN-no-default")
    m = re.search(r"pk is (\w+)", low)
    if m:
        tbl.pk = (m.group(1),)


def _flush_pending(tbl, spec):
    """Turn one table's inline column spec (bullet + continuations) into columns."""
    spec = spec.replace("**", "")
    for tok in split_top_level(spec):
        tok = _clean_tok(tok)
        if not tok:
            continue

        m = re.match(r"^PK is (\w+)", tok, re.I)
        if m:
            tbl.pk = (m.group(1),)
            if tbl.column(m.group(1)) is None:
                _add_doc_column(tbl, m.group(1))     # doc omits its type
            continue
        m = re.match(r"^PK \(([^)]+)\)(.*)$", tok, re.I)
        if m:
            tbl.pk = tuple(x.strip() for x in m.group(1).split(","))
            rest = m.group(2).lstrip(" ,;-—")
            if rest:
                _add_doc_column(tbl, rest)
            continue

        if "—" in tok:
            left, _, right = tok.partition("—")
            _apply_note(tbl, _clean_tok(right))
            tok = _clean_tok(left)
            if not tok:
                continue
            low = tok.lower()

        # trailing asides such as "(no default - must be supplied)" / "(!)"
        tok = re.sub(r"\s*\((?![0-9,]+\s*\))[^()]*\)\s*$", "", tok).strip()
        tok = tok.replace("(!)", "").strip()
        low = tok.lower()

        if "subset of staff" in low:
            tbl.unenumerated = tok
            for nm in re.findall(r"no ([\w/]+)", tok):
                tbl.absent_columns.extend(nm.split("/"))
            continue
        if low.startswith("same"):
            tbl.copy_of = tbl.name.replace("_original", "")
            if "no ai/pk" in low:
                tbl.no_pk = tbl.no_ai = True
            if "no fks" in low:
                tbl.no_fk = True
            continue
        if ".." in tok:
            tbl.unenumerated = tok
            continue
        if re.match(r"^(UQ|FK)\b|^\d+ FKs?\b", tok):
            _apply_note(tbl, tok)
            m = re.match(r"^UQ \(([^)]+)\)", tok, re.I)
            if m:
                tbl.uq = m.group(1)
            continue
        _add_doc_column(tbl, tok)

    # A composite `PK (a, b)` clause names columns the doc may not list again;
    # they are real, leading columns and must be counted in the order check.
    for name in reversed(tbl.pk):
        if tbl.column(name) is None:
            tbl.columns.insert(0, {"name": name, "type": None, "nn": True, "pk": True,
                                   "ai": False, "uq": False, "default": None})


def _add_doc_column(tbl, tok):
    tok = re.sub(r"/\s+", "/", tok)          # doc wraps long name groups after a '/'
    m = re.match(r"^([A-Za-z0-9_./]+)\s*(.*)$", tok)
    if not m:
        tbl.unenumerated = tok
        return
    name_part, rest = m.group(1), m.group(2).strip()
    names = _expand_names(name_part)

    flags = set()
    default = None
    if re.search(r"\bNN\b", rest):
        flags.add("nn")
    if re.search(r"\bPK\b", rest):
        flags.add("pk")
    if re.search(r"\bAI\b", rest):
        flags.add("ai")
    if re.search(r"\bUQ\b", rest):
        flags.add("uq")
    md = re.search(r"\bd('(?:[^']|'')*'|\d[\d.]*|>|CURRENT_TIMESTAMP)", rest)
    if md:
        default = doc_default_to_model(md.group(1))

    type_tok = None
    for part in re.split(r"[\s]+", rest):
        if DOC_TYPE_RE.match(part.replace(",", ",")):
            type_tok = part
            break
    norm_type = doc_type_to_model(type_tok) if type_tok else None

    for n in names:
        existing = tbl.column(n)
        if existing:
            existing.update({"nn": "nn" in flags, "pk": "pk" in flags,
                             "ai": "ai" in flags, "uq": "uq" in flags})
            continue
        tbl.columns.append({"name": n, "type": norm_type, "nn": "nn" in flags,
                            "pk": "pk" in flags, "ai": "ai" in flags,
                            "uq": "uq" in flags, "default": default})

    if "pk" in flags and not tbl.pk:
        tbl.pk = tuple(names)


def compare_doc(doc_tables, doc_views, dead, port: Schema, rep: Report):
    for name in sorted(set(doc_tables) - set(port.tables)):
        rep.check(False, name, "in the schema doc but missing from db/schema.sql")
    for name in sorted(set(port.tables) - set(doc_tables)):
        rep.check(False, name, "in db/schema.sql but not in the schema doc")
    for name in dead:
        rep.check(name not in port.tables, name,
                  "the schema doc says this table does not exist in production")

    for name in sorted(set(doc_views) - set(port.views)):
        rep.check(False, name, "view documented but missing from db/schema.sql")
    for name in sorted(set(port.views) - set(doc_views)):
        rep.check(False, name, "view in db/schema.sql but undocumented")

    for name in sorted(set(doc_tables) & set(port.tables)):
        d, p = doc_tables[name], port.tables[name]

        if d.copy_of and d.copy_of in doc_tables:
            src = doc_tables[d.copy_of]
            rep.check([c["name"] for c in src.columns] == [c.name for c in p.columns],
                      name, f"doc says 'same columns' as {d.copy_of}, but the column "
                            f"lists differ: {[c.name for c in p.columns]}")
            if getattr(d, "no_pk", False):
                rep.check(not p.primary_key, name, "doc says no primary key, ported has one")
            if getattr(d, "no_ai", False):
                rep.check(not any(c.extra == "auto_increment" for c in p.columns), name,
                          "doc says no AUTO_INCREMENT, ported has one")
            if getattr(d, "no_fk", False):
                rep.check(not p.foreign_keys, name,
                          f"doc says no FKs, ported has {sorted(p.foreign_keys)}")
            continue

        if d.unenumerated:
            rep.flag(name, f"column list not enumerated in the doc ({d.unenumerated!r})")
            for absent in d.absent_columns:
                rep.check(p.column(absent) is None, f"{name}.{absent}",
                          "doc says this column does not exist, ported has it")
            continue

        doc_cols = [c["name"] for c in d.columns]
        port_cols = [c.name for c in p.columns]
        rep.check(doc_cols == port_cols, name,
                  "column list/order differs:\n    doc   : " + ", ".join(doc_cols) +
                  "\n    ported: " + ", ".join(port_cols))

        for absent in d.absent_columns:
            rep.check(p.column(absent) is None, f"{name}.{absent}",
                      "doc says this column does not exist, ported has it")

        for dc in d.columns:
            pc = p.column(dc["name"])
            if pc is None:
                continue
            if dc["type"] is None:
                rep.flag(f"{name}.{dc['name']}", "doc gives no type")
            else:
                rep.check(dc["type"] == pc.type, f"{name}.{dc['name']}",
                          f"type: doc {dc['type']} vs ported {pc.type}")
            if dc["nn"]:
                rep.check(not pc.nullable, f"{name}.{dc['name']}",
                          "doc says NN, ported is NULL-able")
            if dc["ai"]:
                rep.check(pc.extra == "auto_increment", f"{name}.{dc['name']}",
                          f"doc says AUTO_INCREMENT, ported extra={pc.extra!r}")
            if dc["default"] is not None:
                rep.check(dc["default"] == pc.default, f"{name}.{dc['name']}",
                          f"default: doc {dc['default']!r} vs ported {pc.default!r}")

        if d.pk:
            rep.check(d.pk == p.primary_key, name,
                      f"primary key: doc {d.pk} vs ported {p.primary_key}")
        if getattr(d, "uq", None):
            want = tuple(x.strip() for x in d.uq.split(","))
            rep.check(want in p.uniques.values(), name,
                      f"doc says UNIQUE {want}, ported uniques are {list(p.uniques.values())}")

    return rep


# =====================================================================
# self-test: the comparator must detect drift, not rubber-stamp
# =====================================================================

MUTATIONS = [
    ("drop a column",
     "  `staffPhoto` varchar(255) DEFAULT NULL,\n", ""),
    ("rename a column",
     "  `staffPhoto` varchar(255) DEFAULT NULL,", "  `staffPhotoX` varchar(255) DEFAULT NULL,"),
    ("widen a varchar",
     "  `staffName` varchar(100) NOT NULL,", "  `staffName` varchar(50) NOT NULL,"),
    ("make a NN column nullable",
     "  `staffName` varchar(100) NOT NULL,", "  `staffName` varchar(100) DEFAULT NULL,"),
    ("change a default",
     "  `yearsWorked` int DEFAULT '0',", "  `yearsWorked` int DEFAULT '1',"),
    ("add a column",
     "  `oldTimer` int DEFAULT '0',\n", "  `oldTimer` int DEFAULT '0',\n  `ghost` int DEFAULT NULL,\n"),
    ("drop a foreign key",
     "  CONSTRAINT `action_style` FOREIGN KEY (`actualStyle`) REFERENCES `baseinfo` (`name`),\n", ""),
    ("change an FK rule",
     "REFERENCES `baseinfo` (`name`) ON DELETE SET NULL ON UPDATE CASCADE,",
     "REFERENCES `baseinfo` (`name`) ON DELETE CASCADE ON UPDATE CASCADE,"),
    ("reorder two columns",
     "  `staffName` varchar(100) NOT NULL,\n  `staffPhoto` varchar(255) DEFAULT NULL,",
     "  `staffPhoto` varchar(255) DEFAULT NULL,\n  `staffName` varchar(100) NOT NULL,"),
    ("change the primary key",
     "  PRIMARY KEY (`staffId`),", "  PRIMARY KEY (`staffName`),"),
    ("drop a unique key",
     "  UNIQUE KEY `staff-unique` (`staffName`),", ""),
    ("flip the table charset",
     ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb3", ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
    ("drop a view",
     "CREATE OR REPLACE VIEW `license_view`", "CREATE OR REPLACE VIEW `license_view_x`"),
    ("change a view body",
     "from (`license` `l` join `licensebatch` `b`)",
     "from (`license` `l` left join `licensebatch` `b`)"),
]


def _as_dump(text):
    """Wrap db/schema.sql the way `mysqldump --no-data` would present it."""
    out = ["-- MySQL dump 10.13  Distrib 8.0.40",
           "/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;",
           "/*!40101 SET NAMES utf8mb3 */;",
           "/*!40014 SET FOREIGN_KEY_CHECKS=0 */;",
           ""]
    for line in text.splitlines():
        m = re.match(r"CREATE TABLE IF NOT EXISTS `(\w+)`", line)
        if m:
            out.append(f"DROP TABLE IF EXISTS `{m.group(1)}`;")
        out.append(line)
    return "\n".join(out)


def self_test(schema_path):
    base = open(schema_path, encoding="utf-8").read()
    reference = parse_ddl(base)
    failures = 0

    # identity: the unmutated file must compare clean (no false positives)
    rep = compare_ddl(reference, parse_ddl(base), Report())
    if rep.ok():
        print(f"  {'identity (no mutation)':<24}: clean ({rep.checks} assertions, correct)")
    else:
        print(f"  {'identity (no mutation)':<24}: FALSE POSITIVE — {rep.failures[:2]}")
        failures += 1

    # Same idea through the tier-A path: a dump-shaped reference (DROP + conditional
    # comments) must compare clean, and a one-character change must be caught.
    dump = _as_dump(base)
    rep = compare_ddl(parse_ddl(dump), parse_ddl(base), Report())
    if rep.ok():
        print(f"  {'dump-shaped reference':<24}: clean ({rep.checks} assertions, correct)")
    else:
        print(f"  {'dump-shaped reference':<24}: FALSE POSITIVE — {rep.failures[:2]}")
        failures += 1
    doctored = dump.replace("`staffName` varchar(100) NOT NULL",
                            "`staffName` varchar(64) NOT NULL", 1)
    rep = compare_ddl(parse_ddl(doctored), parse_ddl(base), Report())
    print(f"  {'dump with 100->64 change':<24}: "
          f"{'detected' if not rep.ok() else 'MISSED'}")
    if rep.ok():
        failures += 1

    for label, old, new in MUTATIONS:
        if old not in base:
            print(f"  {label:<24}: SKIPPED — mutation anchor not found in schema.sql")
            failures += 1
            continue
        mutated = base.replace(old, new, 1)
        rep = compare_ddl(reference, parse_ddl(mutated), Report())
        detected = not rep.ok()
        print(f"  {label:<24}: {'detected' if detected else 'MISSED'}"
              + (f"  ({rep.failures[0].split(':')[0]})" if detected else ""))
        if not detected:
            failures += 1
    return failures


# =====================================================================

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dump", metavar="FILE",
                    help="original `mysqldump --no-data` output (authoritative tier)")
    ap.add_argument("--doc", metavar="FILE", default=DOC_DEFAULT,
                    help=f"condensed schema reference (default: {DOC_DEFAULT})")
    ap.add_argument("--schema", metavar="FILE", default=SCHEMA_DEFAULT,
                    help="ported DDL to check (default: db/schema.sql)")
    ap.add_argument("--self-test", action="store_true",
                    help="mutation-test the comparators and exit")
    ap.add_argument("--allow-unverified", action="store_true",
                    help="exit 0 when the reference simply does not state something "
                         "(the condensed doc omits some types); differences still fail")
    args = ap.parse_args(argv)

    if args.self_test:
        print("schema-parity self-test (each line must read 'detected')")
        bad = self_test(args.schema)
        print(f"\n{'PASS' if bad == 0 else f'FAIL — {bad} mutation(s) not detected'}")
        return 0 if bad == 0 else 1

    port = parse_ddl(open(args.schema, encoding="utf-8").read())
    rep = Report()

    if args.dump:
        ref = parse_ddl(open(args.dump, encoding="utf-8").read())
        print(f"reference: {args.dump}   (tier A — original dump, exact)")
        print(f"ported   : {args.schema}")
        compare_ddl(ref, port, rep)
        tier = "A"
    else:
        if not os.path.exists(args.doc):
            print(f"error: reference doc not found: {args.doc}", file=sys.stderr)
            print("       run with --dump <prod-schema.sql> for the authoritative tier",
                  file=sys.stderr)
            return 2
        doc_tables, doc_views, dead = parse_doc(args.doc)
        print(f"reference: {args.doc}   (tier B — condensed doc)")
        print(f"ported   : {args.schema}")
        compare_doc(doc_tables, doc_views, dead, port, rep)
        print(f"doc: {len(doc_tables)} tables, {len(doc_views)} views, "
              f"{sum(len(t.columns) for t in doc_tables.values())} enumerated columns")
        tier = "B"

    print(f"ported: {len(port.tables)} tables, {len(port.views)} views, "
          f"{sum(len(t.columns) for t in port.tables.values())} columns, "
          f"{sum(len(t.foreign_keys) for t in port.tables.values())} FKs")
    print(f"assertions run: {rep.checks}")

    if rep.unverified:
        print(f"\nUNVERIFIED ({len(rep.unverified)}) — not proven either way:")
        for u in rep.unverified:
            print("  ? " + u)

    if rep.failures:
        print(f"\nPARITY BROKEN — {len(rep.failures)} difference(s):")
        for f in rep.failures:
            print("  ✗ " + f)
        print(f"\nFAIL (tier {tier})")
        return 1

    if rep.unverified:
        print(f"\nNO DIFFERENCES, but {len(rep.unverified)} item(s) unverified "
              f"(tier {tier}); run --dump for the exact answer")
        return 0 if args.allow_unverified else 1

    print(f"\nPARITY OK (tier {tier})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
