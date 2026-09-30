#!/usr/bin/env bash
#
# Keeps the artifacts this repository has stored under a size budget, by
# deleting the oldest runs' artifacts until the total fits.
#
# The Delete artifacts workflow runs it by hand with a budget of 0 and no runs
# held back, which deletes every artifact.
#
# GitHub deletes artifacts one at a time, so a run's logs and test results are
# left alone: only its artifacts go. Runs are taken oldest first and only as many
# as the budget requires. The budget covers every workflow in the repository,
# since that is what GitHub's storage quota counts.
#
# Expired artifacts are ignored: GitHub has already freed their storage. Neither
# is the run performing the cleanup ever touched.
#
# Environment:
#   GITHUB_TOKEN           required  a token with actions: write
#   GITHUB_REPOSITORY      required  owner/name
#   GITHUB_RUN_ID          required  the current run, never touched
#   GITHUB_API_URL         optional  default https://api.github.com
#   ARTIFACT_BUDGET_BYTES  optional  default 1 GiB
#   ARTIFACT_KEEP_BUILDS   optional  newest runs kept regardless, default 1
#   ARTIFACT_DRY_RUN       optional  "true" reports the plan and deletes nothing
#
# Standalone use, for testing the selection logic without GitHub:
#   ./cleanup-artifacts.sh --candidates-from <tsv>
# where each line is:
#   finishTime<TAB>runId<TAB>sizeBytes<TAB>artifactCount<TAB>artifactId:size,...

set -euo pipefail

readonly PAGE_SIZE=100

BUDGET_BYTES="${ARTIFACT_BUDGET_BYTES:-1073741824}"
KEEP_BUILDS="${ARTIFACT_KEEP_BUILDS:-1}"
DRY_RUN="${ARTIFACT_DRY_RUN:-false}"
CURRENT_BUILD_ID="${GITHUB_RUN_ID:-0}"

log() { printf '%s\n' "$*"; }

# Removed by the EXIT trap. Not a local of main(): the trap runs after main has
# returned, where a local is already out of scope and set -u would abort.
INVENTORY_FILE=''

remove_temp_files() {
  if [ -n "${INVENTORY_FILE}" ]; then
    rm -f "${INVENTORY_FILE}"
  fi
}
trap remove_temp_files EXIT

# Bytes as something a human can read in the build log.
human() {
  awk -v b="$1" 'BEGIN {
    split("B KiB MiB GiB TiB", unit, " ")
    i = 1
    while (b >= 1024 && i < 5) { b /= 1024; i++ }
    printf (i == 1 ? "%d %s\n" : "%.2f %s\n"), b, unit[i]
  }'
}

# ---------------------------------------------------------------- selection --

# Runs that may be pruned, oldest first.
#
# Kept free of any network access and of the budget itself, so it can be
# exercised directly: everything it needs arrives as input or as one of the two
# parameters. How many of these actually go is decided while deleting, so that a
# run which refuses to be pruned advances to the next candidate instead of
# ending the pass short.
list_candidates() {
  awk -F'\t' -v keep="$KEEP_BUILDS" -v current="$CURRENT_BUILD_ID" '
    { line[NR] = $0; id[NR] = $2; n = NR }
    END {
      # The newest runs are held back even when that leaves us over budget:
      # losing every stored jar to a threshold is worse than being over it.
      for (i = n; i > n - keep && i >= 1; i--) held[id[i]] = 1
      held[current] = 1

      for (i = 1; i <= n; i++) {
        if (id[i] in held) continue
        print line[i]
      }
    }
  '
}

# ---------------------------------------------------------------------- api --

require_env() {
  local missing=0 name
  for name in "$@"; do
    if [ -z "${!name:-}" ]; then
      log "Missing required environment variable: $name"
      missing=1
    fi
  done
  [ "$missing" -eq 0 ] || exit 1
}

api_get() {
  curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors --connect-timeout 30 \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H 'Accept: application/vnd.github+json' \
    -H 'X-GitHub-Api-Version: 2022-11-28' \
    "$1" < /dev/null
}

# No -f here: the HTTP status is the result we want, so a 4xx has to come back
# as output rather than as a curl failure.
api_delete() {
  curl -sSL --retry 3 --retry-delay 5 --connect-timeout 30 \
    -X DELETE \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H 'Accept: application/vnd.github+json' \
    -H 'X-GitHub-Api-Version: 2022-11-28' \
    -o /dev/null -w '%{http_code}' \
    "$1" < /dev/null
}

# Every unexpired artifact in the repository, as compact JSON objects.
fetch_artifacts() {
  local page=1 fetched=0 total body count
  while :; do
    body="$(api_get "${API_BASE}/artifacts?per_page=${PAGE_SIZE}&page=${page}")"
    total="$(jq -r '.total_count' <<<"$body")"
    count="$(jq -r '.artifacts | length' <<<"$body")"
    jq -c '.artifacts[]
      | select(.expired | not)
      | {id, size: .size_in_bytes, created: .created_at,
         run: (.workflow_run.id // "none-\(.id)")}' <<<"$body"

    fetched=$((fetched + count))
    # Stopping on an empty page as well guards against a total that grows
    # while we page, which would otherwise never be reached.
    [ "$count" -gt 0 ] && [ "$fetched" -lt "$total" ] || break
    page=$((page + 1))
  done
}

# --------------------------------------------------------------------- main --

# One line per run holding artifacts, oldest first. A run's age is that of its
# newest artifact, which is when it last stored anything.
collect_inventory() {
  fetch_artifacts | jq -rs '
    group_by(.run)
    | map({
        finish: (map(.created) | max),
        run: .[0].run,
        size: (map(.size) | add),
        count: length,
        ids: (map("\(.id):\(.size)") | join(","))
      })
    | sort_by(.finish)[]
    | [.finish, .run, .size, .count, .ids] | @tsv
  '
}

main() {
  require_env GITHUB_TOKEN GITHUB_REPOSITORY GITHUB_RUN_ID
  command -v jq >/dev/null || { log 'jq is required but not installed'; exit 1; }

  API_BASE="${GITHUB_API_URL:-https://api.github.com}"
  API_BASE="${API_BASE%/}/repos/${GITHUB_REPOSITORY}/actions"
  readonly API_BASE

  log "Artifact budget: $(human "$BUDGET_BYTES")"
  log "Inspecting stored artifacts for ${GITHUB_REPOSITORY}..."

  local total builds
  INVENTORY_FILE="$(mktemp)"
  collect_inventory > "$INVENTORY_FILE"

  total="$(awk -F'\t' '{ t += $3 } END { print t + 0 }' "$INVENTORY_FILE")"
  builds="$(wc -l < "$INVENTORY_FILE" | tr -d ' ')"
  log "Found ${builds} run(s) holding artifacts, $(human "$total") in total."

  if [ "$total" -le "$BUDGET_BYTES" ]; then
    log "Within budget, nothing to delete."
    return 0
  fi

  log "Over budget by $(human "$((total - BUDGET_BYTES))"). Pruning oldest runs first."
  log "Keeping the newest ${KEEP_BUILDS} run(s) and the current run ${CURRENT_BUILD_ID}."

  local freed=0 planned=0 deleted=0 failed=0
  local finish id size count ids entry artifact artifact_size status run_ok
  local remaining="$total"
  while IFS=$'\t' read -r finish id size count ids; do
    # Measured against what is actually gone, so a refused deletion is made up
    # for by the next candidate rather than leaving us over budget.
    if [ "$remaining" -le "$BUDGET_BYTES" ]; then
      break
    fi

    log "  run ${id} (${finish}) - ${count} artifact(s), $(human "$size")"
    planned=$((planned + 1))

    if [ "$DRY_RUN" = 'true' ]; then
      # Counted as if it had worked, so the report covers the whole plan.
      log '    dry run, left alone'
      freed=$((freed + size))
      remaining=$((remaining - size))
      continue
    fi

    run_ok=1
    IFS=',' read -r -a entries <<<"$ids"
    for entry in "${entries[@]}"; do
      artifact="${entry%%:*}"
      artifact_size="${entry#*:}"
      status="$(api_delete "${API_BASE}/artifacts/${artifact}")"
      case "$status" in
        2*|404)
          freed=$((freed + artifact_size))
          remaining=$((remaining - artifact_size))
          ;;
        *)
          log "    artifact ${artifact} NOT deleted (HTTP ${status})"
          run_ok=0
          ;;
      esac
    done

    if [ "$run_ok" -eq 1 ]; then
      log '    deleted'
      deleted=$((deleted + 1))
    else
      failed=$((failed + 1))
    fi
  done < <(list_candidates < "$INVENTORY_FILE")

  if [ "$DRY_RUN" = 'true' ]; then
    log "Dry run: would prune ${planned} run(s) and free $(human "$freed"). Nothing was removed."
    return 0
  fi

  log "Freed $(human "$freed") across ${deleted} run(s); ${failed} could not be fully pruned."
  log "Stored artifacts now about $(human "$((total - freed))")."

  if [ "$((total - freed))" -gt "$BUDGET_BYTES" ]; then
    log 'Still over budget. Lower ARTIFACT_KEEP_BUILDS, or check that the token'
    log 'has actions: write.'
  fi
}

# Exercised directly by cleanup-artifacts-test.sh.
if [ "${1:-}" = '--candidates-from' ]; then
  list_candidates < "$2"
  exit 0
fi

main "$@"
