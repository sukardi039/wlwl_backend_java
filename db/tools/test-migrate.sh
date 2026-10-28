#!/usr/bin/env bash
# Self-test for db/migrate.sh — needs NO MySQL server and NO network.
#
# migrate.sh cannot be exercised without a database, so its decision logic (the
# seed guard, the --check verdict, credential handling) is tested against a
# recording stand-in for the `mysql` client. The stand-in answers the
# information_schema probes and logs how it was invoked, which lets us assert the
# two things that matter most: the dev seed never lands on a database that holds
# data, and the password is passed by file rather than on the command line.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DBDIR="$(dirname "$HERE")"
MIGRATE="$DBDIR/migrate.sh"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/migrate-selftest.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0
ok()  { pass=$((pass+1)); printf '  ok   %s\n' "$1"; }
bad() { fail=$((fail+1)); printf '  FAIL %s\n' "$1"; [ -s "$WORK/out" ] && sed 's/^/       /' "$WORK/out"; }

# ---------------------------------------------------------------- stand-in client
mkdir -p "$WORK/bin"
cat >"$WORK/bin/mysql" <<'SHIM'
#!/usr/bin/env bash
echo "ARGV: $*" >>"$SHIM_LOG"
sql=""; prev=""
for a in "$@"; do [ "$prev" = "-e" ] && sql="$a"; prev="$a"; done
if [ -n "$sql" ]; then
  case "$sql" in
    *"from information_schema.schemata"*) echo "${SHIM_DB_EXISTS:-1}" ;;
    *"sum(table_rows)"*)                  echo "${SHIM_ROWS:-0}" ;;
    *"table_type = 'BASE TABLE'"*)        echo "${SHIM_TABLES:-40}" ;;
    *"from information_schema.views"*)    echo "${SHIM_VIEWS:-5}" ;;
  esac
  exit 0
fi
echo "STDIN: $(wc -c)" >>"$SHIM_LOG"
for a in "$@"; do
  case "$a" in --defaults-extra-file=*)
    cnf="${a#--defaults-extra-file=}"
    echo "CNF: mode=$(stat -c %a "$cnf") pass=$(grep -c '^password=' "$cnf")" >>"$SHIM_LOG" ;;
  esac
done
SHIM
chmod +x "$WORK/bin/mysql"
export PATH="$WORK/bin:$PATH"
export SHIM_LOG="$WORK/calls.log"
export DB_PASSWORD="selftest-not-a-real-password"
: >"$SHIM_LOG"

run() { "$MIGRATE" "$@" >"$WORK/out" 2>&1; echo $?; }

echo "migrate.sh self-test"

# 1. a complete schema reports OK
code=$(SHIM_TABLES=40 SHIM_VIEWS=5 run --check)
if [ "$code" = "0" ] && grep -q '^OK' "$WORK/out"; then ok "--check passes on 40 tables / 5 views"
else bad "--check should pass on a complete schema (exit=$code)"; fi

# 2. an incomplete schema is a failure
code=$(SHIM_TABLES=39 SHIM_VIEWS=5 run --check)
if [ "$code" != "0" ] && grep -q 'MISMATCH' "$WORK/out"; then ok "--check fails when a table is missing"
else bad "--check should fail on 39 tables (exit=$code)"; fi

# 3. the dev seed is refused when the database holds rows
code=$(SHIM_ROWS=8123 run --seed --dev)
if [ "$code" != "0" ] && grep -q 'already holds data' "$WORK/out"; then ok "dev seed refused on a populated database"
else bad "dev seed must be refused when rows exist (exit=$code)"; fi

# 4. and accepted when it is empty
code=$(SHIM_ROWS=0 run --seed --dev)
if [ "$code" = "0" ] && grep -q 'seed-dev.sql' "$WORK/out"; then ok "dev seed applies to an empty database"
else bad "dev seed should apply when empty (exit=$code)"; fi

# 5. --force overrides the guard
code=$(SHIM_ROWS=8123 run --seed --dev --force)
if [ "$code" = "0" ]; then ok "--force overrides the guard"
else bad "--force should override (exit=$code)"; fi

# 6. dry-run issues no SQL at all (compare log size: earlier calls are in it)
before=$(wc -c <"$SHIM_LOG")
code=$(run --schema --dry-run)
after=$(wc -c <"$SHIM_LOG")
if [ "$code" = "0" ] && [ "$before" = "$after" ]; then ok "--dry-run issues no SQL"
else bad "--dry-run must not call the client (exit=$code, log grew ${before}->${after})"; fi

# 7. credentials: 0600 file, password inside it, never in argv
if grep -q '^CNF: mode=600 pass=1' "$SHIM_LOG" && ! grep '^ARGV' "$SHIM_LOG" | grep -q 'password'; then
  ok "password passed by 0600 defaults-file, never on argv"
else bad "credential handling"; fi

# 8. no credential file left behind
leftover=$(find "${TMPDIR:-/tmp}" -maxdepth 1 -name 'wlwl-my.*' | wc -l)
if [ "$leftover" = "0" ]; then ok "temporary defaults-file cleaned up"
else bad "credential file left behind ($leftover)"; fi

# 9. unknown arguments are rejected
code=$(run --nonsense)
if [ "$code" = "2" ]; then ok "unknown argument exits 2"
else bad "unknown argument should exit 2 (exit=$code)"; fi

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[ "$fail" = "0" ]
