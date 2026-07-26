# Deployment Pipeline — Weak-Model GPU Run Harness

How we run N=100 gemma3:4b experiment arms (full, scripts, bare, and the R-series
ablations) on rented GPUs, track them, and get the data back without losing it.

Every design choice below is tied to a failure we actually hit. The pipeline is
paranoid on purpose: the compute is cheap and reproducible; the *operations*
(finding a sound box, surviving crashes, not losing data) are the real cost.

---

## 1. Topology

Two machines, clear division of labor:

- **Local (the mac)** — source of truth. Holds the code, builds the run artifact,
  keeps the canonical copy of every run DB, runs the monitoring/durability crons,
  and issues the single destructive action (destroy box). Never depends on the box
  surviving.
- **Ephemeral GPU box (Vast.ai)** — disposable compute. Runs ollama + gemma3:4b and
  the JVM harness. Assumed to die at any moment (credit kill, host crash, SSH wedge).
  Holds *no* irreplaceable state — everything it produces is mirrored locally within
  minutes.

The guiding invariant: **the box is cattle, not a pet.** Nothing about a result
depends on any single box living to the end of the run.

---

## 2. The run artifact

One self-contained tarball, built once per code change:

```
tar czf app-<arm>.tgz -C app/build/install app     # ~26 MB, the Gradle installDist output
```

All experiment arms ship the *same* binary. An arm is selected entirely by CLI flags
on the `run` subcommand, never by a separate build:

| arm | invocation | `run_kind` (provenance) |
|---|---|---|
| full | `run --full` | `harness` |
| scripts | `run --full --decisions script` | `harness` |
| bare | `baseline` | (baseline tables) |
| R1a (gates off) | `run --full --gates off` | `harness-R1a-nogates` |
| R1b (state off) | `run --full --state model` | `harness-R1b-nostate` |
| R5-pricing | `run --full --script-seats pricing` | `harness-R5-scriptpricing` |

**Why one binary, flag-selected arms:** so a run is fully attributable from its own
SQLite file. `run_manifest` records `run_kind`, `sim_config_sha`, `seed`, plan/gate
hashes. This is what lets the verify step (§7) prove a batch is a clean single-sha,
single-arm set before we trust it.

---

## 3. Host selection — the first and most expensive filter

Vast offers are a lottery. Advertised bandwidth is fiction and cheap community hosts
are frequently network-starved or unstable. The selection order that survived this
session:

1. **Prefer datacenter GPUs** (`search offers 'datacenter=true reliability>0.98'`).
   Community hosts (`hosting_type=0`) repeatedly delivered <1 MB/s real download
   despite advertised 500–900 Mbps — four dud boxes before we switched. A "stuck in
   `loading` for minutes" box is itself the signal: the host is slowly pulling the
   Docker image.
2. **Throughput-test before committing.** SSH in and pull a 100 MB file from a *near*
   CDN (`nbg1-speed.hetzner.com/100MB.bin` for EU boxes) — **not** transatlantic
   test servers (tele2/cachefly), which bias slow and gave false negatives. Target
   >50 MB/s; a real datacenter 4090/5090 does ~100 MB/s.
3. **SSH-stability check.** Three consecutive quick connects. A box that refuses SSH
   under light probing will wedge under load.
4. **Reasonable disk (40 GB)** and the *lighter* runtime image
   (`nvidia/cuda:12.4.1-runtime-ubuntu22.04`, not `-cudnn`) so the image pull is fast.

Cost of skipping this: the "operational tax" — ~$4–5 of a session spent on boxes that
produced nothing.

---

## 4. Bootstrap

`bootstrap-<arm>.sh`, run detached, writes `/root/bootstrap.log`, ends with a
grep-able `BOOTSTRAP_..._DONE` marker. Steps:

```
apt-get install -y curl ca-certificates zstd sqlite3 openjdk-21-jre-headless rsync
curl -fsSL https://ollama.com/install.sh | sh          # needs zstd (see below)
ollama serve & ; ollama pull gemma3:4b                 # ~3.3 GB, ~6 min at 10 MB/s
tar xzf /root/app-bare.tgz -C /root/app-inst           # deploy the harness
```

**`zstd` is mandatory.** The ollama installer extracts a `.tar.zst`; without zstd it
dies with `This version requires zstd for extraction` — and because the script has no
`set -e`, it prints DONE anyway and you discover the missing binary only when a run
fails. Learned the hard way; it's now first in the apt list.

---

## 5. Inference servers

`setup-servers-<arm>.sh` starts **4 ollama servers** on one GPU, each pinned, small
context, no eviction:

```
for k in 0 1 2 3; do port=$((11434+k));
  CUDA_VISIBLE_DEVICES=0 OLLAMA_HOST=127.0.0.1:$port OLLAMA_NUM_PARALLEL=3 \
    OLLAMA_CONTEXT_LENGTH=8192 OLLAMA_MAX_LOADED_MODELS=1 OLLAMA_KEEP_ALIVE=-1 \
    nohup ollama serve & done
```

- **8192 context** is plenty for the harness (externalized state → prompts stay
  small); it lets multiple model copies fit in VRAM. (The bare-rot arm is the
  exception — it grows an unbounded ledger and uses native 128K context.)
- **`KEEP_ALIVE=-1`** pins the model so it never unloads between calls.
- Runs are round-robined across the 4 ports.

---

## 6. Launch & concurrency — the hard cap

`run-<arm>.sh` runs inside **tmux**, iterating seeds 1..100 with a strict concurrency
limit:

```
tmux new-session -d -s <arm> "bash /root/run-<arm>.sh"
# inside run-<arm>.sh:
printf "%s\n" $(seq 1 100) | xargs -P 8 -I{} bash -c 'run_one "$@"' _ {}
```

Two non-obvious requirements, each from a failure:

- **tmux, not `nohup &`.** On these hosts, background processes get SIGHUP'd when the
  SSH session closes; the batch silently died leaving empty logs. tmux fully detaches
  and survives disconnects. Verify with `tmux ls` from a fresh SSH.
- **`xargs -P N`, not a `jobs -r` gate.** The original `while jobs -r >= MAXJOBS`
  gate failed on one box (lower container `ulimit`/pids cap): all 100 JVMs launched
  at once and the high-seed runs died with `Cannot fork / pthread_create EAGAIN`.
  Only ~12 survived. `xargs -P 8` is a bulletproof hard cap — it *cannot* exceed 8
  concurrent regardless of shell job-control quirks. We also `ulimit -u`/`-n` up as
  belt-and-suspenders. Verify the cap held: `java procs == logs == N ≤ P`, not 100.

A note on scale: 8–12 concurrent is the sweet spot. More risks fork exhaustion for no
throughput gain (the box is inference-bound, not slot-bound).

---

## 7. Tracking & durability — the mirror

The core failsafe. Every arm has two tiny scripts + one cron:

- **`pull-<arm>.sh`** — additive `rsync` of the box's `/root/runs/<arm>/` into local
  `runs/<arm>-box/`. Additive (no `--delete`): the box can only ever *add* to the
  local mirror.
- **`check-<arm>.sh`** — runs the pull, then computes `done=X/100`, mean/median/fail%
  over completed DBs (day ≥ 200 or terminated), reads credit, and reports box_dbs.
- **A recurring cron** (`CronCreate`, staggered per arm to avoid collision) that runs
  `check-<arm>.sh` every ~15 min and acts on the result.

**Why continuous mirroring:** a credit-kill or host crash then costs *nothing* — the
last pull is at most one interval old. This is the direct fix for the data-loss
incident where a box was destroyed with only part of its runs pulled (lost 52 seeds).
The rule burned in from that: **pull the whole tree, verify, before any destroy.**

The cron also carries the *interpretation* context (what full/scripts/bare mean) so
each tick is a real status line, not just a number — and so a fresh session can pick
up the run cold.

---

## 8. Failsafes, and the incident behind each

| failsafe | what it prevents | incident that taught it |
|---|---|---|
| Continuous local mirror (additive rsync) | data loss on box death | destroyed a box with 52 seeds un-pulled |
| Verify-before-destroy (single-sha + run_kind + N + 0 incomplete) | shipping a contaminated/partial set as final | mixing shas; a botched partial polluting a mirror |
| `xargs -P` hard concurrency cap | fork exhaustion → 88/100 runs dead | `jobs -r` gate failed on a low-ulimit box |
| tmux detach | batch dying on SSH close | `nohup &` SIGHUP'd, empty logs |
| `zstd` in bootstrap | silent ollama-install failure | `requires zstd for extraction`, script printed DONE anyway |
| Throughput test (near CDN) before bootstrap | sinking time into a network-starved host | 4 community hosts at <1 MB/s |
| Wedge detection (SSH dead **and** gpu_util 0 for 2 ticks) | thrashing on a dead box | a 5090 wedged at gpu_util 0, SSH gone, API said "running" |
| Credit guard ($0.40 floor → final pull + stop) | losing the tail when credit runs out | credit-only account; Vast auto-destroys at $0 |
| `[M]ainKt` regex in pkill | pkill killing its own shell | `pkill -f "MainKt"` matched the pkill command itself |
| Single-sha discipline (never combine arms across `sim_config_sha`) | invalid cross-run comparisons | the `supplierMode` field bumped the hash 071fd396→64fa1ff5 |
| CLI-independence (API reachable via raw `curl`) | tooling outage masquerading as an API outage | the `vastai` CLI binary vanished; misread as "API down" for hours |
| Ollama-server watchdog (restart any dead 11434–11437 each tick) | one crashed inference server silently stalling its runs | an ollama/CUDA segfault killed one of 4 servers; the 4 runs routed to it (seed ≡ port) stuck retrying `http_status=0` for hours, read as a slow tail |

Two of these deserve emphasis:

- **Wedge vs. transient SSH.** Vast SSH drops briefly under load and recovers — that
  is *not* a wedge. A real wedge is SSH dead on both direct+proxy **and** `gpu_util 0`
  (nothing computing) for two consecutive ticks. Only then do we destroy+relaunch;
  otherwise we'd thrash healthy boxes.
- **CLI-independence.** When every `vastai` call returned empty, the reflex was "Vast
  API is down." It wasn't — the CLI binary had been reaped from the scratchpad venv.
  A direct `curl https://console.vast.ai/api/v0/...` returned valid JSON. Lesson: when
  a whole tool goes dark, test the underlying service directly before diagnosing the
  service. The monitor crons now carry the reinstall recipe and the curl fallback.

---

## 9. Verify-before-destroy

Nothing gets destroyed until its data is proven complete and clean, locally:

```
FINAL pull  →  for every DB:  sim_config_sha == 64fa1ff5
                              run_kind      == <expected arm>
                              day ≥ 200 or terminated  (0 incomplete)
            →  compute mean/median/fail%  →  compare to reference arms
            →  yes | vastai destroy instance <id>   →  delete the cron
```

Only after that single-sha / single-arm / N-complete check passes does the box die.
This is the last line against the data-loss and sha-drift incidents.

---

## 10. Cost & failure-mode notes (the operational tax)

- **Compute is trivial; operations are the cost.** Per-run inference is pennies; the
  money and time go to finding a sound box and surviving crashes. Quote that overhead
  up front.
- **Prefer the API path when the model allows it.** OpenCode/OpenRouter (for models
  they host) have no box tax at all — no provisioning, no wedge, no credit-kill. GPU
  rental is only for locally-run models (gemma3:4b isn't on those marketplaces).
- **Estimates are theater.** Runtime is bimodal and lumpy (failures die fast,
  survivors grind the full horizon), so "days/min × remaining" is always ~2× short.
  The durability + guard design is what makes the actual duration not matter — report
  what happened, don't predict.

---

## 11. Quick reference — launching a new arm

1. `tar czf app-<arm>.tgz -C app/build/install app` (after building the arm's flags in).
2. Find a datacenter GPU; **throughput + SSH-stability test** before committing.
3. `scp` tarball + `bootstrap/setup-servers/run` scripts; run bootstrap detached;
   wait for `BOOTSTRAP_..._DONE`.
4. `setup-servers`; launch `run-<arm>.sh` in **tmux**; verify the cap held
   (procs == logs == P).
5. Write `pull-<arm>.sh` + `check-<arm>.sh` (box IP/port baked in); do an initial pull.
6. `CronCreate` a staggered monitor with the guards from §8 and the destroy id.
7. On N=100: **verify (§9) → destroy → delete cron.** On credit floor / wedge:
   final pull → accept partial N → stop.
</content>
