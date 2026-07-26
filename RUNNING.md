# Running the Harness

A Kotlin/JVM weak-model Vending-Bench harness. Every run is a single self-contained SQLite file,
fully attributable from its `run_manifest`. See `CLAUDE.md` for the build brief and `docs/` for the
specs; design decisions are logged in `docs/OPERATOR-INPUT.md`.

## Prerequisites

- JDK 21 (the Gradle toolchain pins it).
- For **real** models: an OpenRouter API key in `OPENROUTER_KEY`. It lives in your login shell
  (`.zshrc`); nothing in this repo reads, logs, or commits it. CI and all tests run with **zero
  network** via the built-in `mock` model.

## Build

```bash
./gradlew build            # compile + full test suite (zero network)
./gradlew :app:installDist # produces app/build/install/app/bin/app
```

Use `app/build/install/app/bin/app` as the `harness` CLI below (or `./gradlew :app:run --args="…"`).

## The CLI (software spec §12)

| Command | Purpose |
|---|---|
| `harness qualify --model <id> [--out db]` | >90% instruction-following battery; gates entry to the matrix |
| `harness baseline --model <id> --seed <n> [--days N] [--out db]` | the naive control arm (no plan/gates/recovery) |
| `harness run --model <id> --seed <n> [--days N] [--out db]` | the gated harnessed arm |
| `harness report --runs '<glob>' [--out md]` | the four headline tables across run DBs |
| `harness validate --step <id> --artifact <path>` | run a step's validator over an artifact file (exit 0/1) |
| `harness models` | print the OpenRouter free-tier roster (uncurated) |
| `harness plan [--out json]` | export Master Workplan v1 as canonical JSON |
| `harness org` | print the persona roster |

## Offline demonstration (no key, no network)

```bash
BIN=app/build/install/app/bin/app
$BIN run      --model mock --seed 1 --days 20 --out runs/harness-mock-1.db
$BIN baseline --model mock --seed 1 --days 20 --out runs/baseline-mock-1.db
$BIN report   --runs 'runs/*.db'
```

The gated arm keeps the business solvent and posts a materially higher net worth than the naive arm,
with a zero column across the G3 failure classes — the headline result, reproducible from the run
DBs alone.

## Full experiment matrix (real models — uncurated by design)

Do **not** hand-pick models. The thesis is model-independence, so the strongest result comes from
running over whatever the free tier happens to offer — the less curated the selection, the more
robust the claim. With no model arguments the matrix discovers the entire free tier itself:

```bash
export OPENROUTER_KEY=…            # already set if it is in your login shell
scripts/run-matrix.sh --seeds 5 --days 30
```

That runs every free model × 5 seeds × {baseline, harness} and writes `runs/matrix/report.md`.
Qualification is **recorded but not gating**: even a model that flunks the >90% battery should show
the harness winning, because the harness economics are script-driven (the model only writes a daily
summary). Pass `--gate` to restore PRD §7 skip-on-fail, or name explicit models to override
discovery. The thesis is confirmed when the report shows: harness min > naive mean per model (lift),
CV(models) < CV(seeds) (the flat curve), and zeros across the G3 failure classes — the flatter the
curve across a messier model set, the stronger the evidence.

## Reproducibility

Every threshold and config is a hashed artifact recorded in the run's `run_manifest`
(plan hash, gate-config sha, sim-config sha, seed, model id). Gates and validators are pure, so
`harness validate` re-runs bit-identically to what a run saw.

## Known gaps (see `docs/OPERATOR-INPUT.md`)

- `harness revalidate` (spec §12) needs on-disk artifact storage (spec §5.3), which is not yet
  implemented; the incident-replay guarantee itself is covered by module-level tests
  (`core/orchestrator` `ReplayAndRetryTest`).
- `harness run` drives a deterministic gated *script* policy rather than the full per-persona plan
  walk (OPERATOR-INPUT #19); the 34-step persona dispatch is exercised by the Stage 5 golden runs.
- Gate-ablation matrix cells (PRD R48c) are a post-M5 stretch (spec §15 OQ7).
```
