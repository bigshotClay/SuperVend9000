# Reproducibility package — Weak-Model Vending-Bench Harness

Raw run databases behind every number in the paper, plus stdlib scripts that regenerate them.
Nothing is precomputed by hand.

## Get the data

The databases are **not** committed to git (too large). They ship as a release asset:

- **[`data-v1` release](https://github.com/bigshotClay/SuperVend9000/releases/tag/data-v1)** → `supervend9000-reproducibility-data-v1.zip` (679 DBs, 50 MB).

Extract it **at the repository root** so the DBs land where the scripts expect:

```
unzip supervend9000-reproducibility-data-v1.zip -d .
```

This populates `reproducibility-package/dbs/*.db` (core three-arm + exploratory) and
`runs/r1a-box/`, `runs/r1b-box/`, `runs/r5-box/` (the §5.4 ablations, read by `verify_r1.py`
and `verify_r5.py`). Verify integrity with `shasum -a 256 -c CHECKSUMS.sha256`.

## Reproduce

```
python3 compute_results.py dbs
```

It prints a **SIM INTEGRITY CHECK** first (the gemma three-arm must share one `sim_config_sha`),
then the core three-arm with paired stats, the cross-model capability gradient, the capable-model
comparison, and the supporting long-horizon runs. `RESULTS.txt` is the committed expected output —
diff your run against it. No third-party packages.

## What's in `dbs/` (379 runs, one SQLite file each)

| prefix | experiment | N |
|---|---|---|
| `gemma-full-*` / `gemma-scripts-*` / `gemma-bare-*` | **core three-arm**, gemma3:4b, sim `64fa1ff5` | 100 each |
| `qwen-bare-*` | capability gradient — qwen2.5:7b, bare | 51 |
| `deepseek-bare-*` / `deepseek-full-*` | capability gradient + capable-model arm — DeepSeek-V4-Flash | 10 each |
| `live-*` | live model-vs-model vendor runs (sim `c5e337`, live-vendor variant) | 3 |
| `durability-*` | full-harness toward a 3,000-day cap (sim `071fd396`, dynamics-identical to `64fa1ff5`) | 5 |

## Headline (from `RESULTS.txt`)

- **Core three-arm** (gemma3:4b, N=100): full $1,725 / 46% ≫ scripts $735 / 59% ≫ bare $86 / 100%.
  Paired: full−bare ~7.2σ, scripts−bare ~6.2σ, full−scripts decisive on net worth (t=7.5) but only
  ~2.8σ on failure rate (we do **not** claim a significant bankruptcy-rate edge over fundamentals).
- **Capability gradient** (bare): gemma $86/100% → qwen $171/100% → DeepSeek $2,452 mean / $972
  median / 50%. A frontier-tier model is the first that stays solvent bare — and only half the time.
- **Harness on a capable model**: DeepSeek full $6,060/10% > its own bare $2,452/50% — the harness
  helps even the strong model. (The earlier "bare beats full" was a below-cost procurement exploit,
  since fixed; the discarded runs are in the project's `runs/_ARCHIVE/`.)

## Definitions & caveats

- **Net worth** = final `netWorthAfter` (integer cents in `day_report`). **Failure** = terminated
  (bankrupt) OR final net worth < $500. Cross-arm tests are **paired** on seed (deterministic sim).
- The `full`/`bare` arms call a model, so their figures are one sampling draw — a re-run reproduces
  the effect sizes, not the exact dollars. `scripts` and the sim are deterministic.
- Single-model core (gemma); the DeepSeek/qwen arms are the first steps toward a full capability
  ladder, not a proven flat curve. Under the harness, capability still matters (~3.5× gemma→DeepSeek).
