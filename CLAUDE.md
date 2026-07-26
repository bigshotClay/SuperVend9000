# CLAUDE.md — Build Brief: Weak-Model Vending-Bench Harness

You are building a Kotlin/JVM system from four specification documents in `docs/spec/`. Read them in this order before writing any code; they are cumulative and later docs amend earlier ones:

1. `vending-harness-prd.md` — product requirements (R-numbers referenced everywhere)
2. `vending-harness-software-spec.md` — architecture, modules, types, testing strategy
3. `vending-harness-personas-spec.md` — persona system; **amends** software spec §9 dispatch and **supersedes** §11 qualification (>90% instruction-following battery, instructions at bottom of context)
4. `vending-harness-workplan.md` — the normative Master Workplan v1 your DSL encoding must reproduce step-for-step

## Project Thesis (context for judgment calls)

A harness that externalizes state, planning, validation, and procedure can make Vending-Bench performance approximately independent of model capability, demonstrated on the weakest free OpenRouter models. When any design question arises, resolve it toward: **more determinism, less LLM surface area, smaller agent context, everything testable without a model.** The first law: *agents can't fail at tasks they aren't asked to do.* If a script can do it, a script does it.

## Hard Constraints (do not violate; do not "improve")

1. **Stack is fixed:** Kotlin/JVM, Gradle Kotlin DSL, JDK 21, kotlinx.serialization, SQLite via sqlite-jdbc (thin DAO, no ORM), Ktor client, Kotest, Clikt. No additional runtime dependencies without an `OPERATOR-INPUT` flag (see below).
2. **Money is `Cents` (Long value class).** No Double touches any ledger, price, or quote.
3. **No nullable business fields.** Optionality = sealed alternatives.
4. **Gates and validators are pure** — no I/O, clock, or randomness. They must be re-runnable bit-identically via `harness revalidate`.
5. **Recovery writes are additive only** — enforce via the `RecoveryStateWriter` interface having no destructive methods, plus the runtime `RecoveryWriteGate`.
6. **Failed model output is never re-shown to a model.** Retry prompts = original instruction + `FailureReport` only. Enforce by prompt-builder type signature.
7. **Agent-facing JSON schemas are generated from the `@Serializable` types.** Never hand-write a schema.
8. **Context package order:** state brief → inputs → creed → output schema. Creeds ≤150 tokens, sterile ROLE/TASK/RULES/OUTPUT template. No personality, no named authors.
9. **No per-model prompt tuning.** One generic prompt suite. Model-independence is the product; tuning per model destroys the experiment.
10. **All writes flow through the single StateActor**; gate check and write apply atomically inside it.
11. **Every threshold and config is a hashed artifact in the run manifest** (gate config, persona registry, plan version, model slots, sim seed, battery hash). A run must be fully attributable from its single SQLite file.
12. **Naive baseline (`harness baseline`) shares `sim/` and `llm/` only** — it must not gain any harness capability, ever. It is the experimental control.

## Build Order & Definition of Done

Work strictly in this order (software spec §16). Do not start a stage until the prior stage's DoD is met. Commit at each DoD with the stage name.

**Stage 1 — `core/model` + `core/state`.**
DoD: all sealed hierarchies from spec §3 compile; SQLite migrations create spec §5.2 tables; ledger property tests pass (append-only, projection=fold, disputed exclusion, RecoveryStateWriter cannot mutate balances); `Cents` arithmetic property tests.

**Stage 2 — `sim/` scripted mode.**
DoD: `EnvironmentPort` implemented; all supplier behavior profiles from spec §8.1; seed-determinism test (two runs, identical DayReports); demand monotonicity-in-expectation test; sim config artifact hashed.

**Stage 3 — `core/llm` + `harness qualify` + `harness baseline`.**
DoD: OpenRouter client with backoff/fallback/caching; qualification battery (~30 cases, instructions at bottom, >90% threshold) runnable and persisted; `harness baseline --model X --seed N` completes a full sim run end-to-end and writes a complete run DB. **This is M2 — baselines must be runnable before any harness logic exists.**

**Stage 4 — `plan/` + `gates/` + `validate/` + fixtures + `personas/`.**
DoD: Workplan v1 encoded in the DSL and its JSON export reviewed against `vending-harness-workplan.md` step-for-step; every gate from PRD R18–R25 implemented with property tests; every persona registered with good/malformed/rule-violating fixtures; plan-closure CI checks pass (every roleId/gate/validator/predicate resolves; every money-mutating step gated — workplan §6 invariants as tests).

**Stage 5 — `orchestrator/` + `recovery/`.**
DoD: MockModel golden runs — full day and full week — with committed golden files for PlanCounter trace, gate log, incident log; stall detection test; severity routing test; incident replay test (`revalidate` reproduces verdicts bit-identically); scrubbed-retry type-level enforcement compiles as specified.

**Stage 6 — `procure/` + `pricing/` + `audit/`.**
DoD: negotiation FSM exhaustive transition-table test (every state × verdict defined; Ambiguous never commits; walk-away reachable everywhere; round caps enforced); elasticity regression against synthetic known-elasticity data; amendment bounds tests; plan-version chain test.

**Stage 7 — matrix + `report`.**
DoD: `harness run` completes full seeded runs on ≥2 qualifying free models; `harness report` emits the lift chart, flat curve, failure-class table, efficiency table from run DB globs; nightly E2E asserts G3 zeros and per-step success-rate flatness.

## Testing Requirements

Test-first for gates, validators, ledgers, and the negotiation FSM — write the property/fixture tests from the specs before implementations. CI must pass with **zero network access** (MockModel everywhere; only nightly E2E touches OpenRouter). Coverage philosophy per spec §13: exhaustive on the deterministic 90%, containment-focused on the LLM 10%.

## Operator Input Protocol

When you hit a genuine ambiguity, a spec conflict, or a needed dependency, do not guess and do not silently choose: implement the most conservative reading, and log the decision in `docs/OPERATOR-INPUT.md` as a numbered item with (a) the question, (b) what you did, (c) what changes if the answer differs. Known items already expected:
- Sim demand-model constants (elasticities, reference prices, noise) — propose a config, flag for review.
- Par levels and reorder points for BOOT-01 — propose from sim catalog, flag.
- Gate threshold defaults (margin floor, spend caps, MAD multiplier) — use PRD/spec defaults where stated, propose and flag the rest.

## What Success Looks Like

`harness report` over the full matrix shows: (1) harnessed min > naive mean per model (lift), (2) CV across models < CV across seeds (the flat curve — the headline result), (3) a zero column in the failure-class table for meltdowns, state-tracking errors, below-cost sales, and unvalidated payments (G3), (4) ≤12 LLM calls per sim-day. Every run reproducible from its manifest.
