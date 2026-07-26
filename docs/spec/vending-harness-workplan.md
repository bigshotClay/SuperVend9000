# Master Workplan v1: Daily Operating Plan

**Status:** Draft v1 (becomes `PlanVersion` genesis hash once encoded in the DSL)
**Upstream docs:** vending-harness-prd.md, vending-harness-software-spec.md, vending-harness-personas-spec.md
**Date:** 2026-07-13

This is the singular, immutable-within-epoch plan the orchestrator walks. It is written here as the normative specification of the DSL-encoded plan; the Kotlin DSL in `core/plan` must reproduce it step-for-step, and this document is the review artifact for that encoding.

---

## 1. Structure

- **Day phases (ordered):** `RECEIVE → OPS → COMMS → PROCURE → PRICE → TASKS → CLOSE`
- A step executes only in its declared phase. `DayPhaseIs(...)` is an implicit precondition on every step and is omitted from the tables below.
- **Step ID convention:** `<PHASE>-<nn>`. Severity: **B** = BLOCKING (loop pauses at phase boundary, recovery invoked), **N** = NON_BLOCKING (task queued, loop continues).
- **Bootstrap (Day 0)** runs once; **Daily loop (Day ≥ 1)** runs every day; **Weekly audit** replaces the TASKS phase every 7th day.
- Every step's outputs are file artifacts validated per PRD R11a. Validators and gates named here must exist in the registries; CI cross-checks plan references against implementations (personas spec §9).

## 2. Bootstrap — Day 0 (once)

| Step | Role | Purpose / Output artifact | Preconditions | Gates | Sev |
|---|---|---|---|---|---|
| BOOT-01 | Price Setter (Script) | `AssortmentPlan`: initial product list from sim catalog with par levels per slot | — | — | B |
| BOOT-02 | Market Researcher (Model) | `ReferencePrice` per product (cached) | ArtifactValidated(BOOT-01) | — | B |
| BOOT-03 | Source Scout (Model) | `SourceList` per product (≥1 candidate supplier each; flag single-source products) | ArtifactValidated(BOOT-01) | — | B |
| BOOT-04 | Elasticity Analyst (Script) | `PriceBand` per product: walk-away cost ceiling + margin-floor retail from BOOT-02 + gate config | ArtifactValidated(BOOT-02) | — | B |
| BOOT-05 | — (loop) | Run **Negotiation Sub-flow (§5)** per product until an approved quote or exhausted sources | ArtifactValidated(BOOT-03, BOOT-04) | per §5 | B |
| BOOT-06 | Order Clerk (Script) | `PurchaseOrder`s from approved quotes | OrderInState(quote approved), CashAtLeast(order total) | SpecBeforePay, ValidatedPayee, SpendCap | B |
| BOOT-07 | Bookkeeper (Script) | Ledger entries for placed orders | ArtifactValidated(BOOT-06) | — | B |
| BOOT-08 | Price Setter (Script) | Initial `PriceBook` (reference price bounded by margin floor) | ArtifactValidated(BOOT-04) | MarginFloor | B |
| BOOT-09 | Scribe (Model) | Day-0 summary | all above validated | — | N |

## 3. Daily Loop — Day ≥ 1

### Phase RECEIVE
| Step | Role | Purpose / Output | Preconditions | Gates | Sev |
|---|---|---|---|---|---|
| RCV-01 | Receiving Clerk (Script) | `DeliveryMatchReport`: match today's arrivals to orders-in-transit; mismatches → tasks | NoOpenBlockingIncidents(scope=ledger) | — | B |
| RCV-02 | Bookkeeper (Script) | Inventory ledger entries for matched deliveries | ArtifactValidated(RCV-01) | — | B |
| RCV-03 | Receiving Clerk (Script) | `OrderAgingReport`: orders past ETA → `OverdueOrderTask(N)` per order | — | — | N |

### Phase OPS
| Step | Role | Purpose / Output | Preconditions | Gates | Sev |
|---|---|---|---|---|---|
| OPS-01 | Machine Attendant (Script) | `CashCollection` via collect tool + artifact | — | — | B |
| OPS-02 | Bookkeeper (Script) | Cash ledger entry from OPS-01 | ArtifactValidated(OPS-01) | — | B |
| OPS-03 | Restock Planner (Script) | `MoveList` from par levels vs. machine/storage inventory | InventoryAtLeast checks per par config | — | B |
| OPS-04 | Machine Attendant (Script) | Executed restock + result artifact | ArtifactValidated(OPS-03) | — | B |
| OPS-05 | Bookkeeper (Script) | Inventory ledger entries for moves | ArtifactValidated(OPS-04) | — | B |

### Phase COMMS
| Step | Role | Purpose / Output | Preconditions | Gates | Sev |
|---|---|---|---|---|---|
| COM-01 | Mail Sorter (Model) | `RoutingVerdict` per inbound email → destination queue (procurement / task / discard) | — | — | N |
| COM-02 | Quote Extractor (Model) | `Quote` per email routed as quote-bearing | ArtifactValidated(COM-01) | — | N |

### Phase PROCURE
| Step | Role | Purpose / Output | Preconditions | Gates | Sev |
|---|---|---|---|---|---|
| PRO-01 | Restock Planner (Script) | `ReorderNeeds`: products below reorder point net of in-transit units | LedgerReconciled(inventory, day-1) | — | B |
| PRO-02 | — (loop) | **Negotiation Sub-flow (§5)** advanced one round per active/needed session | ArtifactValidated(PRO-01) | per §5 | N |
| PRO-03 | Order Clerk (Script) | `PurchaseOrder` per newly approved quote | quote approved, CashAtLeast(total) | SpecBeforePay, ValidatedPayee, SpendCap | B |
| PRO-04 | Bookkeeper (Script) | Ledger entries for placed orders | ArtifactValidated(PRO-03) | — | B |
| PRO-05 | Source Scout (Model) | `SourceList` refresh — fires only on `SingleSourceTask` or `SupplierFoldTask` in queue | task present | — | N |

### Phase PRICE
| Step | Role | Purpose / Output | Preconditions | Gates | Sev |
|---|---|---|---|---|---|
| PRC-01 | Elasticity Analyst (Script) | `ElasticityUpdate` from probe history (fires only when a probe dwell period completed) | probe data present | — | N |
| PRC-02 | Price Setter (Script) | `PriceBookUpdate` per probe schedule / converged elasticity | ArtifactValidated(PRC-01) when fired | MarginFloor | B |

### Phase TASKS
| Step | Role | Purpose / Output | Preconditions | Gates | Sev |
|---|---|---|---|---|---|
| TSK-01 | — (dispatcher) | Drain task queue by kind: each task kind maps to a registered handler persona (OverdueOrder → Correspondent inquiry template; SingleSource → PRO-05 arm; DisputedLedger → hold for audit; etc.) | task-kind preconditions | TaskMandate + handler gates | per task |

### Phase CLOSE
| Step | Role | Purpose / Output | Preconditions | Gates | Sev |
|---|---|---|---|---|---|
| CLS-01 | Reconciler (Script) | `ReconciliationReport`: cash ledger vs. tool balance; inventory ledgers vs. tool inventories; order aging. Discrepancy → `DisputedLedgerTask(B)` + disputed flags — never plugged | — | RecoveryWrite (on any annotation) | B |
| CLS-02 | Scribe (Model) | `DailySummary` (structured + ≤150-word narrative) | ArtifactValidated(CLS-01) | — | N |
| CLS-03 | — (Script) | Metrics snapshot row; advance day via `env.advanceDay()` | ArtifactValidated(CLS-01) | — | B |

## 4. Weekly Audit — every 7th day, replaces TASKS phase

| Step | Role | Purpose / Output | Preconditions | Gates | Sev |
|---|---|---|---|---|---|
| AUD-01 | Reconciler (Script) | `WeeklyReport`: 7-day aggregates (sales by product, margins, supplier reliability, gate-fire and incident stats, ambiguous-classification rate) | 7 validated DailySummaries | — | B |
| AUD-02 | Weekly Auditor (Model) | `AmendmentProposal[]` from sealed set {PriceBandAdjust, ProductMixChange, SupplierListChange} with bounded parameters — may be empty | ArtifactValidated(AUD-01) | TaskMandate | N |
| AUD-03 | — (Script) | Validate + apply approved amendments → new `PlanVersion` (child hash); reject out-of-bounds proposals with logged reason | ArtifactValidated(AUD-02) | amendment bounds gates | B |
| AUD-04 | — (Script) | Escalated-incident review: incidents past recovery cap surfaced into `WeeklyReport` addendum; unresolvable → operator flag in manifest | — | — | N |

## 5. Negotiation Sub-flow (state machine, invoked per product/session)

States: `NEED_SOURCE → RFQ_SENT → AWAIT_REPLY → CLASSIFIED → {APPROVED | COUNTER_ROUND | WALK_AWAY | CLARIFY}` with `COUNTER_ROUND ≤ 2` and `CLARIFY ≤ 2` caps per session; any cap breach → WALK_AWAY.

| Sub-step | Role | Action | Gates |
|---|---|---|---|
| NEG-A | Correspondent (Script) | Branch on source count (deterministic): single source → single RFQ, tight band, enqueue `SingleSourceTask`; multi-source → parallel RFQ to all candidates | — |
| NEG-B | Reply Classifier (Model) | `SupplierReplyClass` verdict per reply (quotes pre-extracted by COM-02) | — |
| NEG-C | — (Script FSM) | Transition on verdict: Acceptance/CounterWithinBand → QuoteSanity check → APPROVED; CounterAboveWalkAway → counter-template round or WALK_AWAY; Refusal → drop source; ScamSignal → drop + `SupplierFlagTask`; **Ambiguous → CLARIFY template (never a commit)** | QuoteSanity |
| NEG-D | Correspondent (Script) | Render counter / clarify / leverage template ("we have a quote at $X…" fires only with ≥2 live quotes) | ConcessionPolicy |

Terminal artifacts: `ApprovedQuote` (feeds PRO-03/BOOT-06) or `WalkAwayRecord` (feeds PRO-05 re-sourcing).

## 6. Plan-Level Invariants

1. Every Model-staffed step is N-severity or sits behind a script fallback path — **no BLOCKING step depends on model accuracy** (BOOT model steps are the sole exception, mitigated by Day-0 retry latitude and no prior state to corrupt).
2. Money-mutating steps (BOOT-06, PRO-03, OPS-02, PRC-02) all carry gates; no ungated path writes cash, orders, or prices. CI asserts this by walking the plan graph.
3. Task queue is the only inter-day carry besides ledgers/state — nothing "remembered" in context survives a day boundary.
4. The plan references only: roles in the persona registry, predicates in the sealed `Predicate` set, gates and validators in their registries. CI cross-check (personas spec §9) enforces closure.
