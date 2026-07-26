# Phase 1 statistical reporting over archived DBs. Stdlib only. Reproducible bootstrap.
import sqlite3, json, glob, os, math, statistics as st, random
random.seed(42)
DBS="dbs"; FAIL=50000
def final(db):
    c=sqlite3.connect(db); row=c.execute("SELECT report_json FROM day_report ORDER BY day DESC LIMIT 1").fetchone(); c.close()
    j=json.loads(row[0]); return j["netWorthAfter"], bool(j.get("terminated",False))
def load(prefix):
    out={}
    for db in glob.glob(os.path.join(DBS,prefix+"-*.db")):
        seed=int(os.path.basename(db).rsplit("-",1)[1].split(".")[0]); out[seed]=final(db)
    return out
gb,gs,gf=load("gemma-bare"),load("gemma-scripts"),load("gemma-full")
def dollars(d): return {s:v[0]/100.0 for s,v in d.items()}
def q(xs,p):
    xs=sorted(xs); i=p*(len(xs)-1); lo=int(i); f=i-lo
    return xs[lo] if lo+1>=len(xs) else xs[lo]*(1-f)+xs[lo+1]*f
def quant(name,d):
    xs=list(dollars(d).values())
    print(f"  {name:8} mean={st.mean(xs):8.0f}  p10={q(xs,.1):7.0f}  p25={q(xs,.25):7.0f}  median={q(xs,.5):7.0f}  p75={q(xs,.75):7.0f}  p90={q(xs,.9):7.0f}")
def mcnemar(af,bf):
    common=sorted(set(af)&set(bf))
    b=sum(1 for s in common if bf[s] and not af[s]); c=sum(1 for s in common if af[s] and not bf[s])
    n=b+c
    if n==0: return b,c,1.0
    k=min(b,c); p=2*sum(math.comb(n,i) for i in range(k+1))/(2**n); return b,c,min(p,1.0)
def wilcoxon(diffs):
    d=[x for x in diffs if x!=0]; n=len(d)
    if n==0: return 0,0.0,1.0,0
    order=sorted(range(n),key=lambda i:abs(d[i])); R=[0.0]*n; j=0
    while j<n:
        k=j
        while k+1<n and abs(d[order[k+1]])==abs(d[order[j]]): k+=1
        avg=(j+k+2)/2.0
        for m in range(j,k+1): R[order[m]]=avg
        j=k+1
    Wp=sum(R[i] for i in range(n) if d[i]>0); Wm=sum(R[i] for i in range(n) if d[i]<0); W=min(Wp,Wm)
    mu=n*(n+1)/4.0; sig=math.sqrt(n*(n+1)*(2*n+1)/24.0); z=(W-mu)/sig; p=math.erfc(abs(z)/math.sqrt(2))
    return W,z,p,n
def boot(diffs,reps=10000):
    n=len(diffs); m=[]
    for _ in range(reps): m.append(sum(diffs[random.randrange(n)] for _ in range(n))/n)
    m.sort(); return m[int(.025*reps)], m[int(.975*reps)]
def paired(a,b,label):
    common=sorted(set(a)&set(b)); da,db=dollars(a),dollars(b)
    diffs=[da[s]-db[s] for s in common]
    lo,hi=boot(diffs); W,z,p,n=wilcoxon(diffs)
    print(f"  {label:15} meanD={st.mean(diffs):8.0f}  medianD={st.median(diffs):8.0f}  bootCI95=[{lo:7.0f},{hi:7.0f}]  Wilcoxon z={z:6.2f} p={p:.2e}")
def fails(d,thr): return {s:(v[1] or v[0]<thr) for s,v in d.items()}
def terminated(d): return {s:v[1] for s,v in d.items()}
print("QUANTILES (net worth $)"); quant("bare",gb); quant("scripts",gs); quant("full",gf)
print("\nPAIRED NET-WORTH DIFFERENCES (bootstrap CI + Wilcoxon, two-sided normal approx)")
paired(gf,gb,"full - bare"); paired(gs,gb,"scripts - bare"); paired(gf,gs,"full - scripts")
print("\nSEPARATED PAIRED FAILURE TESTS (full vs scripts)")
b,c,p=mcnemar(terminated(gf),terminated(gs)); print(f"  termination only         McNemar {b}/{c}  p={p:.4f}")
b,c,p=mcnemar(fails(gf,FAIL),fails(gs,FAIL)); print(f"  composite (<$500 or term) McNemar {b}/{c}  p={p:.4f}")
common=sorted(set(gf)&set(gs)); bs=[s for s in common if not gf[s][1] and not gs[s][1]]
if bs:
    diffs=[(gf[s][0]-gs[s][0])/100.0 for s in bs]; W,z,p,n=wilcoxon(diffs)
    print(f"  net worth | both survived (n={len(bs)})  meanD=${st.mean(diffs):.0f}  Wilcoxon z={z:.2f} p={p:.2e}")
print("\nTHRESHOLD SENSITIVITY (failure %, and full-vs-scripts McNemar p)")
for thr,lab in [(None,"terminated only"),(25000,"<$250"),(50000,"<$500"),(100000,"<$1000"),(200000,"<$2000")]:
    f=lambda v: v[1] if thr is None else (v[1] or v[0]<thr)
    frb=100*sum(1 for v in gb.values() if f(v))/len(gb); frs=100*sum(1 for v in gs.values() if f(v))/len(gs); frf=100*sum(1 for v in gf.values() if f(v))/len(gf)
    af={s:f(v) for s,v in gf.items()}; sf={s:f(v) for s,v in gs.items()}; _,_,p=mcnemar(af,sf)
    print(f"  {lab:16} bare={frb:5.0f} scripts={frs:5.0f} full={frf:5.0f}   full-vs-scripts p={p:.4f}")
