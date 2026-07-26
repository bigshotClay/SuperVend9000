#!/usr/bin/env bash
# Statistical ablation on the FROZEN hostile sim: failure rate of each arm across N seeds.
# failure = terminated (bankrupt) OR final net worth < starting capital ($500 = 50000c).
#   scripts  = fundamentals only, no model (fast, deterministic)
#   bare     = model only, no harness
#   full     = harness + model
# Proof target: full failure rate < scripts failure rate, at statistical significance.
#
# Usage: HARNESS_BASE_URL=... scripts/ablation-hostile.sh [N] [days]
set -uo pipefail
N="${1:-12}"; DAYS="${2:-150}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
BIN="app/build/install/app/bin/app"
OUT="runs/ablation-hostile"; mkdir -p "$OUT"
FAILT=50000  # net worth below this (starting capital) counts as a failure

fail_of() { # db -> "1" fail / "0" ok
  local db="$1"
  local nw term
  nw=$(sqlite3 "$db" "SELECT json_extract(report_json,'\$.netWorthAfter') FROM day_report ORDER BY day DESC LIMIT 1" 2>/dev/null)
  term=$(sqlite3 "$db" "SELECT SUM(CASE WHEN json_extract(report_json,'\$.terminated')='true' THEN 1 ELSE 0 END) FROM day_report" 2>/dev/null)
  if [ -z "$nw" ] || [ "${term:-0}" -gt 0 ] || [ "$nw" -lt "$FAILT" ]; then echo 1; else echo 0; fi
}

declare -A F=( [scripts]=0 [bare]=0 [full]=0 )
echo "=== hostile ablation: N=$N seeds, $DAYS days, fail<\$500 or bankrupt ==="
for s in $(seq 1 "$N"); do
  # scripts (no model, fast)
  $BIN run --model mock --full --sim hostile --decisions script --seed "$s" --days "$DAYS" --out "$OUT/scripts-$s.db" >/dev/null 2>&1
  fs=$(fail_of "$OUT/scripts-$s.db"); F[scripts]=$((F[scripts]+fs))
  # bare (model only)
  $BIN baseline --model gemma3:4b --sim hostile --seed "$s" --days "$DAYS" --out "$OUT/bare-$s.db" >/dev/null 2>&1
  fb=$(fail_of "$OUT/bare-$s.db"); F[bare]=$((F[bare]+fb))
  # full (harness + model)
  $BIN run --model gemma3:4b --full --sim hostile --decisions model --seed "$s" --days "$DAYS" --out "$OUT/full-$s.db" >/dev/null 2>&1
  ff=$(fail_of "$OUT/full-$s.db"); F[full]=$((F[full]+ff))
  echo "seed $s done  scripts=$fs bare=$fb full=$ff   running fails: scripts=${F[scripts]} bare=${F[bare]} full=${F[full]}  (of $s)"
done
echo "==== FAILURE RATES (of $N) ===="
echo "scripts-only : ${F[scripts]}/$N"
echo "bare-model   : ${F[bare]}/$N"
echo "full harness : ${F[full]}/$N"
