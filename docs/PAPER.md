# Why You Should Not Put Load-Bearing Instructions on a Long-Running Agent

I gave `gemma3:4b` — four billion parameters, one of the weakest models you can run — a hostile business to operate for 200 days. Bare, it failed 100% of the time. Wrapped in a harness that holds its state and gates its actions, the same model ran a real business: **$1,415 median net worth over 100 matched seeds**, recomputable to the cent from the archived databases. The scaffolding makes a bottom-tier model viable at all — and once it's scaffolded, the model still earns +$990/seed over the scripts alone. It's the pairing that does the work, not either half.

I didn't plan the second finding. Running the experiment was itself a long-horizon agentic process, and it failed twice — at the exact steps I left to an agent's judgment instead of a gate. Once it destroyed the data. Once it corrupted a result for days. The thing I was studying kept happening to me while I studied it. So the title isn't about vending machines. It's the lesson, illustrated twice — once by the model, once by the experiment running it (an analogy across two mechanisms, not a second controlled proof): **a long-running agent fails at the step you didn't gate.**

Below is the evidence and the honest limits. No buildup.

## The result

Three arms, same simulation, same seeds. Only the decision layer changes: `scripts` (fundamentals, no model), `bare` (model, no harness), `full` (harness + model). The sim is deliberately adversarial — 8 products, seasonality, weather, finite storage, a roster of greedy negotiators plus a rug-pull and a prepay scammer. Same economics as Vending-Bench, Andon Labs' long-horizon agent benchmark: $500 start, $2/day fee, bankrupt after 10 unpaid days. N = 100, and the sim is deterministic per seed, so every comparison is paired. (How each arm drives the sim, with diagrams: [`SIMULATOR.md`](SIMULATOR.md).)

| arm | failure rate | mean | median |
|---|:---:|:---:|:---:|
| bare (model only) | 100% | $86 | $83 |
| scripts (fundamentals) | 59% | $735 | $29 |
| **full (harness + model)** | **46%** | **$1,725** | **$1,415** |

The median is the honest number — the distribution is heavily right-skewed. The typical full run is a real business. The typical bare or scripts run is broke. Only the combination has a positive center.

Paired tests, matched seeds:

| comparison | Δ net worth / seed | t | McNemar | ~σ |
|---|:---:|:---:|:---:|:---:|
| full − bare | +$1,639 | 8.3 | 54/0 | ~7.2σ |
| scripts − bare | +$649 | 5.8 | 41/0 | ~6.2σ |
| full − scripts | +$990 | 7.5 | 16/3 | ~2.8σ |

Bootstrap 95% CIs (10k resamples, seeded): full−bare [$1,259, $2,041], scripts−bare [$438, $883], full−scripts [$740, $1,247]; Wilcoxon signed-rank two-sided p = 1.6×10⁻⁷, 6.8×10⁻⁴, 1.3×10⁻⁹. (scripts−bare has a *negative* median difference, −$38 — it beats bare on its winners, not at the median, which is why its mean and median disagree.)

One caveat, and it's the honest crux. Split "failure" into its parts and the story sharpens: full and scripts go **bankrupt at the same rate** — 44% vs 47%, McNemar p=0.61, no significant difference. What full wins is the **quality of survival**: on the composite <$500 criterion p=0.0044, and among seeds where both arms survive, full's businesses are worth **+$1,751/seed more** (Wilcoxon p=5×10⁻⁹). The edge grows monotonically as the success bar rises — full-vs-scripts McNemar p runs 0.61 → 0.049 → 0.0044 → <10⁻⁴ from "went bankrupt" up through "cleared $2,000." The harness does not keep the model alive; it makes the survivors real businesses. Here's the mechanism.

## What the harness actually does

Not "prevents bankruptcy" — that story is wrong. Split each run into dead / alive-but-broke / thriving:

| arm | dead | broke | thriving |
|---|:---:|:---:|:---:|
| bare | 83% | 17% | 0% |
| scripts | 47% | 12% | 41% |
| full | 44% | 2% | 54% |

Full and scripts die at nearly the same rate — 44 vs 47%. That's the whole reason the failure-rate test is only ~2.8σ. What the harness does is erase the alive-but-broke middle, 12% down to 2%, and convert those runs into real businesses: 41% → 54% thriving. Don't idle, not don't die. The model alone is a different failure entirely — 83% terminated, 0% thriving, 100% failure. On an adversarial market, a bottom-tier model with no scaffolding isn't mediocre. It's non-viable.

The full-over-scripts gain has a specific channel, and it isn't volume. The two arms sell almost identical units — ~51/day each in the back half of the run — but the model prices for more margin: full clears $96/day in revenue to scripts' $80, and compounds to $2,539 median net worth by days 151–200 against scripts' $1,236. Same units, better margins, twice the business. And the gates the model sits behind almost never intervene — 0.2% of money actions rejected across the full runs — so the gain is the model improving on the floor, not the floor catching the model.

## What the harness is

One law drove every decision: **if a script can do it, a script does it.** The model is called only where judgment actually helps, and it sits on a floor that's correct by construction.

- **State lives in a SQLite ledger, not the context window.** The model gets a fresh state brief each call, never its own transcript — nothing accumulates across calls to rot.
- **Every money action passes a gate** — margin floor, spend cap, payment validation. A rejected proposal falls back to the scripted action, so the gates bound the downside on those accounting dimensions — they block the specific invalid actions, they don't make every model choice better. In practice they rarely bind: 0.2% of money actions get rejected across the full runs. A rejection isn't a failure; it's the floor holding on the rare occasion the model reaches below it.
- **One StateActor, one ground truth.** Gate-check and write are atomic. Nothing races.

A deterministic machine with a gated judgment layer bolted on. The experiment measures what the layer adds and whether the machine is what makes a weak model usable at all. The answer is both.

## Which part of the harness does the work

The harness bundles several things — externalized state, financial gates, specialized model seats, deterministic routines. Which one carries the result? I removed them one at a time, matched-seed against `full` (gemma3:4b, same sim, N=100).

| arm | change from full | fail % | median net worth |
|---|:---:|:---:|:---:|
| full | — (reference) | 46% | $1,415 |
| R1a | gates removed | 45% | $1,378 |
| R1b | externalized state removed | 100% | −$18 |

A clean double dissociation.

**Gates are not load-bearing.** Remove the entire gate suite and nothing that matters moves: failure 45% vs 46% (McNemar 13/12, p=1.0), paired median difference $0. The mean rises (+$399/seed) because the gates lightly clip the right tail — a few below-floor moves that would have paid off — but survival and the typical run are unchanged. This is the earlier finding confirmed causally from the other side: the gates bind on 0.2% of actions because they are a rarely-touched safety floor, not the engine.

**Externalized state is load-bearing — and its absence is worse than no harness at all.** Strip the state ledger, forcing the model to track its own inventory, costs, and prices in context, and the business collapses: 100% failure, not one of 100 runs clearing $500, median net worth −$18. This is a genuine behavioral collapse, not a broken harness — these runs lasted 183 days on average, made 511 model calls each, and actively traded (220 units sold per run). The model tries hard and cannot hold the thread. And it keeps the *full* specialized-call structure and call budget, so the same result closes the obvious confound: the advantage is not merely the larger decision budget or the decomposition into specialized seats — strip only the memory and the identical budget produces catastrophe.

Look at where it lands: −$18 is below `bare`'s $86. A model with the harness's action structure but no memory is *worse* than a model with no scaffolding at all — the structure drives it to keep procuring and pricing, and with no persistent state to coordinate those moves, it trades itself into deeper bankruptcy than near-inertia would. Externalized state isn't help layered on a working system. It is the substrate the weak model needs to function at all; the model's own judgment (the pricing-for-margin edge above) is what turns functioning into a real business on top of it.

So the harness's power is the externalized state — working memory the 4B model does not have on its own — not the guardrails. (One control I have not run: a tool-enabled model allowed to build and maintain its *own* scaffold. "Can't hold state when I withhold it" is not "couldn't build state if given tools." That is the honest next experiment. A model-seat ablation — which decision earns the margin — is running as I write this.)

## Bare failing is not the model being dumb

The tempting read: bare fails 96%, so the model can't run a business. That's a category error, and it's the same one a lot of capability measurement makes.

Ask a competent person to run a vending machine one day at a time for 200 days — no plan, no notebook of which suppliers cheated them last week, no second look before a decision is final, re-deciding everything from scratch each morning. They'd fail. Not because they can't run a business — because the setup is absurd. That's the bare arm — and the databases show exactly how it breaks. It doesn't slowly drift off course; it drops half the business on day one. It procures inventory and collects cash — those actions fire — but it never effectively prices or sells: ~0.3 units sold per day from the first week to the last, stock piling up unsold while the $2/day fee bleeds the stake. Its ~143-day "survival" isn't a business slowly decaying; it's fee-arithmetic on an inert machine. The failure isn't lost memory over a long horizon — it's that the one-shot compound decision (price, restock, procure, collect, all at once, every day) is too much to juggle unscaffolded, and pricing is the ball that gets dropped. That still points at the setup, not the model's ceiling: it manages the parts it can reach and silently abandons the rest.

Competent long-horizon work is *always* scaffolded. Accountants use ledgers. Ops teams run on ERPs and checklists. Externalized memory isn't a crutch a "real" agent should do without — it's the normal substrate of competent work. Strip it away and you measure the absence of the harness, not the ceiling of the model.

Two limits, so this doesn't prove too much. Bare is still a valid control — it isolates what the scaffold adds, and I rest no capability verdict on it. And the genuinely fair capability test — give the model tools and let it scaffold *itself* — is one I didn't run. "Unaided fails" is not "couldn't do it with tools it chose." I tested the first, not the second.

## The adversary was live, too

The sharpest objection: you tuned your adversary. The vendors were scripted profiles — maybe the harness was fitted to beat them. So I replaced them with live models. Buyers stayed `gemma3:4b`; vendors became `qwen2.5:7b` — larger, different family, negotiating live. Three seeds, run until budget stopped them, not failure:

| seed | last day | net worth | outcome |
|---|:---:|:---:|---|
| 16 | 577 | $5.5k | struggled, held positive, never compounded |
| 23 | 606 | $13.7k | steady compounding past the benchmark year |
| 39 | 985 | $71k | strong compounding, ~142× the stake |

Zero bankruptcies across 577–985 days against a larger, un-tuned negotiator. One struggler, two thrivers — the live vendors neither broke the sim nor trivialized it.

Flat caveats. This is n=3, and the seeds were the earlier run's median cluster, fixed before these runs — so it shows long-horizon coherence against live adversaries is *achievable*, not a survival *rate*. Three seeds can't set a rate. These also run on a different sim config than the N=100 ablation, so the dollars don't compare across the two. And the early live runs didn't persist the vendor model id to the manifest — confirmed from the launch config, not the artifact. Fixed since.

## It runs 500+ days without collapse

Long-horizon coherence is the benchmark's actual hard part, so I pushed five near-median seeds toward a 3,000-day cap. Two bankrupt early, days 76 and 78 — on-distribution with the 46% base rate. The other three compound with zero rot, net worth rising monotonically well past the 365-day mark. At day 365 they sit at $9.7k / $11.6k / $11.3k; one climbs to $16.6k by day 519 before the cloud servers died. A harnessed bottom-tier model either dies early or runs a real business for 500+ days. (These dollars aren't comparable to the public Vending-Bench leaderboard — different economy. The point is the coherence, not the figure.)

## The limits

- **One model.** This shows a harness lets a *weak* model perform well. It does not show performance is independent of model capability — that needs the multi-model flat curve, which I haven't run. It's the pending headline, not a result.
- **A negative result I'll own.** I thought the harness's tendency to starve rather than overpay a predatory vendor was a fixable bug, and prototyped a fix. It looked like +6 survivors at N=48; at N=136 it evaporated, −$208/seed. The starvation is a deliberate net-worth-maximizing policy, not a bug. Weakest-sourced claim in here: those N=136 databases died with the box, so it's from notes, not in the archive.
- **An attributability gap.** Early live runs didn't record the vendor model in the manifest — against my own "every run reconstructable from its file" rule. Fixed for future runs.
- **What the ablation doesn't test.** Removing components shows which parts of *my* scaffold matter; it doesn't test whether the model could build its own. A tool-enabled model that constructs and maintains its own memory is the fair capability ceiling, and I didn't run it. That's the next experiment, not a result.

## The experiment failed at its own lesson

Now the part the title is actually about. Running this study was a long-horizon agentic process, and it failed twice at exactly the steps the harness exists to protect.

**One: an agent destroyed the data.** Tearing down a rented box, an agent was told — explicitly, in order — to sync the databases before destroying the instance. It skipped the sync and killed the box. The N=100 full and bare runs, hours of compute and real money, gone. "Sync before you destroy" is load-bearing. Left to an agent's memory, it dropped at the one moment it mattered — because nothing made the destroy step refuse to run until the sync was verified.

**Two: a silent flag corrupted a result for days.** The bare arm's `--sim hostile` flag hit a parser that didn't know the value and silently fell through to an easier default sim. No error. For days, "the model alone is no better than fundamentals" was quietly false — bare had been playing an easier game. It surfaced only because the manifest hashes the sim config, so the arms carried mismatched hashes. The one place discipline held is the one place the bug showed.

Both fixes were the thesis turned on its own tooling: teardown now syncs, verifies checksums, and only then destroys; the flag hard-fails on an unknown value; nothing computes a cross-arm statistic until all arms share one sim hash. Gates that refuse, not instructions that hope. Everything above is the regenerated data, collected under that pipeline. The originals are gone. These were earned back the right way.

The honest root cause isn't flattering: none of this was "the agent failed." It was my discipline lapsing behind too many assumptions. I assumed the sync happened. I assumed the flag was honored. I didn't check the file count before a teardown or the hash before I compared arms — things I already knew to do. The agent did drop the instruction. But trusting an ungated agent to carry a load-bearing step was my design error, not its moral failing.

The experimenter is a variable — a source of error the design has to account for. You wouldn't ask a human collaborator to silently catch all your mistakes and account for *you* as that variable. Same for an agent, and worse, because a human supplies friction. A person asks "wait, did you sync?" An agent won't push back, won't volunteer the check you skipped, won't tell you that you are the uncontrolled variable in your own study. So the discipline you have to bring is *higher* with an agent, exactly where everyone assumes it's lower because "the agent will handle it."

That's what magnifies the result instead of undercutting it. I believed the thesis. I built a gated harness *because* I distrust ungated judgment. And I still couldn't hold the load-bearing steps by hand over a handful of days. If the person most primed to supply that discipline can't, nobody should bet a result on supplying it by hand. So externalized discipline stops being a trick for propping up a weak model. It is the necessary condition for long-horizon agentic work at all. The harness didn't rescue a 4B model. It rescued the process — from a failure mode its author wasn't exempt from.

That's the line I'd stake something on: **the failure mode of a long-running agent is the load-bearing step you left to its judgment.** True of a 4B model that silently drops the pricing half of its job, every day from the first. True of the pipeline of agents, and the human, that lost a box and shipped a silent bug. The fix is the same at every scale. Externalize the state. Gate the irreversible step. Verify before you can't undo it. Don't put load-bearing instructions on a long-running agent — put them where they can't be dropped.

---

## What would change my mind

The data limits are above. These are the harder objections — about reasoning and framing, not numbers — and where I land on each. I'd rather raise them than get raised by them.

**"You generalize a universal law from one model on one sim."** True. The title is a claim I'd stake something on, not a theorem I proved. What's proven is narrow: on this sim, the scaffold carries a weak model. The law is a hypothesis put up to be broken, and there's a clean way to break it — the flat curve across model tiers. It's turnkey in the repo. I'd take being wrong over not knowing.

**"It's a sandbox you built and then beat."** Also true, and it's what a sim is for. A sim can't prove real-world capability; it can hold the environment fixed and vary only the decision layer, which isolates coordination from world-modeling — the one variable I wanted clean. The claim is comparative, never absolute. And if the sim were rigged for the harness, `scripts` wouldn't land cleanly between `bare` and `full`. It does.

**"'The same law proven twice' is a rhetorical trick."** Partly. A model losing state and me leaving a sync step ungated are different mechanisms; I'm not claiming identical causation. Same lesson, and I'll call it an analogy, not a proof. If it only illuminates for me, drop it — the table doesn't need it.

**On "context rot."** I used the term loosely early on and have tightened it. The bare arm gets a fresh state snapshot each day, so it is not a clean test of a model's own transcript degrading — the strict meaning of the phrase. What bare lacks is persistent externalized discipline, and its failure is long-horizon incoherence, not proven memory-rot. The clean test is a bare arm fed its own accumulating history. I didn't run it. That's the honest next experiment after the flat curve.

So here is the gauntlet, and it's a fair one. The fastest way to prove me wrong is already in your hands: run the flat curve and show the scaffold doesn't generalize, or run the transcript-rot arm and show a weak model holds coherence on its own. The harness is built for both, the databases are in the repo, and I'll cite whoever lands it. It's down.

---

*Methods: all comparisons matched-seed (paired) — McNemar for survival flips, paired-t and Wilcoxon for net worth. Failure = terminated or final net worth < $500. Data and a script that regenerates every figure ship in the reproducibility archive. Harness design derives from the open-source BMAD-METHOD.*
