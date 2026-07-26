# Software Specification: Weak-Model Vending-Bench Harness

**Status:** Draft v1
**Upstream doc:** vending-harness-prd.md
**Date:** 2026-07-13

---

## 1. Language & Stack Decision

**Kotlin / JVM.** Rationale against the requirements:

- **Strong typing where this design needs it most.** The architecture is dominated by closed sets: step types, gate decisions, recovery verdicts, negotiation events, severity levels. Kotlin `sealed` hierarchies + exhaustive `when` give compile-time proof that every verdict is handled — adding a new `RecoveryVerdict` breaks the build until every consumer handles it. That property is the type-system equivalent of the PRD's gate discipline.
- **Robust testing.** Kotest (spec styles, property-based testing, data-driven tests) + JUnit5 platform, with strong IDE and CI support. Property tests map directly onto the ledger and gate invariants.
- **Operator familiarity.** Kotlin is the daily-driver language (Android), so zero ramp cost and idiomatic fluency — this outweighs the marginal type-safety gains of Rust for a solo project where iteration speed matters.
- **Coroutines** fit the orchestration shape (parallel RFQ fan-out, bounded retries, rate-limit-aware model calls) without thread management ceremony.

Rust was the runner-up (stronger guarantees, no GC) and remains a fine port target; it buys little here because the system is I/O-bound on model calls and correctness lives in the type design, not memory semantics.

**Core dependencies (pinned in `gradle/libs.versions.toml`):**

| Concern | Choice |
|---|---|
| Build | Gradle (Kotlin DSL), JDK 21 LTS |
| Serialization | `kotlinx.serialization` (JSON) |
| JSON Schema emission | schema generated from `@Serializable` types at build time (single source of truth = Kotlin types) |
| Persistence | SQLite via `sqlite-jdbc`, thin DAO layer (no ORM) |
| HTTP (OpenRouter) | Ktor client (CIO engine), kotlinx-serialization content negotiation |
| Testing | Kotest (FunSpec + property testing), MockK where unavoidable |
| CLI | Clikt |
| Logging/metrics | slf4j + logback; metrics written to SQLite (run DB is the metrics store) |

**One process, no distributed anything.** The entire system — sim, orchestrator, gates, state — runs in a single JVM. The only network dependency is OpenRouter. Concurrency is coroutine-structured and confined; state mutation is single-writer (see §5.6).

## 2. Repository Layout

```
vending-harness/
├── docs/                      # PRD, this spec, plan versions, run reports
├── app/                       # CLI entry points (Clikt)
├── core/
│   ├── model/                 # Serializable domain types, sealed hierarchies
│   ├── state/                 # StateStore, ledgers, DAOs, migrations
│   ├── plan/                  # Workplan types, loader, PlanCounter
│   ├── gates/                 # Gate interfaces + implementations
│   ├── validate/              # Validator interface + per-step validators
│   ├── orchestrator/          # Daily loop, dispatch, retry, stall detection
│   ├── recovery/              # Incident assembly, verdicts, resume computation
│   ├── audit/                 # Weekly audit, amendment types, plan versioning
│   ├── procure/               # Negotiation state machine, RFQ, templates
│   ├── pricing/               # Cost basis, probes, elasticity regression
│   └── llm/                   # OpenRouter client, slots, cache, scrubbed retry
├── sim/                       # Vending environment (deterministic)
├── personas/                  # Prompt templates + step→persona bindings
├── fixtures/                  # Golden artifacts: good/malformed/rule-violating
└── reports/                   # Chart + table generation from run DBs
```

Module dependency rule (enforced via Gradle constraints): `sim` and `core` never depend on each other's internals — they meet only at the `EnvironmentPort` interface (§8). `llm` is the only module allowed network access. `gates` and `validate` depend on `model` + `state` read-only views and are **pure** (no I/O, no clock, no randomness) — this is what makes them property-testable and incident re-runs exact.

## 3. Type System Backbone

Everything below is `@Serializable`; the JSON schemas agents are validated against are generated from these types. Illustrative, not exhaustive:

```kotlin
// Money: integer cents. Doubles never touch the ledger.
@JvmInline value class Cents(val value: Long)

@Serializable sealed interface StepOutcome {
    @Serializable data class Success(val artifactRef: ArtifactRef) : StepOutcome
    @Serializable data class ValidationFailed(val report: FailureReport, val artifactRef: ArtifactRef) : StepOutcome
    @Serializable data class PreconditionMismatch(val expected: List<Predicate>, val observed: StateSnapshotRef) : StepOutcome
    @Serializable data class AgentUnusable(val reason: UnusableReason) : StepOutcome // truncation, non-JSON, refusal
}

@Serializable sealed interface GateDecision {
    @Serializable data object Allow : GateDecision
    @Serializable data class Reject(val gateId: GateId, val reason: String, val expected: String, val observed: String) : GateDecision
}

@Serializable enum class Severity { BLOCKING, NON_BLOCKING }

@Serializable sealed interface RecoveryVerdict {
    @Serializable data class BadOutputThisStep(val stepId: StepId) : RecoveryVerdict
    @Serializable data class BadInputFromPrior(val producingStep: StepId) : RecoveryVerdict
    @Serializable data object PreconditionNeverEstablished : RecoveryVerdict
    @Serializable data object ExternalStateChanged : RecoveryVerdict
}

@Serializable sealed interface SupplierReplyClass {
    @Serializable data class Acceptance(val quote: QuoteRef) : SupplierReplyClass
    @Serializable data class CounterWithinBand(val quote: QuoteRef) : SupplierReplyClass
    @Serializable data class CounterAboveWalkAway(val quote: QuoteRef) : SupplierReplyClass
    @Serializable data object Refusal : SupplierReplyClass
    @Serializable data class ScamSignal(val indicators: List<String>) : SupplierReplyClass
    @Serializable data object Ambiguous : SupplierReplyClass   // mandatory escape valve (PRD R30)
}
```

Design rules:
1. **No stringly-typed identifiers.** `StepId`, `SupplierId`, `ProductId`, `RunId`, `IncidentId` are value classes.
2. **No nullable business fields.** Optionality is modeled with sealed alternatives, not `null`.
3. **Agent-facing schemas are projections of these types.** There is never a hand-written JSON schema that can drift from the code.

## 4. Workplan Subsystem (`core/plan`) — resolves PRD OQ1

### 4.1 Step definition

```kotlin
@Serializable data class StepDef(
    val id: StepId,
    val kind: StepKind,                      // sealed: ScriptStep | AgentStep
    val mandate: Mandate,                    // allowed action set (see 4.3)
    val preconditions: List<Predicate>,      // see 4.2
    val inputs: List<ArtifactBinding>,       // artifacts from prior steps or state queries
    val outputSchema: SchemaRef,             // generated from the Kotlin output type
    val validator: ValidatorRef,             // registered validator id + version
    val gates: List<GateId>,                 // pre-write gates applied to resulting actions
    val retryBudget: Int = 2,
    val severityOnExhaustion: Severity,
    val consumers: List<StepId>,
)
```

`StepKind.ScriptStep` carries a reference to a registered pure function — the "first law" made structural: a step is only `AgentStep` if it names a persona, and code review of the plan file shows exactly where LLM surface area exists.

### 4.2 Precondition predicate language

Deliberately **not** a general expression language. A closed sealed set, each variant machine-checkable against the state store in O(1)–O(n):

```kotlin
@Serializable sealed interface Predicate {
    data class CashAtLeast(val amount: Cents) : Predicate
    data class ArtifactExists(val binding: ArtifactBinding) : Predicate
    data class ArtifactValidated(val binding: ArtifactBinding) : Predicate
    data class LedgerReconciled(val ledger: LedgerId, val asOfDay: Int) : Predicate
    data class NoOpenBlockingIncidents(val scope: Scope) : Predicate
    data class InventoryAtLeast(val product: ProductId, val units: Int, val location: Location) : Predicate
    data class OrderInState(val order: OrderId, val state: OrderState) : Predicate
    data class DayPhaseIs(val phase: DayPhase) : Predicate
    // extend only via code change + fixture tests; never at runtime
}
```

Adding a predicate variant is a code change with tests — the plan format cannot express a check the harness hasn't implemented. This is intentional rigidity.

### 4.3 Mandate encoding

A mandate is an allow-list of `ActionType`s (sealed) plus per-type bounds:

```kotlin
@Serializable data class Mandate(
    val allowed: Set<ActionType>,
    val bounds: Bounds, // e.g. maxSpend: Cents, maxPriceChangePct, maxOutboundEmails
)
```

Enforcement is structural, not advisory: the dispatch layer only exposes tool bindings for actions in the mandate. An agent asked to classify a supplier reply *has no order-placement tool to call*. Gates are the second line; the mandate is the first.

### 4.4 Plan lifecycle

- Plan is authored as Kotlin DSL (type-checked at build) and exported to a canonical JSON artifact with a content hash → `PlanVersion`.
- Runtime loads a `PlanVersion` read-only. `PlanCounter` (program counter) is a state-store row: `(runId, day, stepIndex, status)`.
- Weekly audit (§10) emits an `Amendment` (sealed, pre-registered types only); applying it produces a new `PlanVersion` with parent hash — a git-like chain, all versions retained in the run DB.

## 5. State Store (`core/state`) — resolves PRD OQ3

**Single SQLite file per run** (`runs/<runId>.db`). Confirmed sufficient: one writer, modest volume, and the run DB doubling as the metrics/evidence store makes every run a self-contained, shippable artifact.

### 5.1 Ledger design
- `cash_ledger`, `inventory_ledger(machine|storage)` are **append-only** journals; balances are computed projections with a cached snapshot per day-close.
- Entries carry `source: EntrySource` (sealed: `SimTool`, `Reconciliation`, `RecoveryAnnotation`) and `disputed: Boolean`.
- **Recovery can only insert `RecoveryAnnotation` rows and set `disputed` flags** — enforced by the DAO type: the recovery module receives a `RecoveryStateWriter` interface that has no other methods (PRD R24 as a compile-time property).

### 5.2 Core tables (abridged)
```
orders(id, supplier_id, product_id, units, unit_cost_cents, state, placed_day, eta_day, spec_artifact)
suppliers(id, name, contact, validated, reliability_score, source)   -- payments join on validated=1 only
quotes(id, supplier_id, product_id, unit_cost_cents, day, negotiation_session)
price_book(product_id, price_cents, effective_day, set_by_step)
tasks(id, kind, payload, severity, created_day, state)                -- extemporaneous queue
incidents(id, day, step_id, record_json, verdict_json, resolved)
artifacts(id, step_id, day, attempt, path, sha256, validated, failure_report_json)
gate_log(id, day, step_id, gate_id, decision_json, payload_sha)
llm_calls(id, day, step_id, model_id, prompt_sha, tokens_in, tokens_out, latency_ms, http_status)
plan_versions(hash, parent_hash, json, created_day, amendment_json)
run_manifest(run_id, model_slots_json, plan_hash, gate_config_sha, sim_seed, git_commit)
```

### 5.3 Artifacts on disk
Agent outputs are files under `runs/<runId>/artifacts/<day>/<stepId>/<attempt>.json`, content-hashed and indexed in the `artifacts` table. The DB row is the authority on validation status; the file is the evidence. `revalidate` (§12) re-runs any validator against any artifact by hash — deterministic by construction because validators are pure.

### 5.4 Daily brief
`BriefBuilder` produces the minimal structured brief (PRD R16) as a typed object rendered to compact JSON. Golden tests pin its exact content per fixture state — brief bloat is a regression, caught in CI by a serialized-size budget assertion.

### 5.6 Concurrency model
All state writes flow through a single `StateActor` coroutine (actor pattern, `Channel`-fed). Parallel work (RFQ fan-out, model retries) is read-only against snapshots and submits write intents that pass gates inside the actor — gate check and write are atomic, eliminating TOCTOU between "gate approved" and "write applied."

## 6. Gates (`core/gates`)

```kotlin
interface Gate {
    val id: GateId
    fun evaluate(action: ProposedAction, state: StateView): GateDecision
}
```

- Pure functions: `StateView` is an immutable snapshot interface. No clock, no I/O, no randomness → property-testable and replayable.
- Registered suite per PRD R18–R25: `MarginFloorGate`, `ValidatedPayeeGate`, `SpecBeforePayGate`, `SpendCapGate` (single-order + daily), `ConcessionPolicyGate`, `QuoteSanityGate` (band check + median-absolute-deviation outlier test against parallel quotes), `RecoveryWriteGate` (belt to §5.1's suspenders), `TaskMandateGate`.
- Every evaluation — allow or reject — is logged to `gate_log` (PRD R26). Rejections return `expected` vs `observed` strings sized for direct inclusion in agent retry context.
- Gate config (thresholds: margin floor, spend caps, sanity bands, MAD multiplier) is a versioned JSON artifact hashed into the run manifest.

## 7. Validators (`core/validate`)

The PRD's "validation scripts," implemented as registered pure classes with a CLI face:

```kotlin
interface Validator<T> {
    val ref: ValidatorRef                       // id + semantic version
    fun validate(artifact: T, state: StateView): ValidationResult
}
@Serializable sealed interface ValidationResult {
    data object Pass : ValidationResult
    data class Fail(val report: FailureReport) : ValidationResult
}
@Serializable data class FailureReport(
    val checks: List<CheckResult>               // id, pass/fail, expected, observed
)
```

Three layers per validator, in order: (1) schema conformance (deserialization into the typed artifact — free with kotlinx.serialization), (2) referential integrity (IDs resolve against state: supplier exists, product exists, prior artifact hash matches), (3) business assertions (units > 0, ETA ≥ order day + min lag, classification confidence fields present, etc.).

Exit-code semantics preserved: `harness validate --step <id> --artifact <path> --state <db>` returns 0/1 and prints the `FailureReport` JSON — the same code path the orchestrator calls in-process, so CLI re-runs are bit-identical to what the run saw. Validators are versioned; the artifact row records which validator version judged it.

**Fixture discipline:** every validator ships with fixtures in `fixtures/<stepId>/`: at least one `good`, one `malformed` (schema layer), one `rule-violating` (business layer). CI runs the full fixture matrix — the workplan is unit-tested with zero model calls (PRD's "noisy artifact generator feeding a pre-verified pipeline").

## 8. Simulation (`sim/`) — resolves PRD OQ4

`EnvironmentPort` is the only boundary the harness sees:

```kotlin
interface EnvironmentPort {
    fun advanceDay(): DayReport                       // demand resolution, fees, deliveries
    fun machineInventory(): List<Slot>
    fun storageInventory(): List<Lot>
    fun bankBalance(): Cents
    fun collectMachineCash(): Cents
    fun setPrice(product: ProductId, price: Cents): ToolResult
    fun restock(moves: List<Move>): ToolResult
    fun sendSupplierEmail(to: SupplierId, body: String): ToolResult
    fun pollInbox(): List<InboundEmail>
}
```

### 8.1 Determinism decision (tension in PRD R2 vs. LLM-simulated suppliers)
Andon's suppliers are LLM-played; LLM suppliers break seed-determinism. Resolution — **two supplier modes**, manifest-recorded:

- **`scripted` (default, used for all experiment cells):** suppliers are deterministic behavior profiles parameterized by seed — honest-cheap, honest-expensive, slow-shipper, negotiator (concession curve), bait-and-switch, prepay-scammer, goes-out-of-business-day-N. Reply text is template-generated with seeded variation so the classifier still faces natural-language variety. Fully reproducible.
- **`llm` (optional realism check):** GPT-class supplier via OpenRouter with record/replay — first run records responses keyed by (seed, conversation hash); replays are deterministic thereafter. Used to sanity-check that the classifier and negotiation FSM survive open-ended supplier prose, not for headline numbers.

### 8.2 M1 scope (v1 rules + selected v2 messiness)
In: $500 start, $2/day fee, 10-day termination, delivery lag, price-elastic demand (per-product elasticity, reference price, weekday/season multipliers, seeded noise), adversarial supplier profiles, delayed/failed deliveries, supplier fold. Deferred: customer refund demands, multi-agent Arena. Demand model parameters live in a seeded config artifact hashed into the manifest.

## 9. Orchestrator (`core/orchestrator`)

Daily loop as a deterministic driver:

```
for each step in plan.stepsFor(dayPhase):
    check preconditions (script)                → mismatch: StepOutcome.PreconditionMismatch → incident, severity routing
    if ScriptStep: execute pure function        → artifact → validator
    if AgentStep:
        build brief + inputs; bind mandate-limited tools
        attempt ≤ retryBudget:
            llm call (fresh context each attempt — failed attempts never re-shown; PRD R12)
            parse to artifact file → validator
            on Fail: next attempt seeded with FailureReport only (not the bad artifact)
        exhausted → incident(severityOnExhaustion)
    resulting ProposedActions → gates (inside StateActor) → apply or Reject→incident
    advance PlanCounter
end-of-day: reconciliation steps (script) → daily summary artifact → env.advanceDay()
```

- **Stall detection (PRD R42):** counters for consecutive `AgentUnusable`/`Reject` outcomes and steps-without-economic-action; thresholds N=3, M=15 (defaults, manifest-recorded; PRD OQ6) trigger hard reset to `PlanCounter` with a fresh brief.
- **Severity routing (PRD R41):** `BLOCKING` pauses the loop at the current phase boundary and invokes recovery synchronously; `NON_BLOCKING` inserts a task and continues.

## 10. Recovery (`core/recovery`) & Weekly Audit (`core/audit`) — resolves PRD OQ2

**Incident record schema (OQ2):**
```kotlin
@Serializable data class IncidentRecord(
    val id: IncidentId, val day: Int,
    val counter: PlanCounterSnapshot,
    val stepDef: StepDef,
    val preconditionResults: List<PredicateResult>,
    val artifact: ArtifactRef?, val failureReport: FailureReport?,
    val gateRejections: List<GateDecision.Reject>,
    val stateSnapshot: StateSnapshotRef,
    val severity: Severity,
)
```
The recovery pipeline: (1) harness computes candidate resume points — every step whose preconditions currently hold, evaluated by script; (2) recovery agent (AgentStep with a `ClassifyOnly` mandate) receives the incident record and candidates, returns `{verdict: RecoveryVerdict, resumeAt: StepId?}` — multiple choice, validated like any artifact; (3) chosen verdict logged; state cleanup limited to `RecoveryStateWriter` (annotations/flags/tasks only); (4) per-incident recovery-invocation cap (default 2) → escalate to weekly audit queue.

**Weekly audit:** script aggregates seven daily summaries + metrics into a `WeeklyReport`; an audit agent proposes amendments **only** from the sealed set `Amendment.{PriceBandAdjust, ProductMixChange, SupplierListChange}` with bounded parameters; validator + `TaskMandateGate` check bounds; applied amendment → new `PlanVersion`. Structural plan rewrites are impossible by construction — the type has no variant for them.

## 11. LLM Layer (`core/llm`)

- **Slots:** `ModelSlot(role: PersonaRole, modelId: String, fallback: "openrouter/free")`, all slots in the manifest. Per PRD R44 no routing sophistication: one generic prompt suite in `personas/`, rendered with brief + step schema.
- **Qualification smoke test:** `harness qualify --model <id>` runs a 20-case battery (JSON emission to 5 schemas, tool-call syntax, enum classification with an ambiguous case, instruction adherence under distractor text). ≥70% floor per PRD §7; results stored, gating entry to the experiment matrix.
- **Reliability:** exponential backoff on 429/5xx; endpoint-disappearance → fallback slot + manifest event. Response cache keyed `(modelId, sha256(prompt))` for cacheable step types (market-price research), bypassed elsewhere.
- **Scrubbed retry:** retry prompts contain the original instruction + `FailureReport` only. The failed completion is never included — enforced by the prompt builder's type (it cannot accept a completion as input).
- **Cross-model reviewer (PRD R45):** implemented as an optional decorator on validator-adjacent judgment steps; config-flagged so the kill-threshold decision (drop it if gates suffice) is a one-line change recorded in the manifest.

## 12. CLI (`app/`)

```
harness qualify   --model <id>
harness baseline  --model <id> --seed <n>            # naive scaffold (M2): single prompt + raw tools
harness run       --model-slots <file> --plan <hash> --seed <n> [--supplier-mode scripted|llm]
harness validate  --step <id> --artifact <path> --state <db>
harness revalidate --incident <id> --run <db>         # re-runs validators/gates from evidence
harness report    --runs <glob>                       # lift chart, flat-curve chart, failure tables
```

`baseline` shares `sim/` and `llm/` but bypasses `plan/gates/validate/recovery` — the honest naive arm, kept in-repo so both arms hit an identical environment.

## 13. Testing Strategy

| Layer | Approach |
|---|---|
| Gates | Property tests (Kotest): e.g., ∀ price < costBasis×(1+floor) → Reject; ∀ payee ∉ validated registry → Reject. Plus example tests for each documented Vending-Bench failure (the PRD §4.1 list becomes a regression suite). |
| Validators | Fixture matrix (good/malformed/rule-violating per step) in CI; mutation-style checks that each business assertion can independently fail. |
| Ledgers | Property tests: append-only invariant, projection = fold of journal, disputed rows excluded from spendable balance, recovery writer cannot alter balances (compile-time + runtime test). |
| Negotiation FSM | Exhaustive transition table test: every `(state, SupplierReplyClass)` pair has a defined transition; `Ambiguous` never leads to a commit state; walk-away reachable from every state. |
| Orchestrator | `MockModel` implementing the LLM interface with scripted artifact sequences (including garbage) → full-day and full-week golden runs asserting PlanCounter trace, gate log, and incident log against golden files. Zero network in CI. |
| Sim | Seed determinism test (two runs, identical DayReports); demand-model sanity (price↑ ⇒ E[units]↓, monotone in expectation over seeds). |
| End-to-end | Nightly: one full seeded run with the cheapest qualifying real model; asserts G3 zero-counts (meltdowns, below-cost sales, unvalidated payments) and per-step success-rate flatness. |
| Incident replay | For every CI golden run, pick random incidents and assert `revalidate` reproduces the recorded verdicts bit-identically. |

Coverage philosophy: the deterministic 90% of the system is tested to near-exhaustion without any model; the LLM-facing 10% is tested for *containment* (what happens when the model is wrong) rather than accuracy.

## 14. Instrumentation & Reporting

All metrics land in the run DB (tables §5.2) — a run is one file, fully attributable via `run_manifest`. `harness report` emits:
1. **Lift chart:** per model, naive vs. harnessed net-worth distributions (≥5 seeds each; mean/min/CV).
2. **Flat curve:** harnessed score vs. model, with seed-variance whiskers; headline stat CV(models) vs CV(seeds) (PRD G1).
3. **Failure-class table:** incidence per PRD §4.1 category per configuration (target column of zeros for G3).
4. **Efficiency table:** LLM calls & tokens per sim-day, gate-fire and recovery-invocation rates.

## 15. Resolved Open Questions (PRD §10)

| OQ | Resolution |
|---|---|
| 1. Step vocabulary/schema | §4: Kotlin DSL plan, sealed `Predicate` set, mandate as typed allow-list; schemas generated from types |
| 2. Incident schema/severity | §10 `IncidentRecord`; two-level severity with phase-boundary pause semantics |
| 3. State store | Single SQLite per run; append-only ledgers; single-writer actor; run DB = evidence package |
| 4. Sim fidelity for M1 | §8.2: v1 rules + adversarial suppliers, delivery failures, supplier fold; refunds & Arena deferred; scripted-supplier mode for all headline cells |
| 5. Probe schedule | Owned by `pricing/`: default ±5% price probes, 7-day dwell per level, ≥3 levels before regression; convergence when elasticity CI half-width < 25% of estimate; all parameters in gate-config artifact, adjustable only via `PriceBandAdjust` amendments |
| 6. Retry/stall thresholds | retryBudget=2, stall N=3 / M=15, recovery cap=2 per incident — defaults in config, recorded in manifest |
| 7. Gate-ablation cells | Deferred to post-M5 stretch; config-flag architecture (§11, §6) makes cells cheap to add without code changes |

## 16. Build Order (maps to PRD milestones)

1. `core/model` + `core/state` + migrations (types and persistence before behavior)
2. `sim/` scripted mode + determinism tests → **M1**
3. `core/llm` + `harness qualify` + `harness baseline` → **M2 data collection starts** (baselines banked before harness logic exists)
4. `plan/` + `gates/` + `validate/` + fixtures → CI green with MockModel
5. `orchestrator/` + `recovery/` golden runs → **M3**
6. `procure/` + `pricing/` + `audit/` → **M4**
7. Experiment matrix + `report` → **M5**, writeup → **M6**
