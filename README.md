# SuperVend9000 — a harness that lets a 4B model run a hostile business

> **Companion to the writeup:** *"Why You Should Not Put Load-Bearing Instructions on a Long-Running Agent"* — see [`docs/PAPER.md`](docs/PAPER.md) for the full narrative, [`docs/RESULT-SUMMARY.md`](docs/RESULT-SUMMARY.md) for the terse version, and [`docs/SIMULATOR.md`](docs/SIMULATOR.md) for how the three arms drive the simulator (with diagrams).

A deterministic **harness** — externalized state, one-directional gates, validated procurement — that lets one of the weakest usable open models (`gemma3:4b`) run a deliberately adversarial vending-machine simulation far better than either the raw model or hand-written rules alone. The point isn't the vending machine; it's that **long-horizon agentic performance is a property of the scaffolding, not just the model** — and every claim here is reproducible from the raw run databases.

## The result (N=100, one sim, matched seeds)

| arm | failure rate | mean net worth | median net worth |
|-----|:---:|:---:|:---:|
| bare (model only) | **96%** | $80 | $28 |
| scripts (fundamentals only) | 59% | $735 | $29 |
| **full (harness + model)** | **46%** | **$1,725** | **$1,415** |

**full ≫ scripts ≫ bare.** The typical harnessed run is a real business ($1,415 median); the typical model-only and rules-only run is broke. Paired tests (matched seeds): full−bare ~6.7σ, scripts−bare ~5.6σ, full−scripts t=7.5 on net worth (but only ~2.8σ on failure rate — the harness earns its keep on the *quality* of survival, not the survival rate). Full honest caveats are in the writeup.

## Reproduce it yourself

The raw run databases (300 SQLite files, one per run) ship as a **[release asset](../../releases)** — `vending-harness-reproducibility.zip`. No third-party Python needed:

```bash
# download vending-harness-reproducibility.zip from the Releases page, then:
unzip vending-harness-reproducibility.zip
python3 compute_results.py dbs
```

It prints a **sim-integrity check first** (every run must share one `sim_config_sha`), then regenerates the table and every paired statistic from the DBs. The committed [`reproducibility-package/RESULTS.txt`](reproducibility-package/) is the expected output — diff your run against it. `CHECKSUMS.sha256` covers every DB.

## What's here

```
core/        the harness: model/state/llm/gates/validate/plan/recovery/procure/pricing/audit/orchestrator
app/         CLI entrypoints — harness run / baseline / qualify / report
sim/         the deterministic vending-bench environment (scripted + live-vendor modes)
personas/    role/creed system for the decomposed agents
docs/        the writeup, the PRD/spec, the result summary, the operator-input decision log
scripts/     run orchestration (matrix, ablation)
reproducibility-package/   compute_results.py + README + RESULTS.txt + CHECKSUMS.sha256
```

Stack: Kotlin/JVM (JDK 21), Gradle, kotlinx.serialization, SQLite (thin DAO), Ktor, Kotest, Clikt. Money is a `Cents` value class; gates and validators are pure and re-runnable bit-identically. See [`CLAUDE.md`](CLAUDE.md) for the design brief and hard constraints.

## Build & run

```bash
./gradlew installDist                       # build the CLI
export OPENROUTER_KEY=...                    # only for real models; or use a local server / mock
app/build/install/app/bin/app baseline --model mock --seed 1     # deterministic, no network
app/build/install/app/bin/app run --model gemma3:4b --seed 1     # via local Ollama (set HARNESS_BASE_URL)
```

CI runs fully offline (MockModel everywhere; only the nightly E2E touches a real model). API keys are read from the environment, never committed.

## The two theses

1. **Externalize the discipline.** If a script can do it, a script does it; the model is a gated judgment layer on top of a floor that is correct by construction. A weak model on that floor becomes a competent operator; the same model bare is near-hopeless (96% failure).
2. **The failure mode of a long-running agent is the load-bearing step you left to its judgment.** This one the project proved *the hard way* — running the experiment itself failed twice at ungated steps (an agent destroyed a box before syncing; a silent flag ran the wrong sim for days). Both are written up honestly in the article; both are why the reproducibility check exists.

## Limitations (short version; full list in the writeup)

- **Single model.** This shows a harness lets a *weak* model perform well; it does **not** yet show model-independence (the multi-model "flat curve" is the open next experiment).
- **Stochastic arms.** `full`/`bare` call the model, so their numbers are one valid draw — a re-run reproduces the *effect sizes*, not the exact dollars. `scripts` and the sim are deterministic (bit-identical on re-run).

## Credit

The harness design derives from patterns in the open-source [BMAD-METHOD](https://github.com/bmad-code-org/BMAD-METHOD) agentic framework.

## License

Apache-2.0 — see [`LICENSE`](LICENSE). Copyright © 2026 Clay Pierce.
