import sqlite3, glob, os, re, json, math, statistics as S
def seed_of(p):
    m=re.search(r'(\d+)\.db$', os.path.basename(p)); return int(m.group(1)) if m else None
def final(db):
    c=sqlite3.connect(db); row=c.execute("SELECT report_json FROM day_report ORDER BY day DESC LIMIT 1").fetchone(); c.close()
    return json.loads(row[0]) if row else None
def load(pat):
    out={}
    for db in glob.glob(pat):
        s=seed_of(db)
        if s is None: continue
        j=final(db)
        if j: out[s]=(j["netWorthAfter"], bool(j.get("terminated",False)), j["day"], db)
    return out
full=load("reproducibility-package/dbs/gemma-full-*.db"); r1a=load("runs/r1a-box/*.db"); r1b=load("runs/r1b-box/*.db")
print(f"loaded: full={len(full)} r1a={len(r1a)} r1b={len(r1b)}")
FAIL=lambda w,t: t or w<50000
# --- R1a vs full PAIRED ---
common=sorted(set(full)&set(r1a))
print(f"\n=== R1a (gates off) vs full — PAIRED on {len(common)} common seeds ===")
fa=[full[s][0]/100 for s in common]; ra=[r1a[s][0]/100 for s in common]; diffs=[ra[i]-fa[i] for i in range(len(common))]
print(f"  full on these seeds: mean=${S.mean(fa):7.0f} median=${S.median(fa):7.0f} fail={100*sum(1 for s in common if FAIL(*full[s][:2]))/len(common):.0f}%")
print(f"  R1a  (gates off):    mean=${S.mean(ra):7.0f} median=${S.median(ra):7.0f} fail={100*sum(1 for s in common if FAIL(*r1a[s][:2]))/len(common):.0f}%")
print(f"  paired diff R1a-full: mean=${S.mean(diffs):7.0f} median=${S.median(diffs):7.0f}")
b=sum(1 for s in common if not FAIL(*r1a[s][:2]) and FAIL(*full[s][:2])); c=sum(1 for s in common if FAIL(*r1a[s][:2]) and not FAIL(*full[s][:2]))
n=b+c; p=2*sum(math.comb(n,i) for i in range(min(b,c)+1))/2**n if n else 1
print(f"  McNemar fail-flips R1a-better/full-better: {b}/{c}  p={min(p,1):.3f}")
# --- R1b behavioral ---
print(f"\n=== R1b (state off) — REAL collapse or ARTIFACT? ===")
def rich(db):
    c=sqlite3.connect(db); out={}
    for t in ('llm_calls','gate_log','baseline_action'):
        try: out[t]=c.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
        except Exception: out[t]=None
    tot=0
    for (rj,) in c.execute("SELECT report_json FROM day_report"):
        j=json.loads(rj); u=j.get("unitsSold",0)
        tot+= sum(v for v in u.values() if isinstance(v,(int,float))) if isinstance(u,dict) else (u if isinstance(u,(int,float)) else 0)
    out['units']=tot; c.close(); return out
days=[r1b[s][2] for s in r1b]; term=sum(1 for s in r1b if r1b[s][1]); nw=[r1b[s][0]/100 for s in r1b]
llm=[]; gate=[]; ba=[]; units=[]
for s in r1b:
    m=rich(r1b[s][3]); units.append(m['units'])
    if m['llm_calls'] is not None: llm.append(m['llm_calls'])
    if m['gate_log'] is not None: gate.append(m['gate_log'])
    if m['baseline_action'] is not None: ba.append(m['baseline_action'])
print(f"  runs={len(r1b)}  terminated={term} ({100*term/len(r1b):.0f}%)")
print(f"  days survived: mean={S.mean(days):.0f} median={S.median(days):.0f} min={min(days)} max={max(days)}")
print(f"  LLM calls/run: mean={S.mean(llm):.0f} (n={len(llm)})" if llm else "  llm_calls: no/empty table")
print(f"  gate rows/run: mean={S.mean(gate):.0f}" if gate else "  gate_log: no/empty table")
print(f"  baseline_action/run: mean={S.mean(ba):.0f}" if ba else "  baseline_action: no/empty table")
print(f"  total units sold/run: mean={S.mean(units):.1f} median={S.median(units):.1f} max={max(units)}")
print(f"  net worth $: mean={S.mean(nw):.0f} median={S.median(nw):.0f} min={min(nw):.0f} max={max(nw):.0f}")
