#!/usr/bin/env bash
#
# Compiles the companion's sources against every Paper API in the supported
# range, proving the API surface it uses exists in all of them.
#
# The shipping jar is built against the OLDEST API only (see pom.xml). That is
# what makes it run everywhere: a jar compiled against an old API works on newer
# servers, but not the other way round. This script is the check that the old
# API's methods have not been removed further along the range.
#
# Versions whose bytecode the running JDK cannot read are skipped with a notice
# rather than failing: the 26.x line is compiled to Java 25, so covering the
# whole range needs JDK 25 or newer.
#
# Usage: paper/compat-check.sh [--strict] [version ...]
#
#   --strict  treat a skipped version as a failure. Use this before a release,
#             on a JDK new enough to read every API in the range, to prove the
#             whole range was actually checked rather than half of it.

set -euo pipefail

cd "$(dirname "$0")"

PAPER_REPO="https://repo.papermc.io/repository/maven-public/"
WORK="target/compat-check"

# Every Paper API release between the oldest and newest supported server.
DEFAULT_VERSIONS=(
  1.21.1-R0.1-SNAPSHOT
  1.21.3-R0.1-SNAPSHOT
  1.21.4-R0.1-SNAPSHOT
  1.21.5-R0.1-SNAPSHOT
  1.21.6-R0.1-SNAPSHOT
  1.21.7-R0.1-SNAPSHOT
  1.21.8-R0.1-SNAPSHOT
  1.21.9-R0.1-SNAPSHOT
  1.21.10-R0.1-SNAPSHOT
  1.21.11-R0.1-SNAPSHOT
  26.1.2.build.74-stable
  26.2.build.123-stable
  # 26.3 has no stable Paper build yet; swap in the first -stable one once it
  # ships.
  26.3.build.37-alpha
)

STRICT=false
if [ "${1:-}" = "--strict" ]; then
  STRICT=true
  shift
fi

VERSIONS=("$@")
if [ ${#VERSIONS[@]} -eq 0 ]; then
  VERSIONS=("${DEFAULT_VERSIONS[@]}")
fi

# Highest class file version this JDK can read: major 65 is Java 21, 69 is 25.
JDK_MAJOR=$(java -XshowSettings:properties -version 2>&1 | awk -F'= *' '/java.specification.version/{print $2}')
MAX_CLASS_FILE=$((JDK_MAJOR + 44))
echo "Running on JDK ${JDK_MAJOR}, which reads class files up to major ${MAX_CLASS_FILE}."
echo

SOURCES=$(find src/main/java -name '*.java')
mkdir -p "$WORK"

passed=0
skipped=0
failed=0
failures=()

for version in "${VERSIONS[@]}"; do
  printf '%-30s ' "$version"

  pom="$WORK/pom-$version.xml"
  cat > "$pom" <<POM
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>compat</groupId>
  <artifactId>check</artifactId>
  <version>0</version>
  <repositories>
    <repository><id>papermc</id><url>${PAPER_REPO}</url></repository>
  </repositories>
  <dependencies>
    <dependency>
      <groupId>io.papermc.paper</groupId>
      <artifactId>paper-api</artifactId>
      <version>${version}</version>
    </dependency>
  </dependencies>
</project>
POM

  classpath_file="$WORK/cp-$version.txt"
  if ! mvn -B -ntp -q -f "$pom" dependency:build-classpath \
        -Dmdep.outputFile="$(pwd)/$classpath_file" > "$WORK/resolve-$version.log" 2>&1; then
    echo "FAIL (could not resolve; see $WORK/resolve-$version.log)"
    failed=$((failed + 1))
    failures+=("$version: unresolved")
    continue
  fi

  # The API's own bytecode decides which JDK can compile against it.
  api_jar=$(tr ':' '\n' < "$classpath_file" | grep 'paper-api' | head -1)
  api_class_file=$(unzip -p "$api_jar" 'org/bukkit/event/Listener.class' \
    | od -An -tu1 -j6 -N2 | awk '{print $1 * 256 + $2}')

  if [ "$api_class_file" -gt "$MAX_CLASS_FILE" ]; then
    echo "SKIP (needs JDK $((api_class_file - 44)), running $JDK_MAJOR)"
    skipped=$((skipped + 1))
    continue
  fi

  release=$((api_class_file - 44))
  out="$WORK/classes-$version"
  rm -rf "$out" && mkdir -p "$out"
  if javac -nowarn --release "$release" -cp "$(cat "$classpath_file")" -d "$out" $SOURCES \
       > "$WORK/javac-$version.log" 2>&1; then
    echo "ok (release $release)"
    passed=$((passed + 1))
  else
    echo "FAIL"
    sed -n '1,15p' "$WORK/javac-$version.log"
    failed=$((failed + 1))
    failures+=("$version")
  fi
done

echo
echo "compiled against ${passed}, skipped ${skipped}, failed ${failed}"
if [ "$failed" -gt 0 ]; then
  printf 'failed: %s\n' "${failures[@]}"
  exit 1
fi
if [ "$skipped" -gt 0 ]; then
  echo "note: ${skipped} version(s) need a newer JDK than this one, so the top of"
  echo "      the range was not checked. Re-run on JDK 25+ to cover all of it."
  if [ "$STRICT" = true ]; then
    echo "--strict was given, so that counts as a failure."
    exit 1
  fi
fi
