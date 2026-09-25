#!/usr/bin/env bash
#
# Turns a JaCoCo CSV report into a Markdown table for the run's summary page.
# GitHub has no built-in coverage view, and this avoids a third-party action
# for what is a sum over one file.
#
#   ./coverage-summary.sh <title> <jacoco.csv>
#
# Writes to $GITHUB_STEP_SUMMARY when set, otherwise to stdout.

set -euo pipefail

title="$1"
csv="$2"
out="${GITHUB_STEP_SUMMARY:-/dev/stdout}"

if [ ! -f "$csv" ]; then
  printf '### %s\n\nNo coverage report was produced.\n' "$title" >> "$out"
  exit 0
fi

# Columns: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,
#          BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,...
awk -F, -v title="$title" '
  NR > 1 { im += $4; ic += $5; bm += $6; bc += $7; lm += $8; lc += $9 }
  function pct(covered, missed) {
    return covered + missed == 0 ? "n/a" : sprintf("%.1f%%", 100 * covered / (covered + missed))
  }
  END {
    printf "### %s\n\n| Counter | Covered | Total | Coverage |\n|---|---:|---:|---:|\n", title
    printf "| Lines | %d | %d | %s |\n", lc, lc + lm, pct(lc, lm)
    printf "| Branches | %d | %d | %s |\n", bc, bc + bm, pct(bc, bm)
    printf "| Instructions | %d | %d | %s |\n\n", ic, ic + im, pct(ic, im)
  }
' "$csv" >> "$out"
