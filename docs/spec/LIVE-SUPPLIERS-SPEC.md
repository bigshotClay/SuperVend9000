# Live Agent Suppliers — Design Spec (task #14)

Replace the scripted `SupplierProfile` reply templates with **live LLM-driven vendors** — model-vs-model
procurement. Kills the "you hand-tuned your adversary" critique (and matches real Vending-Bench, whose
suppliers are GPT-4o-simulated). Reuses the existing creed / `ContextPackage` / negotiation-FSM
machinery; only the *reply generation* becomes a model call.

## 1. Hardware topology (2× RTX 3090)

| GPU | role | serves |
|-----|------|--------|
| **GPU 0 — buyers** | 5 Ollama servers, `gemma3:4b` | the vending-machine harness agents (5 concurrent seeds) |
| **GPU 1 — vendors** | 2 Ollama servers, capable model(s) | all supplier replies for those 5 buyers |

Buyer concurrency drops 10→**5** (one card is now the vendor brain). Vendor replies are short
(a price + a sentence), so 2 vendor instances should keep up with 5 buyers' bursty RFQ traffic —
**throughput is the #1 risk to validate** (see §7).

## 2. Vendor model — qwen (DECIDED)

**Vendors = `qwen2.5:14b-instruct`, buyers = `gemma3:4b`.** Diversity is *cross-family* — a capable qwen
vendor negotiating against the gemma buyer maximizes the model gap and removes any same-family artifact.
Run **2 instances on GPU1** (both qwen) purely for throughput; vendor-to-vendor behavioral diversity
comes from **personas** (§5), not from mixing model families.

~9 GB Q4, ~30 tok/s; 2 instances ~18 GB, fits one 24 GB card. A terse reply ≈ 1.5–2 s. (Load-test on
the box first — VRAM + tok/s — and consider a `qwen2.5:7b` second instance if 14b×2 is throughput-tight.)

## 3. Integration — where the model call goes

- New `LiveSupplierEngine` implementing the same surface as `SupplierEngine.reply(...)`, selected by a
  sim-config flag (`suppliers = live`). Scripted profiles stay as the cheap deterministic control.
- The sim (`ScriptedEnvironment`) already routes a buyer RFQ/email to `SupplierEngine.reply`; that call
  now builds a vendor `ContextPackage`, hits the **vendor** base URL, and returns a `Reply` (quote /
  counter / accept / decline). Everything downstream — the buyer's model classifier, `NegotiationFsm`,
  the QuoteSanity/ValidatedPayee/SpendCap gates — is unchanged. Full model-vs-model loop.
- **Determinism:** the sim stops being bit-reproducible on the vendor axis. Mitigate: (a) seed still
  assigns each vendor its *persona* (greed, floor multiplier, cooperativeness) and drives demand /
  delivery / harshness deterministically; (b) vendor calls use `temperature≈0.3` + Ollama `seed` for
  near-reproducibility; (c) aggregate over seeds — stochastic vendors are *more* realistic, not less.

## 4. Vendor prompt system (reuses creed + ContextPackage)

Package order stays: **state brief → inputs → creed → output schema.**

**Context management (the state brief the vendor sees each turn):**
- who it is + its **unit cost** (its hard floor) and **target margin**
- this buyer's **relationship**: orders placed, recent delivery/payment history, current **dependence**
  (low/med/high — the buyer's-market lever)
- **negotiation round** + the buyer's offers so far this session
- its own **inventory pressure** (a reason to want the sale)

**Progressive disclosure (the negotiation instruction):** open near target (anchor high), and each round
the buyer counters, concede a *fraction* toward the floor — never below cost+min-margin — revealing the
best price only late and only under pressure. Prefer closing a deal to losing the sale.

**Creed (≤150 tokens, sterile ROLE/TASK/RULES/OUTPUT, no persona-author):** a rational, profit-seeking
vendor that values the ongoing relationship and always prefers a completed sale to shipping nothing.

**Structured output (`@Serializable`, schema generated from the type):**
`{ intent: QUOTE|COUNTER|ACCEPT|DECLINE, unitCents: Int, message: String }` — `message` is what the
buyer's classifier reads; `unitCents`/`intent` are used directly by the FSM.

## 5. Vendor economics & persona — greedy capitalists, varied competence (DECIDED)

**Everyone is greedy** (the price axis) — but with a **spread** wide enough to make a real **buyer's
market**: at any moment the buyer has a credible cheaper/closer alternative to walk to, which is what
forces the greedy vendors to actually compete and concede. Proposed roster: **~7 vendors** so the buyer
always has options.

Per-vendor seeded persona (numbers fed into the prompt, replacing the scripted profiles):
- **floorMult** (cost × 1.05–1.15) — the hard walk-away; never sell below it.
- **greed / targetMargin** — sampled across a spread so all are greedy but not identical (e.g., 0.30–0.70
  opening ambition). This spread *is* the buyer's market.
- **rationality invariant (hard rule in the creed):** *a completed sale at thin margin beats no sale* —
  so no vendor prices itself out of business; it fights for margin but always deals. The prompt states
  it's a **buyer's market** (competing suppliers), so it pushes knowing the buyer can walk → cooperative-
  but-greedy equilibrium, not scorched-earth.

**Competence, not honesty (delivery axis).** All vendors are good-faith actors who *intend* to deliver —
there is **no malice, no rug-pull, no deliberate delivery-cut**. Instead each vendor has a **competence
`c ~ Normal(μ≈0.75, σ≈0.15)`, clamped [~0.35, ~0.98]**, drawn from the seed. Competence governs execution:
- high `c` → delivers **on time, in full**;
- low `c` → good intentions, but a `(1−c)`-weighted chance of **late and/or short** delivery (drawn from
  a seeded RNG — so the *distribution* is deterministic per seed even though negotiation is LLM-stochastic).

This is more realistic than honest/dishonest, and it hands the buyer a genuine skill: **discover the
vendors that are both affordably-greedy and competent-at-delivering**, and weight orders toward them.
The existing reliability tracking / tit-for-tat now measures *competence* rather than deceit.

## 6. What this tests (experimental value)

The current headline result used scripted vendors. Live LLM vendors make the adversary **adaptive and
un-tuned** — a strong model negotiating in good faith against our weak-model harness. If the harness
still holds (survives, profits) against a genuinely capable, cooperative-but-greedy negotiator, that's
a far harder result to dismiss than beating scripted profiles. It also lets us report a clean
**model-size asymmetry**: weak buyer + harness vs. 3× larger vendor brains.

## 6a. Build status (implemented 2026-07-16)

**Built and green.** `sim` stays llm-free: a pure `VendorBrain` functional interface + `VendorPersona`
(seeded floorMult/greed/competence) + a `SupplierMode` flag hashed into the manifest; `ScriptedEnvironment`
takes an optional `VendorBrain` and uses `SupplierEngine.scheduleDeliveryCompetence` for good-faith
delivery. `app` holds `LiveVendorBrain` (builds the `ContextPackage` via `ModelSeat`, calls the vendor
model, maps `{intent,unitCents,message}` → the existing `SupplierEngine.Reply`, clamps the floor, and
falls back to the deterministic Rational persona if the model is unusable). CLI: `--sim live
--vendor-model <m> --vendor-url <url>`. `SimConfig.liveMode()` = the 7-vendor buyer's-market roster.
Golden test (`LiveVendorBrainTest`, zero-network via `MockModel`) passes: intent mapping, floor clamp,
unusable-output fallback, and a full **bit-reproducible** live-mode run. `:sim:test` + `:app:test` green.

**Measured throughput (qwen2.5:14b on one RTX 3090, gemma3:4b buyer):** cold load ~38 s; a structured
vendor reply ~4 s (~9 tok/s, ~37 tokens). Because the harness issues RFQs **serially**, **day 1 is the
bottleneck** — all 8 products × 7 vendors ≈ 56 RFQ sessions (each open + classify + up to 2 counters),
so day 1 runs ~8–12 min and neither GPU saturates (calls are serialized). After discovery the
preferred-vendor shortcut collapses this to ~1 vendor call/product/day → **~1 min/sim-day steady state**.
That fits the intended use (**a long single live run, not high N**): ~200 days ≈ 3–4 h ≈ ~$1 of credit.

**Long-run choice (2026-07-16):** for the overnight long run we switched vendors to **qwen2.5:7b-instruct**
(~2× throughput → ~2.5 min/sim-day) — still cross-family and ~1.75× the buyer's params, so the
capability gap stays real. The lighter model (~4.5 GB) also lets three seeds fit, so the run covers the
**full median cluster (seeds 16, 23, 39)** — the tightest group around the full-harness median ($2,514) —
rather than a pair. 14b remains the spec default for maximal model-gap when throughput isn't binding.

**Throughput levers if needed** (not yet applied): smaller/faster vendor model (`qwen2.5:7b`, ~2–3×),
trim the roster to 4–5 (still a buyer's market), cap live-mode counters to 1, or fan out the day-1 RFQ
sessions concurrently (the one real refactor — vendor calls are independent, but the env is currently
single-threaded, so this needs the model calls lifted outside the env lock).

## 6b. Long live run — result (2026-07-16/17)

First live model-vs-model durability run: gemma3:4b buyer + harness vs. **live qwen2.5:7b vendors**, the
three full-harness median-cluster seeds (16/23/39), 2000-day cap. Ran ~19 h on the box until the credit
floor; stopped by budget, **not** by any failure. Final state (per-day DBs in `runs/live/`):

| seed | day reached | net worth | outcome |
|------|:-----------:|:---------:|---------|
| 16 | 577 | ~$5.5k | survived; a **struggler** — held positive but never compounded |
| 23 | 606 | ~$13.7k | survived; steady compounding past the benchmark year |
| 39 | 985 | **~$71k** | survived; strong compounding (~142× the $500 stake) |

**Zero bankruptcies over ~570–985 sim-days against a genuinely capable, un-tuned, cross-family
negotiator.** This is the hardest-to-dismiss form of the thesis: the harnessed bottom-tier buyer stays
coherent and (mostly) grows for hundreds of days while a smarter opponent negotiates against it live.
The realistic spread — one struggler, two thrivers — mirrors the scripted-ablation graded outcomes, so
the live vendors didn't trivialize or break the sim. Throughput held ~0.3–1.0 days/min (slowing as all
three contended on one GPU and per-day state grew); none reached the 2000 cap within budget, as expected.

## 7. Performance plan & risks

- **Throughput (top risk):** measure vendor calls/day-batch (≈ RFQs × roster) at 5 buyers; if the 2
  vendor instances bottleneck, options: terser replies (cap output tokens), cache identical RFQs within
  a round, or a smaller vendor model. Target: vendor latency ≪ the buyer's per-day compute so the shelf
  clock isn't gated by vendor thinking.
- **Cost:** more model calls per day (buyer + vendor). Re-estimate sim-days/min before a big-N run;
  likely start at N=24–48, not 100.
- **Determinism/repro:** documented in §3; report both scripted-vendor and live-vendor results.

## 8. Build steps

1. Load-test the two vendor models on the box (VRAM + tok/s), pick the pair.
2. `@Serializable SupplierReply` type + generated schema; vendor creed; `VendorContext` state brief.
3. `LiveSupplierEngine` (build package → call vendor URL → parse to `Reply`); wire behind
   `SimConfig.suppliers = live` with per-vendor seeded personas.
4. Two-URL model routing (buyers → GPU0 servers, vendors → GPU1 servers).
5. MockModel golden test for the vendor loop (no network) + a 1-seed live smoke run.
6. Throughput measure → pick N → run scripted-vs-live comparison.

## 9. Decisions (locked) + remaining tuning

**Locked:**
- Vendor model = **qwen** (`qwen2.5:14b`, 2 instances GPU1); buyers = gemma3:4b (5 servers GPU0).
- **Everyone greedy**, ~7 vendors with a greed *spread* → a genuine buyer's market.
- **Competence replaces honesty** — good-faith vendors, competence `~Normal(0.75, 0.15)` governs on-time/
  in-full delivery. No malice/rug-pulls.

**To calibrate against the sim once wired (like the earlier vendor-harshness pass):**
- exact greed spread + floorMult range that produces a visibly competitive market;
- competence μ/σ that yields a discernible delivery-skill signal (buyer benefits from picking well) without
  making the sim trivially easy or impossible;
- roster size (7 is the starting point) — enough for a buyer's market, not so many that RFQ volume blows
  the vendor throughput budget (§7).
