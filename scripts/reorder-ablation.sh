#!/usr/bin/env bash
# Reordered hostile ablation: front-load the BARE arm (cheap, ~12min each) to lock X" fast,
# then run the FULL arm (slow, ~65min each) overnight. Waits for the in-flight full-1 to finish
# so we don't waste it. scripts arm already collected separately (runs/scripts-baseline, X=52%).
set -uo pipefail
export HARNESS_BASE_URL="${HARNESS_BASE_URL:-http://localhost:11434/v1/chat/completions}"
export HARNESS_TIMEOUT_MS=120000
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
BIN="app/build/install/app/bin/app"
OUT="runs/ablation-hostile"; mkdir -p "$OUT"
LOG="$OUT/reorder.log"; MODEL="gemma3:4b"; DAYS=150; N=12
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

# 1. Wait for the in-flight full-1 (pid passed as $1) to finish, if still alive.
WAIT_PID="${1:-}"
if [ -n "$WAIT_PID" ]; then
  say "waiting for in-flight full-1 (pid $WAIT_PID) to finish..."
  while kill -0 "$WAIT_PID" 2>/dev/null; do sleep 30; done
  say "full-1 finished."
fi

# 2. BARE arm first, seeds 2..N (seed 1 already done).
say "=== BARE arm: seeds 2..$N ==="
for s in $(seq 2 $N); do
  say "bare seed $s starting"
  $BIN baseline --model "$MODEL" --sim hostile --seed "$s" --days "$DAYS" --out "$OUT/bare-$s.db" >>"$LOG" 2>&1
  nw=$(sqlite3 "$OUT/bare-$s.db" "SELECT json_extract(report_json,'\$.netWorthAfter') FROM day_report ORDER BY day DESC LIMIT 1" 2>/dev/null)
  say "bare seed $s done: netWorth=${nw:-NA}c"
done
say "=== BARE arm complete ==="

# 3. FULL arm, seeds 2..N (seed 1 already done).
say "=== FULL arm: seeds 2..$N ==="
for s in $(seq 2 $N); do
  say "full seed $s starting"
  $BIN run --model "$MODEL" --full --sim hostile --decisions model --seed "$s" --days "$DAYS" --out "$OUT/full-$s.db" >>"$LOG" 2>&1
  nw=$(sqlite3 "$OUT/full-$s.db" "SELECT json_extract(report_json,'\$.netWorthAfter') FROM day_report ORDER BY day DESC LIMIT 1" 2>/dev/null)
  say "full seed $s done: netWorth=${nw:-NA}c"
done
say "=== ALL DONE ==="
