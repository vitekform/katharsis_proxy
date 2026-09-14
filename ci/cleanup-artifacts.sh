#!/usr/bin/env bash
#
# Keeps the artifacts this pipeline has stored under a size budget, by deleting
# the oldest runs until the total fits.
#
# Azure DevOps has no API for deleting a single build artifact: the Build
# Artifacts group only exposes Create, Get Artifact, Get File and List. The only
# way to free artifact storage is to delete the run that produced it, which also
# removes that run's logs and test results. Runs are taken oldest first and only
# as many as the budget requires.
#
# Runs holding no artifacts are never touched, since deleting them frees
# nothing. Neither is the run performing the cleanup: this step goes first, so
# its own artifacts do not exist yet.
#
# Environment:
#   SYSTEM_ACCESSTOKEN     required  the pipeline's OAuth token
#   SYSTEM_COLLECTIONURI   required  e.g. https://dev.azure.com/myorg/
#   SYSTEM_TEAMPROJECTID   required  project id or name
#   SYSTEM_DEFINITIONID    required  the pipeline whose runs are considered
#   BUILD_BUILDID          required  the current run, never deleted
#   ARTIFACT_BUDGET_BYTES  optional  default 1 GiB
#   ARTIFACT_KEEP_BUILDS   optional  newest runs kept regardless, default 1
#   ARTIFACT_DRY_RUN       optional  "true" reports the plan and deletes nothing
#
# Standalone use, for testing the selection logic without Azure:
#   ./cleanup-artifacts.sh --candidates-from <tsv>
# where each line is: finishTime<TAB>buildId<TAB>sizeBytes<TAB>artifactCount

set -euo pipefail

readonly API_VERSION='7.1'
readonly PAGE_SIZE=200

BUDGET_BYTES="${ARTIFACT_BUDGET_BYTES:-1073741824}"
KEEP_BUILDS="${ARTIFACT_KEEP_BUILDS:-1}"
DRY_RUN="${ARTIFACT_DRY_RUN:-false}"
CURRENT_BUILD_ID="${BUILD_BUILDID:-0}"

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

# Runs that may be deleted, oldest first.
#
# Kept free of any network access and of the budget itself, so it can be
# exercised directly: everything it needs arrives as input or as one of the two
# parameters. How many of these actually go is decided while deleting, so that a
# run which refuses to be deleted advances to the next candidate instead of
# ending the pass short.
list_candidates() {
  awk -F'\t' -v keep="$KEEP_BUILDS" -v current="$CURRENT_BUILD_ID" '
    { finish[NR] = $1; id[NR] = $2; size[NR] = $3; count[NR] = $4; n = NR }
    END {
      # The newest runs are held back even when that leaves us over budget:
      # losing every stored jar to a threshold is worse than being over it.
      for (i = n; i > n - keep && i >= 1; i--) held[id[i]] = 1
      held[current] = 1

      for (i = 1; i <= n; i++) {
        if (id[i] in held) continue
        printf "%s\t%s\t%s\t%s\n", finish[i], id[i], size[i], count[i]
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
  local url="$1"
  shift
  curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors --connect-timeout 30 \
    -H "Authorization: Bearer ${SYSTEM_ACCESSTOKEN}" \
    -H 'Accept: application/json' \
    "$@" "$url" < /dev/null
}

# No -f here: the HTTP status is the result we want, so a 4xx has to come back
# as output rather than as a curl failure.
api_delete() {
  curl -sSL --retry 3 --retry-delay 5 --connect-timeout 30 \
    -X DELETE \
    -H "Authorization: Bearer ${SYSTEM_ACCESSTOKEN}" \
    -H 'Accept: application/json' \
    -o /dev/null -w '%{http_code}' \
    "$1" < /dev/null
}

# Every completed run of this pipeline, oldest first, as compact JSON objects.
fetch_builds() {
  local token='' url page headers
  headers="$(mktemp)"

  while :; do
    url="${API_BASE}/build/builds?api-version=${API_VERSION}"
    url+="&definitions=${SYSTEM_DEFINITIONID}"
    url+="&queryOrder=finishTimeAscending&statusFilter=completed"
    url+="&\$top=${PAGE_SIZE}"
    # An "a && b" statement here would take the whole script down under set -e
    # on the first page, where there is no token yet.
    if [ -n "$token" ]; then
      url+="&continuationToken=${token}"
    fi

    : > "$headers"
    page="$(api_get "$url" -D "$headers")" || { rm -f "$headers"; return 1; }
    jq -c '.value[] | {id, finishTime, buildNumber}' <<<"$page"

    token="$(tr -d '\r' < "$headers" \
      | awk 'tolower($1) == "x-ms-continuationtoken:" { print $2 }' | tail -1)"
    [ -n "$token" ] || break
  done
  rm -f "$headers"
}

# Total artifact bytes and artifact count for one run, as "<bytes> <count>".
#
# artifactsize is not part of the documented ArtifactResource schema - it is a
# type-specific entry in the free-form properties bag - so an artifact that does
# not report one counts as zero rather than failing the run.
build_artifact_usage() {
  local artifacts
  artifacts="$(api_get "${API_BASE}/build/builds/$1/artifacts?api-version=${API_VERSION}")" || return 1
  jq -r '
    [.value[] | (.resource.properties.artifactsize // 0) | tonumber] as $sizes
    | "\($sizes | add // 0) \($sizes | length)"
  ' <<<"$artifacts"
}

# Retention leases keep a run alive past the configured retention and block its
# deletion, so any that apply are released first.
release_leases() {
  local build_id="$1" ids
  ids="$(api_get "${API_BASE}/build/builds/${build_id}/leases?api-version=${API_VERSION}" \
    | jq -r '[.value[].leaseId] | join(",")')"
  [ -n "$ids" ] || return 0

  log "    releasing retention lease(s) ${ids}"
  api_delete "${API_BASE}/build/retention/leases?ids=${ids}&api-version=${API_VERSION}" >/dev/null
}

# --------------------------------------------------------------------- main --

collect_inventory() {
  local build id finish usage size count
  while read -r build; do
    id="$(jq -r '.id' <<<"$build")"
    finish="$(jq -r '.finishTime // ""' <<<"$build")"
    [ -n "$finish" ] || continue

    usage="$(build_artifact_usage "$id")"
    read -r size count <<<"$usage"

    # Runs holding nothing are not candidates: deleting them frees no storage.
    [ "$count" -gt 0 ] || continue

    printf '%s\t%s\t%s\t%s\n' "$finish" "$id" "$size" "$count"
  done < <(fetch_builds)
}

main() {
  require_env SYSTEM_ACCESSTOKEN SYSTEM_COLLECTIONURI SYSTEM_TEAMPROJECTID \
              SYSTEM_DEFINITIONID BUILD_BUILDID
  command -v jq >/dev/null || { log 'jq is required but not installed'; exit 1; }

  API_BASE="${SYSTEM_COLLECTIONURI%/}/${SYSTEM_TEAMPROJECTID}/_apis"
  readonly API_BASE

  log "Artifact budget: $(human "$BUDGET_BYTES")"
  log "Inspecting stored artifacts for pipeline ${SYSTEM_DEFINITIONID}..."

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

  log "Over budget by $(human "$((total - BUDGET_BYTES))"). Deleting oldest runs first."
  log "Keeping the newest ${KEEP_BUILDS} run(s) and the current run ${CURRENT_BUILD_ID}."

  local freed=0 planned=0 deleted=0 failed=0 finish id size count status
  local remaining="$total"
  while IFS=$'\t' read -r finish id size count; do
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
    else
      release_leases "$id" || log '    could not release leases, trying anyway'
      status="$(api_delete "${API_BASE}/build/builds/${id}?api-version=${API_VERSION}")"
      case "$status" in
        2*|404)
          log "    deleted (HTTP ${status})"
          deleted=$((deleted + 1))
          ;;
        *)
          log "    NOT deleted (HTTP ${status}); it may be retained or protected"
          failed=$((failed + 1))
          continue
          ;;
      esac
    fi
    freed=$((freed + size))
    remaining=$((remaining - size))
  done < <(list_candidates < "$INVENTORY_FILE")

  if [ "$DRY_RUN" = 'true' ]; then
    log "Dry run: would delete ${planned} run(s) and free $(human "$freed"). Nothing was removed."
    return 0
  fi

  log "Freed $(human "$freed") across ${deleted} run(s); ${failed} could not be deleted."
  log "Stored artifacts now about $(human "$((total - freed))")."

  if [ "$((total - freed))" -gt "$BUDGET_BYTES" ]; then
    log 'Still over budget. Lower ARTIFACT_KEEP_BUILDS, or check for runs that are'
    log 'retained by a release or pinned with "Retain indefinitely".'
  fi
}

# Exercised directly by cleanup-artifacts-test.sh.
if [ "${1:-}" = '--candidates-from' ]; then
  list_candidates < "$2"
  exit 0
fi

main "$@"
