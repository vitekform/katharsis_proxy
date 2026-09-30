#!/usr/bin/env bash
#
# Uploads jars into the draft of a Katharsis release on the release system
# (rsys.ganamaga.me, project katharsis). The Release job in build.yml publishes
# the draft once every build job has passed, so nobody ever sees a release with
# only some of the jars.
#
# The build jobs run at once and the first to arrive creates the draft, so one
# that loses that race retries and then finds it. Re-runs are safe: a file
# already there with the same sha256 is skipped, and a release that is already
# published is left alone (a rebuilt jar is not byte-identical, and replacing
# a live file is not something a re-run should do quietly).
#
# Usage:
#   ci/rsys-upload.sh [--primary <name>] <file>...
#
# Environment:
#   GN_TOKEN          required  CLI token with rsys:read and rsys:publish
#   RSYS_VERSION      required  the release, e.g. 0.1-dev.42
#   RSYS_PRERELEASE   optional  "true" marks the release as a prerelease
#   RSYS_PROJECT      optional  default katharsis
#
# gn-cli must be on PATH (ci/install-gn-cli.sh).

set -euo pipefail

: "${GN_TOKEN:?}" "${RSYS_VERSION:?}"
project="${RSYS_PROJECT:-katharsis}"

flags=(--draft --append --title "Katharsis $RSYS_VERSION")
[ "${RSYS_PRERELEASE:-false}" = true ] && flags+=(--prerelease)
if [ "${1:-}" = --primary ]; then
  flags+=(--primary "$2")
  shift 2
fi
[ "$#" -gt 0 ] || { echo 'no files to upload'; exit 2; }

gn() { gn-cli --no-unicode --no-color "$@"; }

if status="$(gn rsys show "$project" "$RSYS_VERSION" --json 2>/dev/null | jq -r '.status // empty')" \
   && [ "$status" = PUBLISHED ]; then
  echo "$project $RSYS_VERSION is already published; not uploading into it again."
  exit 0
fi

for attempt in 1 2 3; do
  if gn rsys publish "$project" "$RSYS_VERSION" "$@" "${flags[@]}"; then
    exit 0
  fi
  [ "$attempt" = 3 ] || { echo "attempt $attempt failed, retrying"; sleep $((attempt * 5)); }
done
exit 1
