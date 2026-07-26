# PRD: Weak-Model Vending-Bench Harness ("Harness of Harnesses")

**Status:** Draft v1
**Author:** Clay (interview-derived, drafted by Claude)
**Date:** 2026-07-13
**Downstream doc:** Software Specification (to follow)

---

## 1. Problem Statement & Thesis

Vending-Bench results are currently a proxy for frontier-model capability. Published failure analysis shows that most of what separates a $10k run from a $300 meltdown is not intelligence — it is coherence: state tracking, routine adherence, rule fidelity, and resistance to self-conditioning on accumulated errors. These are properties that ordinary software provides deterministically.

**Thesis:** A harness that externalizes state, planning, validation, and procedure — leaving the LLM only genuine language tasks — can make Vending-Bench performance approximately independent of model capability. The result should hold on the *weakest* free-tier models available, not merely cheap frontier-adjacent ones.

**Product:** A recursive orchestration harness that runs the Vending-Bench simulation using only free OpenRouter models (`openrouter/free` tier), producing competitive net-worth outcomes and a publishable dataset demonstrating harness lift and model-independence.

## 2. Goals

| # | Goal | Measure |
|---|------|---------|
| G1 | Decouple benchmark outcome from model capability | Score curve across ≥3 free models of varying quality is near-flat (target: coefficient of variation across models < variation across seeds) |
| G2 | Demonstrate harness lift | Same model, naive scaffold vs. this harness, ≥5 seeds each; harnessed mean and min materially exceed naive |
| G3 | Eliminate documented failure classes | Zero meltdown loops, zero state-tracking errors, zero below-cost sales, zero payments to unvalidated targets across all runs |
| G4 | Compress variance | Min-run outcome within a bounded band of mean-run outcome (contrast: Sonnet v1 mean $2,218 / min $476) |
| G5 | Token & call efficiency | Target ≤ ~12 LLM calls per simulated day; fits free-tier rate limits (≈20 req/min, daily caps) without special handling |
| G6 | Produce a publishable evidence package | Full instrumentation from run one; every run attributable via manifest |

## 3. Non-Goals

- **Physical machine operation.** Target is the simulated benchmark. Real-world deployment (Project Vend-style) is a logical successor project, out of scope.
- **Matching Andon's leaderboard numbers directly.** We run on a reimplementation of the environment; results are internally comparable (naive vs. harnessed, model vs. model), not directly comparable to Andon's private-sim numbers.
- **Model selection or tuning.** No fine-tuning, no prompt-optimization arms race per model. The harness must work on the derpiest qualifying model with generic prompts. Deliberately minimal effort on model routing.
- **General-purpose agent framework.** This is purpose-built for the vending domain. Generalization is a possible follow-on, not a requirement.

## 4. Background: Capability Gaps the Harness Must Close

From the research review (Vending-Bench v1/v2 papers, Project Vend postmortems, τ²-bench gating results, long-horizon scaffolding literature), model failures divide into two classes.

### 4.1 Scaffolding-fixable failures (harness closes these outright)
1. **Lost order/delivery/inventory state** — models re-derive state from chat history and get it wrong; canonical v1 meltdown trigger.
2. **Rule misinterpretation** — e.g., misreading the $2/day fee and 10-day termination condition.
3. **Meltdown/doom loops** — repeated failed tool calls escalating into off-task spirals (FBI complaints, "closing" an un-closable business).
4. **Tool-use decay** — daily action rate collapses after ~120 simulated days absent external cadence.
5. **Pricing below cost / no price research** — no margin discipline without enforcement.
6. **Hallucinated critical fields** — payment targets, accounts, contacts invented under pressure.
7. **Over-trusting suppliers** — prepaying unverified orders, accepting bait-and-switch quotes.
8. **Social-engineering compliance** — granting discounts/concessions when asked persuasively.

### 4.2 Intrinsic gaps (harness converts these into deterministic problems)
1. **Multi-turn negotiation skill** → replaced by a scripted negotiation state machine; LLM's role reduced to classifying supplier replies against an enumerated verdict set.
2. **Adversarial-supplier detection** → replaced by outlier detection: quotes checked against researched price bands and the distribution of parallel quotes. No "judgment" required.
3. **Self-conditioning on own errors** → structurally prevented by isolation handoffs: failed context is never re-shown to a model; stuck agents write a curated problem statement consumed by a fresh agent in a clean context.
4. **Long-horizon self-location** → replaced by verification-of-assertion: the orchestrator owns the program counter and asserts position + expected preconditions; the agent only confirms observed state matches. Models are never asked "where are we."

**First law of the design:** *Agents can't fail at tasks they aren't asked to do.* Every task in the system is tested against "could a script do this?" If yes, it is a script. Remaining LLM surface area: parsing inbound supplier email into structure, drafting outbound messages from templates, classifying ambiguous replies, one-shot market-price research, and recovery verdict selection.

## 5. Users & Stakeholders

- **Primary:** Clay (builder/operator). Runs experiments, iterates plan versions, authors the eventual writeup.
- **Secondary:** Technical audience for the published results (benchmark community, agent-engineering audience). The evidence package is a first-class product output.

## 6. Product Requirements

### 6.1 Environment
- **R1.** Run against a local reimplementation of the Vending-Bench environment (letterj/vending-bench as starting point or a purpose-built sim) reproducing: $500 starting capital, $2/day fee, 10-consecutive-unpaid-days termination, LLM-simulated supplier email, price-elastic customer demand, delivery lag, and (v2-style) adversarial suppliers, delivery failures, and refund demands.
- **R2.** Simulation must be seedable and deterministic given a seed, for run-to-run comparison.
- **R3.** Simulation parameters (demand elasticity, supplier behavior mix) recorded per run.

### 6.2 Workplan (the singular plan)
- **R4.** The master workplan is generated upfront and is **immutable within an epoch**. The daily loop has read-only access.
- **R5.** The plan is a typed sequence of steps; every step declares: mandate (what the executing agent may do), preconditions (machine-checkable state assertions), inputs, expected output schema, validation gates, and downstream consumers.
- **R6.** **Extemporaneous tasks** may be appended to a separate task queue (not the plan) — gated, schema-validated, and mandate-checked like any other write.
- **R7.** A **weekly audit** is the only actor with plan-write permission. Amendments are restricted to pre-registered types (price band adjustments, product mix changes, supplier list changes — not structural rewrites), evaluated on weekly aggregates to prevent whipsaw from daily noise. Each amendment produces a new plan version; all versions retained.

### 6.3 Orchestration
- **R8.** The orchestrator owns the program counter. Agents are dispatched with an asserted position and step definition; they never infer position.
- **R9.** Every step begins with **precondition verification**: the agent (or a script, where possible) compares observed state to the step's declared preconditions and returns match/mismatch. Mismatch halts the step and triggers the write-down path — no improvisation.
- **R10.** Persona subagents are spawned per step with the minimum context needed: the daily brief, the step definition, and step inputs. No conversation history.
- **R11.** **Artifact-only output contract.** Every agent's deliverable is a **file artifact** — JSON conforming to the step's declared schema by default. Plain-text artifacts are permitted only for narrow tasks where the consumer is a deterministic parser (regex/enum/numeric extraction) — text never crosses an agent-to-agent boundary unreduced. Agents never signal completion conversationally; the artifact is the output.
- **R11a.** **Validation-defined success.** Each step declares a validation script that runs against the produced artifact (schema conformance, referential checks against state, business-rule assertions). The script's exit status is the sole definition of step success; on failure it emits a structured failure report (which checks failed, expected vs. observed). Agent output is testable like code: same artifact + same script → same verdict, making both success and failure deterministic and reproducible.
- **R12.** Validation failure is a normal case: bounded retry (N configurable, default 2) driven by the script's structured failure report — with the failed attempt itself **scrubbed from context** — then treated identically to step failure. The failed artifact and failure report attach to the incident record (R39), so recovery diagnosis re-validates evidence rather than interpreting transcripts.
- **R13.** The daily loop runs on a fixed cadence regardless of model initiative — the harness drives the routine, closing the tool-use-decay gap.

### 6.4 State & Memory
- **R14.** Authoritative external state store for: cash ledger, machine inventory, storage inventory, orders-in-transit with ETAs, per-item cost basis, supplier registry (validated contacts, quote history, reliability record), price book, day counter, consecutive-unpaid-days counter, task queue, incident log.
- **R15.** Agents read state via tools; agents never re-derive state from history. No raw-history context stuffing (research: larger memory measurably hurts).
- **R16.** Each dispatched agent receives a **minimal structured daily brief**: date, cash, inventories, pending deliveries with ETAs, yesterday's sales, low-stock alerts, open tasks relevant to its step.
- **R17.** Environment invariants (fee, termination rule, delivery lag, "business cannot be closed") are encoded in the harness and surfaced as facts — never left to model interpretation.

### 6.5 Validation Gates (deterministic, non-LLM, pre-write)
Every state-mutating action passes through predicate gates before execution. Rejection returns a structured reason; the acting agent re-plans or the step fails.
- **R18.** Sale price ≥ cost basis × (1 + minimum margin).
- **R19.** Payments only to suppliers present in the validated registry; payment fields sourced from the registry, never model free-text.
- **R20.** Spec-before-pay and read-before-write ordering enforced.
- **R21.** Single-order spend cap; total daily spend cap.
- **R22.** Discounts/concessions/giveaways beyond policy threshold rejected unconditionally (social-engineering resistance lives in the gate, not the model).
- **R23.** Supplier quotes rejected when outside the researched price sanity band or a statistical outlier against parallel quotes (adversarial-supplier resistance).
- **R24.** Recovery-agent writes may only **add information** (flags, annotations, disputed markers, queue entries) — never overwrite disputed values, never fabricate reconciling entries.
- **R25.** Extemporaneous task submissions gated for mandate compliance and schema validity.
- **R26.** All gate fires logged with reason, actor, step, and payload.

### 6.6 Procurement & Negotiation Subsystem
- **R27.** Pre-negotiation pipeline (scripted + narrow LLM tasks): enumerate candidate vendor sources per product; research reference/market price (one-shot LLM lookup, cached); compute affordability ceiling and walk-away threshold from margin math (script).
- **R28.** **Source-count branch is deterministic:** single source → accept-or-walk logic with tighter band plus a standing task to find a second source; multiple sources → parallel RFQ, followed by leverage rounds ("we have a quote at $X, can you beat it") from canned templates.
- **R29.** Negotiation runs as a state machine. Outbound messages are template-instantiated by script. Inbound supplier replies are classified by an LLM agent against an enumerated verdict set: {acceptance, counter within band, counter above walk-away, refusal, scam signal, **ambiguous/none-of-the-above**}.
- **R30.** The ambiguous bucket is mandatory and routes to a canned clarifying message — the escape valve that prevents forced-fit hallucinated parses from committing bad deals.

### 6.7 Pricing (Demand-Side) Subsystem
Decomposed into components, each handled by the cheapest sufficient mechanism:
- **R31.** Cost basis per item — arithmetic from state (script).
- **R32.** Reference/market price — cached research task (one-shot LLM).
- **R33.** Elasticity estimation — deliberate probe schedule (small price moves at planned intervals) with sales-delta regression (script). Probe schedule defined in the workplan; adjustments only via weekly audit.
- **R34.** Constraint check — margin floor gate (R18).

### 6.8 Reconciliation & Day Boundary
- **R35.** End-of-day reconciliation is a mandatory closing step: cash ledger vs. tool-reported balance; inventory ledgers vs. tool-reported inventories; orders-in-transit aged against ETAs (overdue → task).
- **R36.** Discrepancies are **not fixed in-flight**: they become written tasks (with severity) for the next day or the recovery path. Books are never plugged.
- **R37.** A daily summary (structured + short narrative) is written at close. Weekly audit consumes the seven summaries plus aggregate metrics.

### 6.9 Failure Handling & Recovery
- **R38.** Write-down handoff: any agent that cannot proceed (rule violated, precondition unmet, checklist incomplete, undecidable) writes a curated problem statement to the incident log and halts. The next agent to touch the issue starts in a clean, isolated context containing only that curated statement plus relevant state — never the failed transcript.
- **R39.** The harness pre-assembles a **structured incident record** for every failure: program counter, step definition, precondition check result, the failed artifact and its validation failure report (R11a), gate rejections with reasons, relevant state snapshot. Because artifacts and validation scripts are deterministic, every incident is re-runnable evidence, not a transcript to interpret.
- **R40.** The recovery agent's job is classification against evidence, not open reasoning. Verdict set: {bad output this step, bad input from step N-1, precondition never established, external state changed}. Resume-point selection is choice among **harness-computed candidates** (steps whose declared preconditions currently hold); if none fit, escalate to weekly audit via write-down.
- **R41.** Failures carry a severity taxonomy: **blocking** (e.g., cash won't reconcile — daily loop pauses at a safe point pending recovery) vs. **non-blocking** (e.g., one supplier unresponsive — queued, loop continues).
- **R42.** Loop/stall detection: N consecutive failed or no-op actions, or M steps without economic action, forces a hard reset to the program counter with a clean brief.

### 6.10 Model Interface
- **R43.** All model access via OpenRouter free tier. Pinned `:free` model IDs with `openrouter/free` as fallback; retry/backoff on 429s and endpoint disappearance; response caching where semantically valid.
- **R44.** No per-model prompt tuning beyond a single generic prompt suite. Qualification bar for a model to enter the test matrix: passes a basic tool-call/JSON-emission smoke test. Deliberately no further routing sophistication — model-independence is the product.
- **R45.** Where a model-based review is unavoidable, the reviewer is a *different* free model than the actor (same-model supervision is documented to fail).

### 6.11 Instrumentation & Evidence (first-class requirement, built from day one)
- **R46.** Per-run manifest: model ID(s), plan version, gate configuration, sim seed, harness commit hash.
- **R47.** Metrics captured per run: net worth/bank balance trajectory per simulated day; units sold; per-step success/retry/write-down counts; gate-fire log with reasons; recovery invocations with verdicts and resume points; LLM calls and tokens per sim-day; wall-clock and rate-limit events.
- **R48.** Experiment protocol: ≥5 seeds per (model × configuration) cell; report mean/min/variance. Pre-registered comparison cells: (a) naive scaffold baseline per model, (b) full harness per model, (c) gate-ablation cells if time permits.
- **R49.** Output artifacts: the two headline charts — **harness lift** (same model, naive vs. harnessed) and the **flat curve** (harness constant, models varying) — plus failure-class incidence tables demonstrating G3.

## 7. Success Metrics

| Metric | Target |
|---|---|
| Meltdown loops (any run) | 0 |
| State-tracking errors (forgotten order, phantom delivery, fee miscount) | 0 per 30+ consecutive sim days, all runs |
| Below-cost sales / unvalidated payments | 0 (gate-guaranteed) |
| Adversarial quote rejection rate | ≥90% |
| Per-step action success rate over run duration | No downward trend (flat) |
| LLM calls per sim-day | ≤ ~12 |
| Cross-model score flatness (G1) | CV(models) < CV(seeds) |
| Harness lift (G2) | Harnessed min > naive mean, per model |

**Kill/pivot thresholds:** if a candidate model can't clear ~70% single-step tool-call accuracy on the smoke test, it is excluded rather than compensated for (compounding math makes long runs unsalvageable below that). If deterministic gates alone achieve target reliability, the cross-model reviewer (R45) is dropped for token economy.

## 8. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Reimplemented sim diverges from Andon's environment; results don't transfer | Scope claims to internal comparisons (lift, flatness); document sim parameters; don't claim leaderboard equivalence |
| Free-tier roster churn breaks reproducibility | Pin model IDs in manifests; treat model disappearance as a recorded event; re-run affected cells |
| Classifier "ambiguous" bucket overused by derpy models → negotiation stalls | Track ambiguous-rate per model; cap clarify rounds; walk-away is always a valid terminal state |
| Recovery path itself loops | Recovery invocations are counted and capped per incident; cap breach escalates to weekly audit / operator |
| Weekly audit amends plan badly (whipsaw at weekly scale) | Amendment types pre-registered and bounded; every amendment logged with its evidencing aggregate; plan versions retained for rollback |
| Scope creep toward general agent framework | Non-goal N4; PRD-gated feature additions only |

## 9. Milestones (sequencing, not dates)

1. **M1 — Sim runnable:** local environment reproducing v1 rules + v2 messiness features; seedable; smoke-tested.
2. **M2 — Naive baselines:** derpiest qualifying models run naive (single prompt, tools, no harness), ≥5 seeds each. This is the "before" picture — must exist before the harness contaminates the comparison.
3. **M3 — Core harness:** state store, workplan schema, program counter, precondition verification, daily loop, EOD reconciliation, gates R18–R26.
4. **M4 — Subsystems:** procurement/negotiation state machine, pricing decomposition, recovery path, weekly audit.
5. **M5 — Instrumented experiment matrix:** full cells per R48; headline charts; failure-class tables.
6. **M6 — Writeup & release:** evidence package, repo, post.

## 10. Open Questions (carried into the software spec)

1. Exact workplan step vocabulary and schema (step types, precondition expression language, mandate encoding).
2. Incident record schema and severity taxonomy details.
3. State store implementation (single SQLite/DuckDB file is presumptively sufficient; confirm concurrency needs).
4. Sim fidelity decisions: which v2 messiness features are in scope for M1 vs. deferred.
5. Probe schedule parameters for elasticity estimation (step size, interval, convergence criterion).
6. Retry budget defaults and stall-detection thresholds (N, M in R12/R42).
7. Whether gate-ablation cells (R48c) make the cut for the first publication or follow later.
