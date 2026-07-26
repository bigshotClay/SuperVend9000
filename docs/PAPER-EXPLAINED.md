# What the paper says, in plain language

*A companion to "Ablating Persistent Structured State Collapses a Weak-Model Scaffolding Harness." This document explains the study for a non-specialist. It stays faithful to what the paper actually claims — including the things it is careful* not *to claim. Every number here comes from the paper; the paper's numbers come from archived data (see its Appendix A).*

---

## The one-sentence version

A small, weak AI cannot run a pretend vending-machine business on its own, but wrapping it in the right support system turns near-total failure into majority success — and when we take that support system apart piece by piece, the piece that matters is **giving the AI a reliable external memory**, not the safety checks people usually assume are doing the work.

---

## The question

There's a popular way to test how good an AI is at long, multi-step jobs: hand it the job "bare" — just the AI, a way to take actions, and nothing else — and watch it fail. People often read that failure as *"the AI isn't capable enough."*

We think that test measures two things tangled together: how capable the model is, **and** how much it was left to fend for itself with no support. This paper tries to pull those two apart, and then goes one step further: if a support system *does* help, **which part of it** is actually helping?

## The setup: a 200-day vending-machine business

We use a simulated business (based on a public benchmark called Vending-Bench). You start with $500. Every day costs $2 to operate. You buy stock, set prices, sell to customers, deal with suppliers — some of whom are cheats — and try not to go broke over 200 days. It's deterministic: give it the same random "seed" and the same decisions, and the same day-by-day story plays out. That lets us compare approaches **on the exact same 100 scenarios**, which is a much fairer comparison than running each on different luck.

The AI we use is deliberately weak: `gemma3:4b`, a small model. We *want* it to be weak, because a weak model makes it obvious how much the support system contributes.

## The three contestants

1. **bare** — the weak AI alone. Once per day it looks at the situation and makes every decision in one shot. No notebook, no running records.
2. **scripts** — no AI judgment at all. Just sensible fixed rules ("reorder when stock is low," "don't sell below cost," etc.). This is the crucial control: it tells us what plain, dumb software can achieve *without* any AI cleverness.
3. **full** — the "harness": the AI, but supported. It has an external ledger that tracks the true state of the business, gets a clean fresh summary each time instead of an ever-growing pile of history, has its money decisions checked by automatic safety rules, and hands specific decisions (like pricing) to the AI while letting fixed rules handle the rest.

## What happened

Over 100 identical scenarios:

| Contestant | How often it failed | Typical ending net worth |
|---|:--:|:--:|
| **bare** (AI alone) | 100% | $83 |
| **scripts** (rules only) | 59% | $29 |
| **full** (supported AI) | 46% | $1,415 |

The AI alone failed **every single time**. Plain rules did better but were mediocre. The supported AI was the only one that typically ended up running a real, solvent business.

An important nuance the paper insists on: the supported AI does **not** mainly win by avoiding bankruptcy — it goes broke at about the same rate as the plain rules (44% vs 47%, a difference too small to call). Its advantage shows up when you ask for *more* than mere survival: the higher you set the bar for "success," the bigger its lead grows (from a 3-point edge at "didn't go bankrupt" to a 32-point edge at "ended above $2,000"). In plain terms: **the support system doesn't keep the business barely alive so much as make the businesses that survive worth a lot more.**

## The clever part: taking the winner apart

Knowing the full system wins isn't enough. We wanted to know *which piece* wins. So we removed pieces one at a time and re-ran the same 100 scenarios.

**Remove the safety checks (the "gates").** Almost nothing changed — same failure rate, same typical outcome. Those money-guarding rules almost never actually triggered in this business (about 0.2% of money actions). *Careful wording:* this doesn't prove safety checks are useless in general — it means that in *this* particular business they rarely had anything to do, so removing them didn't hurt. A guardrail that never gets tested tells you little.

**Remove the external memory (make the AI track everything in its head).** The system **collapsed** — it failed 100% of the time and did *worse* than the bare AI. This is the paper's most striking result. Crucially, this version kept everything else — the same number of AI calls, the same breaking-the-job-into-steps structure — so the collapse can't be explained away as "fewer chances to think." Take away the reliable external memory and the whole thing falls apart, even harder than the naive baseline.

**Replace just the AI's pricing decision with a fixed rule.** The full system's entire advantage vanished — it dropped right back down to the plain-rules level (in fact slightly below it). So of all the decisions the AI was making, its **pricing** judgment was the one carrying the win.

## What it means

Put together, a clean story:

- The support system's real job is to be the AI's **memory and workspace** — a reliable, external record of the state of the business that a small model simply can't hold in its head across 200 days of decisions. That's the load-bearing piece.
- The safety checks were not what made it work here (though that's specific to this low-risk business).
- On top of that memory, the AI's own contribution is **narrow but real**: it prices better. When we looked at how, it wasn't selling *more* stuff — it was selling at a *higher price* (about 17% higher per unit), which is where the extra money came from.
- A practical lesson for anyone building these systems: the weak AI wasn't generically useful everywhere. Its value was concentrated in one high-stakes decision. You may get more out of a hybrid system by handing the AI the *few* decisions where its judgment really pays off than by letting it make *every* decision.

## What the paper is careful NOT to claim

This is the part most write-ups skip, and it's where the paper spent the most effort. In the interest of not overselling:

- **"Memory" is our interpretation, not a proven mechanism.** When we removed the "external memory," we actually removed a *bundle* of things at once (a persistent record, a clean summary, a fresh start each turn). We showed removing the bundle is catastrophic; we did **not** isolate which specific ingredient matters. Calling it "memory" is a reasonable reading, not a demonstrated fact.
- **We can't prove the AI adds nothing outside pricing.** Pricing is clearly necessary. But because the decisions interact, we can't rule out that other decisions matter too — we'd need more experiments.
- **Higher price ≠ proven higher profit.** Our logs recorded what the AI *sold* things for but not what it *paid* for them, so we can honestly say revenue-per-item went up, not that profit did.
- **The safety-checks finding is local.** It applies to this business, which rarely stressed those checks — not to guardrails in general.
- **This is one weak model on one simulator.** It shows a support system rescues *this* model here. It doesn't show the result holds for every model or every task.
- **The test scenarios weren't held out.** The system was tuned while we could see these same 100 scenarios, so the numbers describe performance on the scenarios it was developed against — not a guarantee about brand-new, unseen ones. A clean "held-out" re-run is the biggest remaining thing that would strengthen it.

## The honest bottom line

On this task, with this weak model: plain software plus a bare AI isn't enough. Give the AI a reliable external memory and it goes from *always* failing to *usually* succeeding — and that memory, not the safety rails, is what does the heavy lifting. The AI's own cleverness matters in exactly one place: setting prices. Everything beyond that, we've flagged as either a limitation or a question for future work — because the most useful thing a study like this can do is be exactly clear about where the evidence stops.
