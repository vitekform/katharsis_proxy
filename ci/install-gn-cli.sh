#!/usr/bin/env bash
#
# Fetches the newest gn-cli Linux binary from the release system
# (rsys.ganamaga.me, project gn-cli), checks it against the sha256 rsys lists
# for it, and puts it on the job's PATH.
#
# Dev prereleases count: nextgen's gn-cli workflow publishes one per master
# push. The newest release need not carry a Linux binary (a hand-published
# Windows-only one, say), so this walks back until one does: gn-cli-linux-x64
# as CI names it, or a bare gn-cli as published by hand.
#
# The binary links libcurl dynamically; GitHub's Ubuntu runners have libcurl4.
#
# Environment:
#   RUNNER_TEMP   required  where the binary goes
#   GITHUB_PATH   optional  when set, the binary's folder is appended to it
#   GITHUB_ENV    optional  when set, GN_CLI_CONFIG_DIR is pointed into
#                           RUNNER_TEMP for the rest of the job, so gn-cli
#                           keeps its state out of the runner's home

set -euo pipefail

readonly RELEASES_URL='https://rsys.ganamaga.me/gn-cli/public/releases.json'

dir="${RUNNER_TEMP:?}/gn-cli"
rm -rf "$dir" && mkdir -p "$dir"

curl -fsSL --retry 3 -o "$dir/releases.json" "$RELEASES_URL"
# Anything in front of rsys that answers with a page instead (Anubis's "Making
# sure you're not a bot!" challenge, say) still comes back 200, so check.
if ! jq -e 'type == "array"' "$dir/releases.json" > /dev/null 2>&1; then
  echo "::error::$RELEASES_URL did not answer with JSON; it starts with:"
  head -c 300 "$dir/releases.json"; echo
  if grep -qi 'not a bot\|anubis' "$dir/releases.json"; then
    echo "::error::That is an Anubis bot challenge: the policy on the server does not let rsys.ganamaga.me through (see the release-system rule in nextgen's nextgen_backend/botPolicy.yaml)."
  fi
  exit 1
fi
read -r version want url < <(jq -r '
  [ .[]
    | (.files | map({key: .filename, value: .}) | from_entries) as $f
    | ($f["gn-cli-linux-x64"] // $f["gn-cli"]) as $bin
    | select($bin != null)
    | "\(.version) \($bin.sha256) \($bin.url)" ]
  | first // empty' "$dir/releases.json")

if [ -z "${version:-}" ]; then
  echo '::error::no published gn-cli release has a Linux binary (gn-cli-linux-x64 or gn-cli)'
  exit 1
fi

echo "using gn-cli $version"
curl -fsSL --retry 3 -o "$dir/gn-cli" "$url"
if ! echo "$want  $dir/gn-cli" | sha256sum --check --status; then
  echo '::error::the downloaded gn-cli does not match its sha256'
  exit 1
fi
chmod +x "$dir/gn-cli"
"$dir/gn-cli" --version

if [ -n "${GITHUB_PATH:-}" ]; then
  echo "$dir" >> "$GITHUB_PATH"
fi
if [ -n "${GITHUB_ENV:-}" ]; then
  echo "GN_CLI_CONFIG_DIR=$RUNNER_TEMP/gn-cli-config" >> "$GITHUB_ENV"
fi
