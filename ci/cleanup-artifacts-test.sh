#!/usr/bin/env bash
#
# Tests for cleanup-artifacts.sh. Needs no GitHub access: the selection
# logic is pure, and the parts that talk HTTP run against a mock server started
# here.
#
#   ./cleanup-artifacts-test.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly CLEANUP="${SCRIPT_DIR}/cleanup-artifacts.sh"
readonly GIB=$((1024 * 1024 * 1024))
readonly MB=$((1024 * 1024))

passed=0
failed=0

check() {
  local name="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    printf 'ok   %s\n' "$name"
    passed=$((passed + 1))
  else
    printf 'FAIL %s\n       expected: [%s]\n       actual:   [%s]\n' \
      "$name" "$expected" "$actual"
    failed=$((failed + 1))
  fi
}

# ------------------------------------------------------- candidate ordering --

# candidates <keep> <current> <<< inventory
candidates() {
  local fixture
  fixture="$(mktemp)"
  cat > "$fixture"
  ARTIFACT_KEEP_BUILDS="$1" GITHUB_RUN_ID="$2" \
    "$CLEANUP" --candidates-from "$fixture" | cut -f2 | paste -sd, -
  rm -f "$fixture"
}

# Five runs, oldest first.
five_runs() {
  local i
  for i in 1 2 3 4 5; do
    printf '2026-01-0%dT00:00:00Z\t10%d\t%d\t1\t10%d1:%d\n' \
      "$i" "$i" $((300 * MB)) "$i" $((300 * MB))
  done
}

echo '-- candidate selection'

check 'oldest first' \
  '101,102,103,104' "$(five_runs | candidates 1 999)"

check 'the newest run is held back by default' \
  '101,102,103,104' "$(five_runs | candidates 1 999)"

check 'keeping three holds the three newest' \
  '101,102' "$(five_runs | candidates 3 999)"

check 'keeping none offers every run' \
  '101,102,103,104,105' "$(five_runs | candidates 0 999)"

check 'the current run is never a candidate' \
  '101,102,104,105' "$(five_runs | candidates 0 103)"

check 'the current run does not consume the keep allowance' \
  '101,102,103' "$(five_runs | candidates 1 104)"

check 'holding more than exist offers nothing' \
  '' "$(five_runs | candidates 9 999)"

check 'an empty inventory is handled' \
  '' "$(printf '' | candidates 1 999)"

# ------------------------------------------------------------------- HTTP  --

MOCK_DIR="$(mktemp -d)"
trap 'rm -rf "$MOCK_DIR"' EXIT

cat > "${MOCK_DIR}/mock.py" <<'PY'
"""Stand-in for the GitHub Actions artifacts API, enough to drive the script."""
import json, os, re, sys, threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

MB = 1024 * 1024
GIB = 1024 * MB
ARTIFACTS = []
# Run 105 holds no artifacts; run 106 is the current run. Each run stores two
# artifacts, so pruning a run has to delete both.
for run in (101, 102, 103, 104, 106):
    for n, size in ((1, 200 * MB), (2, 100 * MB)):
        ARTIFACTS.append({"id": run * 10 + n, "size_in_bytes": size, "expired": False,
                          "created_at": "2026-01-%02dT00:00:%02dZ" % (run - 100, n),
                          "workflow_run": {"id": run}})
# Expired artifacts no longer count against storage, however large.
ARTIFACTS.append({"id": 1001, "size_in_bytes": 5 * GIB, "expired": True,
                  "created_at": "2025-12-01T00:00:00Z", "workflow_run": {"id": 100}})
# GitHub lists newest first; the script must not rely on any order.
ARTIFACTS.sort(key=lambda a: a["created_at"], reverse=True)
PAGE = 3  # smaller than the script asks for, to exercise paging by total_count
REFUSE = set(int(x) for x in os.environ.get("MOCK_REFUSE", "").split(",") if x)
DELETED = []

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def _send(self, code, body=None):
        raw = json.dumps(body).encode() if body is not None else b""
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _authorized(self):
        return self.headers.get("Authorization") == "Bearer test-token"

    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        if not self._authorized():
            return self._send(401, {"message": "unauthorized"})
        if u.path == "/repos/o/r/actions/artifacts":
            page = int(q.get("page", ["1"])[0])
            return self._send(200, {"total_count": len(ARTIFACTS),
                                    "artifacts": ARTIFACTS[(page - 1) * PAGE:page * PAGE]})
        return self._send(404, {"message": "no route " + u.path})

    def do_DELETE(self):
        if not self._authorized():
            return self._send(401, {"message": "unauthorized"})
        m = re.fullmatch(r"/repos/o/r/actions/artifacts/(\d+)", urlparse(self.path).path)
        if m:
            run = int(m.group(1)) // 10
            if run in REFUSE:
                return self._send(403, {"message": "forbidden"})
            if run not in DELETED:
                DELETED.append(run)
            return self._send(204)
        return self._send(404, {"message": "no route"})

srv = HTTPServer(("127.0.0.1", 0), H)
print(srv.server_address[1], flush=True)
threading.Thread(target=srv.serve_forever, daemon=True).start()
try:
    sys.stdin.readline()
finally:
    print("DELETED=" + ",".join(map(str, DELETED)), flush=True)
PY

# run_against_mock <budget> <keep> <dry_run> [refuse_ids]
# Echoes "<deleted ids>|<exit code>", with the script's log on stderr.
run_against_mock() {
  local budget="$1" keep="$2" dry="$3" refuse="${4:-}"
  local dir port rc deleted
  dir="$(mktemp -d)"
  mkfifo "${dir}/ctl"

  exec 3< <(MOCK_REFUSE="$refuse" python3 "${MOCK_DIR}/mock.py" < "${dir}/ctl")
  exec 4> "${dir}/ctl"
  read -r port <&3

  set +e
  GITHUB_TOKEN=test-token \
  GITHUB_API_URL="http://127.0.0.1:${port}" \
  GITHUB_REPOSITORY=o/r \
  GITHUB_RUN_ID=106 \
  ARTIFACT_BUDGET_BYTES="$budget" \
  ARTIFACT_KEEP_BUILDS="$keep" \
  ARTIFACT_DRY_RUN="$dry" \
    "$CLEANUP" >&2
  rc=$?
  set -e

  echo >&4
  read -r deleted <&3
  exec 3<&- 4>&-
  rm -rf "$dir"
  printf '%s|%s\n' "${deleted#DELETED=}" "$rc"
}

echo
echo '-- against a mock API (1.46 GiB stored across runs 101-104 and 106,'
echo '   plus 5 GiB of expired artifacts that must be ignored)'

check 'under budget deletes nothing' \
  '|0' "$(run_against_mock $((5 * GIB)) 1 false 2>/dev/null)"

check 'deletes only as many as the budget needs, oldest first' \
  '101,102|0' "$(run_against_mock "$GIB" 1 false 2>/dev/null)"

check 'a dry run deletes nothing and still succeeds' \
  '|0' "$(run_against_mock "$GIB" 1 true 2>/dev/null)"

check 'a refused deletion advances to the next candidate' \
  '101,103|0' "$(run_against_mock "$GIB" 1 false 102 2>/dev/null)"

check 'a tiny budget deletes every candidate but the held one' \
  '101,102,103,104|0' "$(run_against_mock 1 1 false 2>/dev/null)"

printf '\n%d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
