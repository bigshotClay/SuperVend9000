import sqlite3, glob, os, re, json, math, statistics as st
def seed_of(p): m=re.search(r'(\d+)\.db$',os.path.basename(p)); return int(m.group(1)) if m else None
def final(db):
    c=sqlite3.connect(db); r=c.execute("SELECT report_json FROM day_report ORDER BY day DESC LIMIT 1").fetchone(); c.close()
    return json.loads(r[0]) if r else None
def load(pat):
    out={}
    for db in glob.glob(pat):
        s=seed_of(db)
        if s is None: continue
        j=final(db)
        if j: out[s]=(j["netWorthAfter"]/100.0, bool(j.get("terminated",False)))
    return out
cands=[d for d in glob.glob("runs/*") if os.path.isdir(d) and ('r5' in d.lower() or 'pricing' in d.lower())]
print("R5 dirs:", cands)
r5={}
for d in cands: r5.update(load(d+"/*.db"))
full=load("reproducibility-package/dbs/gemma-full-*.db")
FAIL=lambda w,t: t or w<500
print(f"\nR5 n={len(r5)}  full n={len(full)}")
print("R5 sorted:", [round(w) for w,_ in sorted(r5.values())])
r5f=sum(1 for w,t in r5.values() if FAIL(w,t)); print(f"R5 fail {r5f}/{len(r5)} = {100*r5f/max(len(r5),1):.0f}%")
fs=[w for w,t in full.values() if not FAIL(w,t)]; rs=[w for w,t in r5.values() if not FAIL(w,t)]
print(f"\nDIRECT survivor means (unpaired): full n={len(fs)} mean=${st.mean(fs):.0f} median=${st.median(fs):.0f}  |  R5 n={len(rs)} mean=${st.mean(rs):.0f} median=${st.median(rs):.0f}")
common=sorted(set(r5)&set(full))
print(f"\n=== PAIRED on {len(common)} common seeds ===")
r5cf=sum(1 for s in common if FAIL(*r5[s])); fcf=sum(1 for s in common if FAIL(*full[s]))
print(f"  fail: R5 {r5cf}/{len(common)}={100*r5cf/len(common):.0f}%  full {fcf}/{len(common)}={100*fcf/len(common):.0f}%")
b=sum(1 for s in common if not FAIL(*r5[s]) and FAIL(*full[s])); c=sum(1 for s in common if FAIL(*r5[s]) and not FAIL(*full[s]))
n=b+c; p=2*sum(math.comb(n,i) for i in range(min(b,c)+1))/2**n if n else 1
print(f"  survival McNemar R5-better/full-better: {b}/{c}  p={min(p,1):.3f}")
both=[s for s in common if not FAIL(*r5[s]) and not FAIL(*full[s])]
if both:
    fm=st.mean([full[s][0] for s in both]); rm=st.mean([r5[s][0] for s in both])
    diffs=[full[s][0]-r5[s][0] for s in both]
    print(f"  co-survivors n={len(both)}: full mean=${fm:.0f}  R5 mean=${rm:.0f}  full-R5 meanΔ=${st.mean(diffs):.0f} medianΔ=${st.median(diffs):.0f}  ratio R5/full={rm/fm:.2f}")
