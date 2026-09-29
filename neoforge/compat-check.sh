#!/usr/bin/env bash
#
# Compiles the mod against the newest NeoForge of every Minecraft version in
# the supported range, proving the API it uses exists in all of them.
#
# Each shipped jar is built against the OLDEST NeoForge of its line (see
# build.gradle). That is what makes it run on the whole line: a mod compiled
# against an old API links against newer ones, not the other way round. This
# script is the check that the old API has not been removed further along.
#
# Every version has Minecraft decompiled and recompiled for it the first time,
# which takes a few minutes and about a gigabyte of Gradle cache each.
#
# Usage: neoforge/compat-check.sh [line:version ...]
#
#   With no arguments, checks every version below. Pass line:version pairs,
#   such as 26:26.2.0.88, to check only those.

set -euo pipefail

cd "$(dirname "$0")"

# line:neoforge-version, one per Minecraft version from 1.21.1 to 26.3. There is
# no NeoForge for 1.21.2, and 26.1 and 26.1.1 only ever had betas.
DEFAULT_VERSIONS=(
  1.21:21.1.252
  1.21:21.3.97
  1.21:21.4.158
  1.21:21.5.98
  1.21:21.6.20-beta
  1.21:21.7.25-beta
  1.21:21.8.54
  1.21:21.9.16-beta
  1.21:21.10.64
  1.21.11:21.11.45
  26:26.1.2.112
  26:26.2.0.88
  # 26.3 has no stable NeoForge yet; swap in the first stable one once it ships.
  26:26.3.0.35-beta
)

VERSIONS=("$@")
if [ ${#VERSIONS[@]} -eq 0 ]; then
  VERSIONS=("${DEFAULT_VERSIONS[@]}")
fi

WORK="build/compat-check"
mkdir -p "$WORK"

passed=0
failed=0
failures=()

for entry in "${VERSIONS[@]}"; do
  line="${entry%%:*}"
  version="${entry#*:}"
  printf '%-10s %-22s ' "$line" "$version"
  log="$WORK/$version.log"
  if ./gradlew --no-daemon -q -Pline="$line" -Pneo_version="$version" compileJava > "$log" 2>&1; then
    echo "ok"
    passed=$((passed + 1))
  else
    echo "FAIL"
    grep -E 'error:|FAILURE|What went wrong' -A3 "$log" | sed -n '1,20p'
    failed=$((failed + 1))
    failures+=("$line:$version")
  fi
done

echo
echo "compiled against ${passed}, failed ${failed}"
if [ "$failed" -gt 0 ]; then
  printf 'failed: %s\n' "${failures[@]}"
  exit 1
fi
