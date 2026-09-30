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

set -euo pipefail

readonly RELEASES_URL='https://rsys.ganamaga.me/gn-cli/public/releases.json'

dir="${RUNNER_TEMP:?}/gn-cli"
rm -rf "$dir" && mkdir -p "$dir"

curl -fsSL --retry 3 -o "$dir/releases.json" "$RELEASES_URL"
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
