"""Parse MySQL DDL (mysqldump output or db/schema.sql) into a comparable model.

No dependencies, no server: everything here is text in, data structures out. The
point is to be able to assert "the ported DDL is structurally identical to the
original DDL" without a MySQL instance, which is what CI can afford.
"""

import re

# ---------------------------------------------------------------- utilities

def strip_comments(sql):
    """Remove -- and # line comments and /* ... */ blocks, keeping quoted text."""
    out = []
    i, n = 0, len(sql)
    quote = None
    while i < n:
        c = sql[i]
        if quote:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(sql[i + 1])
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
            continue
        if c in "'\"`":
            quote = c
            out.append(c)
            i += 1
            continue
        if c == "-" and sql.startswith("--", i):
            j = sql.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "#":
            j = sql.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "/" and sql.startswith("/*", i):
            j = sql.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def split_top_level(text, sep=","):
    """Split on `sep` ignoring separators inside parens or quotes."""
    parts, buf, depth, quote = [], [], 0, None
    i = 0
    while i < len(text):
        c = text[i]
        if quote:
            buf.append(c)
            if c == "\\" and i + 1 < len(text):
                buf.append(text[i + 1])
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
            continue
        if c in "'\"`":
            quote = c
            buf.append(c)
        elif c == "(":
            depth += 1
            buf.append(c)
        elif c == ")":
            depth -= 1
            buf.append(c)
        elif c == sep and depth == 0:
            parts.append("".join(buf).strip())
            buf = []
        else:
            buf.append(c)
        i += 1
    if "".join(buf).strip():
        parts.append("".join(buf).strip())
    return parts


def _find_stmt(sql, start):
    """Return (body, end_index) for the statement starting at `start`."""
    depth, i, n = 0, start, len(sql)
    quote = None
    while i < n:
        c = sql[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in "'\"`":
            quote = c
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == ";" and depth <= 0:
            return sql[start:i], i
        i += 1
    return sql[start:], n


# ------------------------------------------------------------------- types

TYPE_WORDS = ("varchar", "char", "int", "bigint", "smallint", "tinyint", "mediumint",
              "text", "tinytext", "mediumtext", "longtext", "datetime", "date", "time",
              "timestamp", "decimal", "numeric", "double", "float", "blob", "longblob",
              "varbinary", "binary", "bit", "json", "enum", "set", "year")


def parse_type(text):
    """Normalise a column type to (family, args, modifiers) for comparison.

    MySQL 8 dumps omit integer display widths; older dumps include them, and the
    width is meaningless since 8.0.19, so it is dropped here. `int(11)` == `int`.
    """
    t = text.strip().lower()
    m = re.match(r"^([a-z]+)\s*(?:\(([^)]*)\))?(.*)$", t)
    if not m:
        return t, "", ""
    family, args, mods = m.group(1), (m.group(2) or "").strip(), m.group(3) or ""
    args = re.sub(r"\s+", "", args)
    if family in ("int", "bigint", "smallint", "tinyint", "mediumint"):
        args = ""  # display width is not part of the type in MySQL 8
    mods = " ".join(mods.split())
    if "unsigned" in mods and "unsigned" not in family:
        family += " unsigned"
    if "zerofill" in mods:
        family += " zerofill"
    return family, args, mods


def normalise_default(raw):
    if raw is None:
        return None
    v = raw.strip()
    low = v.lower()
    if low in ("null",):
        return None
    if low.startswith("current_timestamp"):
        return "CURRENT_TIMESTAMP"
    if len(v) >= 2 and v[0] == "'" and v[-1] == "'":
        return v[1:-1].replace("''", "'")
    return v


# ----------------------------------------------------------------- parsing

class Column:
    __slots__ = ("name", "type", "nullable", "default", "extra", "charset",
                 "collate", "comment", "position")

    def __init__(self, name, type_, nullable, default, extra, charset, collate,
                 comment, position):
        self.name, self.type, self.nullable = name, type_, nullable
        self.default, self.extra = default, extra
        self.charset, self.collate, self.comment = charset, collate, comment
        self.position = position

    def compare(self):
        d = {
            "type": self.type,
            "nullable": self.nullable,
            "default": self.default,
            "extra": self.extra,
        }
        if self.charset:
            d["charset"] = self.charset
        if self.collate:
            d["collate"] = self.collate
        if self.comment:
            d["comment"] = self.comment
        return d

    def __repr__(self):
        return f"<Column {self.name} {self.type}>"


class Table:
    def __init__(self, name):
        self.name = name
        self.columns = []
        self.primary_key = ()
        self.uniques = {}       # name -> tuple(cols)
        self.indexes = {}       # name -> (tuple(cols), unique?)
        self.foreign_keys = {}  # name -> dict
        self.options = {}       # engine/charset/collate/auto_increment/comment

    def column(self, name):
        for c in self.columns:
            if c.name == name:
                return c
        return None


class Schema:
    def __init__(self):
        self.tables = {}
        self.views = {}
        self.notes = []


COLUMN_KEYWORDS = ("NOT NULL", "NULL", "DEFAULT", "AUTO_INCREMENT", "ON UPDATE",
                   "CHARACTER SET", "COLLATE", "COMMENT", "GENERATED", "INVISIBLE",
                   "SRID", "STORAGE", "COLUMN_FORMAT")


def _parse_column(item, position):
    m = re.match(r"^`([^`]+)`\s+(.*)$", item, re.S)
    if not m:
        return None
    name, rest = m.group(1), m.group(2).strip()

    # Type ends at the first column keyword.
    upper = rest.upper()
    cut = len(rest)
    for kw in COLUMN_KEYWORDS:
        idx = upper.find(kw)
        if 0 <= idx < cut:
            # "NULL" inside "NOT NULL" must not cut early; check word boundary
            prefix = upper[:idx]
            if kw == "NULL" and prefix.rstrip().endswith("NOT"):
                continue
            cut = idx
    type_text = rest[:cut].strip()
    tail = rest[cut:]

    nullable = "NOT NULL" not in tail.upper()
    extra = []
    if "AUTO_INCREMENT" in tail.upper():
        extra.append("auto_increment")
    mu = re.search(r"ON UPDATE\s+(\w+(?:\(\d*\))?)", tail, re.I)
    if mu:
        extra.append("on update " + mu.group(1).upper())
    if re.search(r"GENERATED ALWAYS", tail, re.I):
        extra.append("generated")
    md = re.search(r"DEFAULT\s+('(?:[^']|'')*'|[\w.()]+(?:\(\d*\))?|NULL)", tail, re.I)
    default = normalise_default(md.group(1)) if md else None
    mc = re.search(r"CHARACTER SET\s+(\w+)", tail, re.I)
    mo = re.search(r"COLLATE\s+(\w+)", tail, re.I)
    mm = re.search(r"COMMENT\s+'((?:[^']|'')*)'", tail, re.I)
    return Column(name, parse_type(type_text), nullable, default,
                  " ".join(extra), mc.group(1).lower() if mc else None,
                  mo.group(1).lower() if mo else None,
                  mm.group(1) if mm else None, position)


def parse_ddl(sql, default_charset=None):
    """Parse CREATE TABLE / CREATE VIEW statements into a Schema."""
    schema = Schema()
    clean = strip_comments(sql)
    clean = re.sub(r"/\*!\d+\s*|\*/", "", clean)  # leftover conditional markers

    for m in re.finditer(r"CREATE\s+(?:TEMPORARY\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([\w$]+)`?\s*\(",
                         clean, re.I):
        body, _ = _find_stmt(clean, m.end() - 1)
        if body.startswith("("):
            body = body[1:]
        if body.endswith(")"):
            body = body[:-1]
        table = Table(m.group(1))
        for item in split_top_level(body):
            u = item.upper()
            if u.startswith("PRIMARY KEY"):
                cols = re.search(r"\(([^)]*)\)", item)
                table.primary_key = tuple(_cols(cols.group(1)) if cols else ())
                continue
            if u.startswith("UNIQUE KEY") or u.startswith("UNIQUE INDEX"):
                nm = re.match(r"UNIQUE\s+(?:KEY|INDEX)\s+`([^`]+)`", item, re.I)
                cols = re.search(r"\(([^)]*)\)", item)
                name = nm.group(1) if nm else "UNIQUE"
                cols_t = tuple(_cols(cols.group(1)) if cols else ())
                table.uniques[name] = cols_t
                table.indexes[name] = (cols_t, True)
                continue
            if u.startswith("KEY") or u.startswith("INDEX"):
                nm = re.match(r"(?:KEY|INDEX)\s+`([^`]+)`", item, re.I)
                cols = re.search(r"\(([^)]*)\)", item)
                name = nm.group(1) if nm else "KEY"
                cols_t = tuple(_cols(cols.group(1)) if cols else ())
                table.indexes[name] = (cols_t, False)
                continue
            if u.startswith("CONSTRAINT") or "FOREIGN KEY" in u:
                nm = re.match(r"CONSTRAINT\s+`([^`]+)`", item, re.I)
                fk = re.search(r"FOREIGN KEY\s*\(([^)]*)\)\s*REFERENCES\s+`?([\w$]+)`?\s*\(([^)]*)\)",
                               item, re.I)
                if fk:
                    rules = {}
                    for act in ("DELETE", "UPDATE"):
                        a = re.search(act + r"\s+(RESTRICT|CASCADE|SET NULL|NO ACTION|SET DEFAULT)",
                                      item, re.I)
                        if a:
                            rules[act] = a.group(1).upper()
                    name = nm.group(1) if nm else f"fk_{len(table.foreign_keys)}"
                    table.foreign_keys[name] = {
                        "columns": tuple(_cols(fk.group(1))),
                        "ref_table": fk.group(2),
                        "ref_columns": tuple(_cols(fk.group(3))),
                        "rules": rules,
                    }
                continue
            col = _parse_column(item, len(table.columns))
            if col is not None:
                table.columns.append(col)
        tail, _ = _find_stmt(clean, m.end() - 1)
        table.options = _parse_options(tail)
        if not table.options.get("charset") and default_charset:
            table.options["charset"] = default_charset
        schema.tables[table.name] = table

    for m in re.finditer(r"CREATE\s+(?:OR\s+REPLACE\s+)?(?:ALGORITHM=\w+\s+)?(?:DEFINER=\S+\s+)?"
                         r"(?:SQL\s+SECURITY\s+\w+\s+)?VIEW\s+`?([\w$]+)`?\s+AS\s+",
                         clean, re.I):
        body, _ = _find_stmt(clean, m.end())
        schema.views[m.group(1)] = normalise_sql(body)
    return schema


def _cols(text):
    out = []
    for part in split_top_level(text):
        p = part.strip()
        p = re.sub(r"`", "", p)
        p = re.sub(r"\(\d+\)$", "", p)  # index prefix length
        p = re.sub(r"\s+(ASC|DESC)$", "", p, flags=re.I)
        if p:
            out.append(p.strip())
    return out


def _parse_options(tail):
    opts = {}
    m = re.search(r"ENGINE\s*=\s*(\w+)", tail, re.I)
    if m:
        opts["engine"] = m.group(1).lower()
    m = re.search(r"(?:DEFAULT\s+)?CHARSET\s*=\s*(\w+)", tail, re.I)
    if m:
        opts["charset"] = m.group(1).lower()
    m = re.search(r"COLLATE\s*=\s*(\w+)", tail, re.I)
    if m:
        opts["collate"] = m.group(1).lower()
    m = re.search(r"AUTO_INCREMENT\s*=\s*(\d+)", tail, re.I)
    if m:
        opts["auto_increment"] = m.group(1)
    m = re.search(r"COMMENT\s*=\s*'((?:[^']|'')*)'", tail, re.I)
    if m:
        opts["comment"] = m.group(1)
    return opts


def normalise_sql(text):
    t = re.sub(r"\s+", " ", text or "").strip().rstrip(";").strip()
    t = t.replace("`", "")
    return t.lower()
