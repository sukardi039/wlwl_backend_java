#!/usr/bin/env bash
# Create (and optionally seed) the wlwl database from the repo, without touching
# any existing data.
#
#   ./db/migrate.sh --schema            # create missing tables/views  (safe, repeatable)
#   ./db/migrate.sh --seed              # tell you how to load the dev seed
#   ./db/migrate.sh --seed --dev        # load the DEV seed (refuses unless DB is empty)
#   ./db/migrate.sh --check             # report object counts, change nothing
#
# Connection settings come from the same environment variables the application
# uses, so one export block configures both:
#
#   export DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME=wlwl DB_USER=root DB_PASSWORD=secret
#
# Notable safety properties:
#   * schema.sql has no DROP statements — it only creates what is missing;
#   * the dev seed is refused on a non-empty database unless --force is given,
#     because it INSERTs rows and would collide with real configuration;
#   * the password is passed via a 0600 temporary defaults-file, never on the
#     command line where `ps` would show it;
#   * the database is created if absent, with utf8mb3 to match production.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCHEMA="$HERE/schema.sql"
SEED="$HERE/seed-dev.sql"

MODE=""
LOAD_SEED=0
FORCE=0
DRY_RUN=0

for arg in "$@"; do
  case "$arg" in
    --schema)  MODE="schema" ;;
    --seed)    MODE="seed" ;;
    --dev)     LOAD_SEED=1 ;;
    --check)   MODE="check" ;;
    --force)   FORCE=1 ;;
    --dry-run) DRY_RUN=1 ;;
    -h|--help) awk 'NR>1 && /^set /{exit} NR>1 {sub(/^# ?/, ""); print}' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

: "${DB_NAME:=wlwl}"
: "${DB_HOST:=127.0.0.1}"
: "${DB_PORT:=3306}"
: "${DB_USER:=root}"
: "${DB_PASSWORD:=}"

command -v mysql >/dev/null 2>&1 || {
  echo "error: the MySQL client 'mysql' is not on PATH." >&2
  echo "       Debian/Ubuntu: apt-get install mysql-client   (needs root)" >&2
  exit 1
}
[ -r "$SCHEMA" ] || { echo "error: missing $SCHEMA (run db/tools/from-dump.py)" >&2; exit 1; }

# ---------------------------------------------------------------- credentials
# MYSQL_PWD is honoured by the client but still leaks in /proc/<pid>/environ on
# some systems, so use a defaults file that only this user can read.
CNF="$(mktemp "${TMPDIR:-/tmp}/wlwl-my.XXXXXX")"
cleanup() { rm -f "$CNF"; }
trap cleanup EXIT INT TERM
chmod 600 "$CNF"
cat >"$CNF" <<EOF
[client]
host=$DB_HOST
port=$DB_PORT
user=$DB_USER
password=$DB_PASSWORD
EOF
MYSQL=(mysql --defaults-extra-file="$CNF" --batch --skip-column-names)

# q/qn take an -e payload, or read SQL from stdin when called with no argument
# (the .sql files are piped in, which is why the no-argument case must exist).
q()  { if [ $# -gt 0 ]; then "${MYSQL[@]}" -e "$1"; else "${MYSQL[@]}"; fi; }
qn() { if [ $# -gt 0 ]; then "${MYSQL[@]}" "$DB_NAME" -e "$1"; else "${MYSQL[@]}" "$DB_NAME"; fi; }

db_exists() {
  [ "$(q "select count(*) from information_schema.schemata where schema_name = '$DB_NAME'")" = "1" ]
}

table_count() {
  db_exists || { echo 0; return; }
  qn "select count(*) from information_schema.tables
      where table_schema = '$DB_NAME' and table_type = 'BASE TABLE'"
}

row_count() {
  db_exists || { echo 0; return; }
  qn "select coalesce(sum(table_rows),0) from information_schema.tables
      where table_schema = '$DB_NAME' and table_type = 'BASE TABLE'"
}

echo "target: $DB_USER@$DB_HOST:$DB_PORT/$DB_NAME"

case "$MODE" in
  check)
    if ! db_exists; then echo "database '$DB_NAME' does not exist"; exit 1; fi
    tables="$(table_count)"
    views="$(qn "select count(*) from information_schema.views where table_schema = '$DB_NAME'")"
    echo "tables: $tables (expected 40)"
    echo "views:  $views (expected 5)"
    [ "$tables" = "40" ] && [ "$views" = "5" ] && echo "OK" || { echo "MISMATCH"; exit 1; }
    ;;

  schema|seed)
    if [ "$DRY_RUN" = "1" ]; then
      echo "[dry-run] would ensure database '$DB_NAME' exists (utf8mb3)"
      echo "[dry-run] would apply $SCHEMA"
      [ "$LOAD_SEED" = "1" ] && echo "[dry-run] would apply $SEED"
      exit 0
    fi

    q "create database if not exists \`$DB_NAME\` character set utf8mb3 collate utf8mb3_general_ci"
    echo "applied: database exists (utf8mb3)"

    before="$(table_count)"
    qn <"$SCHEMA"
    after="$(table_count)"
    echo "applied: $SCHEMA  (tables $before -> $after)"

    if [ "$LOAD_SEED" = "1" ]; then
      rows="$(row_count)"
      if [ "$rows" != "0" ] && [ "$FORCE" != "1" ]; then
        echo "error: '$DB_NAME' already holds data (information_schema.table_rows sums to $rows)." >&2
        echo "       seed-dev.sql is synthetic test data and would collide with real" >&2
        echo "       configuration. Re-run with --force only on a throwaway database." >&2
        exit 1
      fi
      qn <"$SEED"
      echo "applied: $SEED"
    else
      echo
      echo "Schema only. To populate:"
      echo "  * production cutover: copy data from the legacy database, e.g."
      echo "      mysqldump --no-create-info --complete-insert wlwl > wlwl-data.sql"
      echo "      mysql wlwl < wlwl-data.sql"
      echo "  * local development: ./db/migrate.sh --seed --dev   (synthetic rows)"
    fi

    echo
    echo "verify with: ./db/migrate.sh --check"
    ;;
  *)
    echo "nothing to do — pass --schema, --seed --dev, or --check (see --help)" >&2
    exit 2
    ;;
esac
