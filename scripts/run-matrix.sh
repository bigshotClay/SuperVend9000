#!/usr/bin/env bash
# Run the full experiment matrix (software spec §14, PRD R48): for each qualifying model, both arms
# (naive baseline + gated harness) across N seeds, then emit the four headline tables.
#
# Usage:
#   scripts/run-matrix.sh [--days N] [--seeds N] [--out DIR] [--gate] [MODEL ...]
#
# With no MODEL args it discovers the ENTIRE OpenRouter free tier (`harness models`) and runs all of
# them — deliberately uncurated. The thesis is model-independence, so the less we curate the model
# set, the more robust the result: whatever the free tier hands us is exactly what we want. Pass
# explicit model ids to override, or "mock" for an offline, zero-network demonstration.
#
# By default qualification is RECORDED but never gates: even a model that flunks the >90% battery
# should show the harness winning (the harness economics are script-driven — the model only writes a
# daily summary). Pass --gate to restore PRD §7 skip-on-fail behavior.
#
# Real models require OPENROUTER_KEY in the environment (login shell only; this script never reads or
# prints it).
set -euo pipefail

DAYS=30
SEEDS=5
OUT="runs/matrix"
GATE=0
QUALIFY=1
OLLAMA=""
MODELS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --days)        DAYS="$2";  shift 2 ;;
    --seeds)       SEEDS="$2"; shift 2 ;;
    --out)         OUT="$2";   shift 2 ;;
    --gate)        GATE=1;     shift ;;
    --no-qualify)  QUALIFY=0;  shift ;;  # skip the battery entirely (saves the rate budget)
    --ollama)      OLLAMA="$2"; shift 2 ;; # local Ollama host, e.g. localhost[:11434]
    -*)            echo "unknown flag $1" >&2; exit 2 ;;
    *)             MODELS+=("$1"); shift ;;
  esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> building the CLI"
./gradlew --quiet :app:installDist
HARNESS="app/build/install/app/bin/app"

# Local Ollama server: point the harness at its OpenAI-compatible endpoint, skip qualification (no
# rate budget to conserve), and discover its chat models (excluding embedding models).
if [[ -n "$OLLAMA" ]]; then
  [[ "$OLLAMA" == *:* ]] || OLLAMA="$OLLAMA:11434"
  export HARNESS_BASE_URL="http://$OLLAMA/v1/chat/completions"
  QUALIFY=0
  echo "==> Ollama endpoint $HARNESS_BASE_URL"
  if [[ ${#MODELS[@]} -eq 0 ]]; then
    mapfile -t MODELS < <(curl -s -m 10 "http://$OLLAMA/api/tags" | python3 -c '
import json,sys
d=json.load(sys.stdin)
EMBED={"bert","nomic-bert"}
for m in d.get("models",[]):
    if m.get("details",{}).get("family","") not in EMBED:
        print(m["name"])')
    echo "==> ${#MODELS[@]} local chat models"
  fi
fi

# No models named → take the whole OpenRouter free tier, verbatim and uncurated.
if [[ ${#MODELS[@]} -eq 0 ]]; then
  echo "==> discovering the OpenRouter free tier"
  mapfile -t MODELS < <("$HARNESS" models 2>/dev/null)
  if [[ ${#MODELS[@]} -eq 0 ]]; then
    echo "no models discovered (is OPENROUTER_KEY set?); pass models explicitly or use 'mock'" >&2
    exit 2
  fi
  echo "==> ${#MODELS[@]} free models"
fi

mkdir -p "$OUT"
# Each run is a fresh SQLite file; clear any prior run DBs so a re-use of $OUT can't collide on
# run_id (a leftover DB is reopened and the manifest INSERT fails a PRIMARY KEY constraint) or
# pollute the report glob with stale models. $OUT defaults to a gitignored runs/ dir.
rm -f "$OUT"/*.db "$OUT"/*.db-journal "$OUT"/*.db-wal 2>/dev/null || true
echo "==> matrix: ${#MODELS[@]} model(s), seeds=$SEEDS days=$DAYS gate=$GATE out=$OUT"

for model in "${MODELS[@]}"; do
  [[ -z "$model" ]] && continue
  slug="$(echo "$model" | tr '/:' '__')"

  # Record qualification as data (mock is exempt); only skip when --gate is set.
  if [[ "$QUALIFY" == "1" && "$model" != mock* ]]; then
    echo "==> qualify $model"
    qualified=0
    "$HARNESS" qualify --model "$model" --out "$OUT/qualify-$slug.db" | tee /dev/stderr | grep -q "^QUALIFIED" && qualified=1
    if [[ "$GATE" == "1" && "$qualified" == "0" ]]; then
      echo "!! $model did not qualify — skipping (--gate, PRD §7)" >&2
      continue
    fi
  fi

  for seed in $(seq 1 "$SEEDS"); do
    echo "==> $model seed=$seed  (baseline + harness)"
    # Fresh DB per run (idempotent re-runs); keep going even if one arm/model errors
    # (rate limits, endpoint drop) — the matrix is robust to gaps.
    bdb="$OUT/baseline-$slug-$seed.db"; hdb="$OUT/harness-$slug-$seed.db"
    rm -f "$bdb" "$bdb"-journal "$bdb"-wal "$hdb" "$hdb"-journal "$hdb"-wal 2>/dev/null || true
    "$HARNESS" baseline --model "$model" --seed "$seed" --days "$DAYS" --out "$bdb" || echo "!! baseline $model seed=$seed failed" >&2
    "$HARNESS" run      --model "$model" --seed "$seed" --days "$DAYS" --out "$hdb"  || echo "!! harness $model seed=$seed failed" >&2
  done
done

echo "==> report"
"$HARNESS" report --runs "$OUT/*.db" --out "$OUT/report.md"
cat "$OUT/report.md"
