#!/usr/bin/env bash
#
# Tests for cleanup-artifacts.sh. Needs no Azure DevOps access: the selection
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
  ARTIFACT_KEEP_BUILDS="$1" BUILD_BUILDID="$2" \
    "$CLEANUP" --candidates-from "$fixture" | cut -f2 | paste -sd, -
  rm -f "$fixture"
}

# Five runs, oldest first.
five_runs() {
  local i
  for i in 1 2 3 4 5; do
    printf '2026-01-0%dT00:00:00Z\t10%d\t%d\t2\n' "$i" "$i" $((300 * MB))
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
"""Stand-in for the Azure DevOps build API, enough to drive the script."""
import json, os, re, sys, threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

MB = 1024 * 1024
BUILDS = [{"id": 100 + i, "finishTime": "2026-01-%02dT00:00:00Z" % i} for i in range(1, 7)]
# Run 105 holds no artifacts; run 106 is the current run.
SIZES = {101: 300 * MB, 102: 300 * MB, 103: 300 * MB, 104: 300 * MB, 106: 300 * MB}
REFUSE = set(int(x) for x in os.environ.get("MOCK_REFUSE", "").split(",") if x)
DELETED, LEASES = [], []

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def _send(self, code, body=None, headers=None):
        raw = json.dumps(body).encode() if body is not None else b""
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query); p = u.path
        if self.headers.get("Authorization") != "Bearer test-token":
            return self._send(401, {"message": "unauthorized"})
        if p.endswith("/_apis/build/builds"):
            # Two pages, to exercise continuation-token paging.
            if "continuationToken" not in q:
                return self._send(200, {"value": BUILDS[:3]}, {"x-ms-continuationtoken": "P2"})
            return self._send(200, {"value": BUILDS[3:]})
        m = re.search(r"/builds/(\d+)/artifacts$", p)
        if m:
            bid = int(m.group(1))
            if bid not in SIZES:
                return self._send(200, {"value": []})
            return self._send(200, {"value": [
                {"id": 1, "name": "proxy-plugin", "resource": {
                    "type": "PipelineArtifact",
                    "properties": {"artifactsize": str(SIZES[bid])}}},
                # artifactsize is not in the documented schema, so an artifact
                # without one must count as zero rather than break the run.
                {"id": 2, "name": "companion-plugin",
                 "resource": {"type": "Container", "properties": {}}},
            ]})
        m = re.search(r"/builds/(\d+)/leases$", p)
        if m:
            return self._send(200, {"value": [{"leaseId": 900 + int(m.group(1)) % 100}]})
        return self._send(404, {"message": "no route " + p})

    def do_DELETE(self):
        u = urlparse(self.path); q = parse_qs(u.query); p = u.path
        if p.endswith("/retention/leases"):
            LEASES.append(q.get("ids", [""])[0]); return self._send(200, {})
        m = re.search(r"/builds/(\d+)$", p)
        if m:
            bid = int(m.group(1))
            if bid in REFUSE:
                return self._send(409, {"message": "retained"})
            DELETED.append(bid); return self._send(204)
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
  SYSTEM_ACCESSTOKEN=test-token \
  SYSTEM_COLLECTIONURI="http://127.0.0.1:${port}/" \
  SYSTEM_TEAMPROJECTID=proj \
  SYSTEM_DEFINITIONID=7 \
  BUILD_BUILDID=106 \
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
echo '-- against a mock API (1.46 GiB stored across runs 101-104 and 106)'

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
