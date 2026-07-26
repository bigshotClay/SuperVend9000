# Weak-Model Vending-Bench Harness — Result Summary

## Claim
A harness that externalizes state, planning, validation, and adversarial procurement lets one of the
weakest usable models (`gemma3:4b`) run a hostile business simulation **substantially better than either
the model alone or disciplined fundamentals alone.** On one hostile sim at **N=100 matched seeds**, the
harnessed arm dominates both, monotonically on mean *and* median: **full ≫ scripts ≫ bare**. The net-worth
comparisons are decisive (full−bare t=8.4, scripts−bare t=5.9, full−scripts t=7.5); the harness's edge over
fundamentals on *failure rate* is borderline (~2.8σ), so that half of the headline rests on net worth, not
survival.

## Setup
- **Sim:** deliberately adversarial (not benchmarked as strictly "harder") vs real Vending-Bench — 8
  products, seasonality/weather/finite storage, an all-adversarial vendor roster (greedy negotiators +
  a rug-pull + a prepay scammer), per-seed vendor-harshness draw. Same core economics ($500 start, $2/day
  fee, bankrupt after 10 unpaid days). 200-day horizon.
- **Three arms**, sharing the same `sim/`; only the decision layer differs:
  - **scripts** — fundamentals only, no model (the disciplined floor)
  - **bare** — model only, self-managed memory, no harness (the rot control)
  - **full** — harness + model (state brief → gates → validated procurement → model judgment on top)
- **N = 100 matched seeds, all three arms on one verified sim** (`sim_config_sha 64fa1ff5`), `gemma3:4b`
  via local Ollama. All runs archived locally and recomputed from the DBs.

## Core result

**N = 100, single sim (`64fa1ff5`), matched seeds — mean *and* median reported (the distribution is
heavily right-skewed, so the median is the honest "typical run"):**

| arm | failure rate | mean net worth | median net worth |
|-----|:---:|:---:|:---:|
| bare (model only) | **96%** | $80 | $28 |
| scripts (fundamentals only) | 59% | $735 | $29 |
| **full (harness + model)** | **46%** | **$1,725** | **$1,415** |

The medians carry the story: **the typical full run is a real business ($1,415); the typical scripts and
bare run is broke ($29 / $28).** Only the harnessed arm has a positive central outcome.

Paired tests (matched seeds, N=100):

| comparison | net worth Δ/seed | median Δ | paired t | McNemar (rescues/losses) | ~σ |
|---|:---:|:---:|:---:|:---:|:---:|
| **full − bare** | +$1,639 | +$1,364 | 8.3 | 54 / 0 | **~7.2σ** |
| **scripts − bare** | +$649 | −$38 | 5.8 | 41 / 0 | **~6.2σ** |
| **full − scripts** | +$990 | +$386 | 7.5 | 16 / 3 | **~2.8σ** |

*(McNemar losses are **0** for both bare comparisons because the corrected bare arm fails 100% — it never survives where full or scripts don't. The scripts−bare median Δ is slightly negative because both arms' median runs are failures and bare's land a touch higher; scripts still wins decisively on mean and failure rate.)*

Two honest reads of that table: (a) **scripts ≫ bare is decisive** (t=5.8, ~6.2σ) — fundamentals-alone
clearly beats the model-alone; the earlier "near-tie" was the sim bug. (b) **full − scripts is decisive on
net worth (t=7.5) but only ~2.8σ on failure rate** — the harness's edge over fundamentals is about the
*quality* of survival, not the survival rate; we do **not** claim a significant failure-rate reduction over
fundamentals even at N=100.

## What the harness actually does (precise mechanism)
It is **not** mainly "prevents bankruptcy." Decomposing into *dead (bankrupt) / alive-but-broke (<$500) /
thriving (≥$500)*:

| arm | dead | alive-but-broke | thriving |
|-----|:---:|:---:|:---:|
| bare | 70% | 26% | 4% |
| scripts | 47% | 12% | 41% |
| full | 44% | 2% | 54% |

Full and scripts **die at nearly the same rate** (44% vs 47% — which is exactly why the failure-rate
McNemar is only ~2.8σ). The harness's contribution over fundamentals is **"don't idle, not don't die"**:
it nearly eliminates the alive-but-broke middle (12% → 2%) and converts it into thriving businesses
(41% → 54%). Against the model alone the harness dominates on every axis — bare is **70% outright dead and
only 4% thriving**: on a genuinely adversarial market, `gemma3:4b` without scaffolding is essentially
non-viable.

*(Stochasticity note: the full and bare arms call the model, whose sampling varies run-to-run, so those
figures are one valid draw — a re-run reproduces the effect sizes, not the exact dollars. The scripts arm
and the sim itself are deterministic: scripts regenerated **bit-identical** across two `sim_config_sha`
values, which is how we confirmed the sim never actually changed and cross-validated the pipeline.)*

## Honest boundaries
- **Single model.** This shows *"a harness lets a weak model perform well,"* **not** the stronger
  *"performance is independent of model capability"* — that requires the multi-model flat curve
  (CV-across-models < CV-across-seeds), which is not yet run.
- **Scripted adversaries → now also run live (see below).** The core ablation vendors are behavior
  profiles, not live agents. The "you tuned your adversary" objection is addressed by the live-vendor
  runs in the **Live adversaries** section below — a *more capable, different-family* model negotiating live.
- **A documented negative result.** We hypothesized the harness's one weakness — a "starvation edge"
  where its margin discipline refuses predatory vendors and dies with empty shelves — was a fixable bug,
  and prototyped a fix (vendor rotation + an option-value valuation). It looked like a clean +6 survivors
  at N=48; **at N=136 it evaporated** (no significant survival gain; **−$208/seed net worth**).
  Conclusion: the starvation is a *deliberate net-worth-maximizing policy*, not a bug — the original
  design was right. Reported because it's the kind of overfit high-N testing is *for*. **Caveat:** the
  N=136 DBs did not survive the box loss — this one is from notes, **not** in the reproducibility archive
  and not re-verified here; treat it as the weakest-sourced claim in the summary.
- **Durability (result).** Five near-median full-harness seeds were pushed to a 3,000-day cap (on
  `sim_config_sha 071fd396` — the original hostile, proven *bit-identical in dynamics* to the ablation's
  `64fa1ff5` via the deterministic scripts arm, so the same environment). The split
  is on-distribution with the ~46% base rate: **2/5 bankrupt early** (days 76 and 78) and **3/5 stay
  coherent and compound with zero rot** — net worth *monotonically rising* through and well past the
  365-day Vending-Bench horizon. At the benchmark year (day 365) the three survivors sit at
  **$9,729 / $11,648 / $11,323**; left running they keep climbing to **~$12.6k–$16.6k by day ~500**
  (seed 9: $4.2k@180 → $11.3k@365 → $16.6k@519) before the cloud Ollama servers died and stalled them
  (the harness has no DB-resume, so they end at day ~500, not the 3,000 cap). The point stands without
  the cap: a bottom-tier model, harnessed, shows **no long-horizon coherence collapse** — it either dies
  early or runs a business for 500+ days with net worth compounding throughout. (Dollar totals are **not**
  comparable to the published Vending-Bench leaderboard — this is a different, custom economy — so no
  leaderboard multiple is claimed; the point is coherence over the horizon, not the figure.) Long-horizon
  coherence is exactly what externalized state buys, and it's the benchmark's actual hard part.

## Live adversaries (result — closes the "you tuned your adversary" objection)
The scripted vendors were replaced with **live LLM agents**: model-vs-model procurement. Buyers stayed
`gemma3:4b`; vendors became **`qwen2.5:7b`** — a *different, more capable* model from a different family,
generating replies live (planned `qwen2.5:14b`, dropped to 7b for throughput on the 2×3090 box; still
comfortably above the buyer). Only reply *generation* on the vendor side became a model call — the
harness's negotiation machinery is unchanged. Three median-cluster seeds were run until stopped by
**budget, not failure**:

| seed | last day | final net worth | outcome |
|---|:---:|:---:|---|
| 16 | 577 | $5,512 | survived — struggler, held positive, never compounded |
| 23 | 606 | $13,680 | survived — steady compounding past the benchmark year |
| 39 | 985 | $71,165 | survived — strong compounding, ~142× the $500 stake |

**Zero bankruptcies across 577–985 sim-days against a larger, un-tuned, cross-family negotiator.** The
harnessed bottom-tier buyer stays coherent and mostly *grows* for hundreds of days while a stronger model
bargains against it live; the one-struggler/two-thrivers spread shows the live vendors neither trivialized
the sim nor broke it. **Caveats:** (1) **n = 3** — seeds are the earlier run's median cluster, fixed before
these runs, so this shows long-horizon coherence is *achievable*, not a survival *rate* (3 seeds can't set
one); (2) **different sim** (`sim_config_sha c5e337`, the live-vendor variant) than the N=100 ablation, so
dollar figures don't compare across the two; (3) **attributability gap** — these early live runs didn't
persist the vendor model id to the manifest (confirmed out-of-band from the launch config; fixed — see
`OPERATOR-INPUT.md` §22). Net worth read directly from the ledgers, `terminated = false` in every case.

## Meta take
**Neither component works alone.** On the hostile sim the same `gemma3:4b` is **near-worthless bare** —
96% failure, $28 median, only 4% of runs reach a viable business — and a **competent operator once
scaffolded** ($1,415 median, 54% thriving). Fundamentals-alone sits between, mediocre (59% fail, $29
median). So the result is **complementary**: the scaffold *unlocks* a model otherwise non-viable in an
adversarial market, and on that floor the model adds real judgment — full − scripts = **+$990/seed
(t = 7.5)**, decisive on net worth. The honest one-liner is **"scaffolding unlocks a bottom-tier model"** —
*not* "scaffolding replaces the brain" (the model's marginal over the floor is far too large) and *not*
"the model carries it" (bare, it's near-worthless). Which side contributes *more* isn't cleanly separable
and isn't the interesting question.

The higher-value open question is **model-independence**: does the scaffold unlock models of *any*
capability *equally* — the flat curve, CV-across-models < CV-across-seeds? That is what would make
"the harness, not the brain" fully general, and it remains open.

## On the "bare" arm — a control, not a capability verdict
Bare fails catastrophically (96%), but that is **not** evidence the model is incapable of the task. Bare
runs the model *unaided* — no ledger, no externalized state — so its failures are **context rot**
(miscounted cash, forgotten orders), i.e. unaided working-memory endurance, not bad business judgment. (The
runs bear this out: bare plays ~143 days on average before folding — it mismanages into bankruptcy over a
long horizon, it doesn't crash out early.) No competent human runs a vending machine in their head for 200
days either; competent long-horizon cognition is *always* scaffolded (ledgers, ERP, checklists). Stripping
that away measures **the absence of the harness, not the ceiling of the model** — "handicap it, watch it
fail, call it the model's limit" measures the handicap. Two guardrails: (1) bare is the **correct control**
— it isolates the scaffold's marginal, and we rest no capability verdict on it; (2) the genuinely *fair*
capability test — give the model tools and let it scaffold *itself* — is a **distinct experiment we did not
run**; "unaided operation fails" ≠ "couldn't do it with tools it chose to build."

---

*Statistics: matched-seed (paired) tests throughout — McNemar for the binary survival flips, paired-t +
Wilcoxon signed-rank for net worth — because the sim is deterministic per seed and the arms share it, so
pairing removes between-seed environment variance. Failure = terminated (bankrupt) OR final net worth
< $500. Full per-seed data is in the reproducibility archive — `compute_results.py` regenerates every figure from the 300 run DBs.*
