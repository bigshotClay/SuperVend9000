"""Computations demanded by the major-revision review.
All numbers regenerate from archived DBs. stdlib only. Seeded bootstraps.
"""
import sqlite3, glob, os, re, json, math, random, statistics as S

def seed_of(p):
    m = re.search(r'(\d+)\.db$', os.path.basename(p)); return int(m.group(1)) if m else None
def final(db):
    c = sqlite3.connect(db); row = c.execute("SELECT report_json FROM day_report ORDER BY day DESC LIMIT 1").fetchone(); c.close()
    return json.loads(row[0]) if row else None
def load(pat):
    out = {}
    for db in glob.glob(pat):
        s = seed_of(db)
        if s is None: continue
        j = final(db)
        if j: out[s] = (j["netWorthAfter"], bool(j.get("terminated", False)), j["day"], db)
    return out

full   = load("reproducibility-package/dbs/gemma-full-*.db")
scripts= load("reproducibility-package/dbs/gemma-scripts-*.db")
bare   = load("reproducibility-package/dbs/gemma-bare-*.db")
r1a    = load("runs/r1a-box/*.db")
print(f"loaded full={len(full)} scripts={len(scripts)} bare={len(bare)} r1a={len(r1a)}")

FAIL = lambda w, t: t or w < 50000     # composite failure (cents)
BANK = lambda w, t: t                  # bankruptcy = termination
def dollars(s, d): return d[s][0] / 100

def boot_ci_mean(diffs, n=10000, seed=1234):
    rng = random.Random(seed); m = len(diffs); acc = []
    for _ in range(n):
        acc.append(sum(diffs[rng.randrange(m)] for _ in range(m)) / m)
    acc.sort(); return acc[int(.025*n)], acc[int(.975*n)]

def boot_ci_riskdiff(pairs, n=10000, seed=99):
    # pairs: list of (a_fail(bool), b_fail(bool)); risk diff = P(a) - P(b)
    rng = random.Random(seed); m = len(pairs); acc = []
    for _ in range(n):
        sa = sb = 0
        for _ in range(m):
            a, b = pairs[rng.randrange(m)]
            sa += a; sb += b
        acc.append((sa - sb) / m)
    acc.sort(); return acc[int(.025*n)], acc[int(.975*n)]

def quant(xs, q):
    xs = sorted(xs); i = min(len(xs)-1, int(q*len(xs))); return xs[i]

# ============ 1. R1a vs full — full statistical treatment (#3) ============
print("\n===== 1. R1a (gates removed) vs full — paired, N common =====")
com = sorted(set(full) & set(r1a))
fa = [dollars(s, full) for s in com]; ra = [dollars(s, r1a) for s in com]
d  = [ra[i] - fa[i] for i in range(len(com))]           # R1a - full
print(f"  n={len(com)}")
print(f"  full  mean=${S.mean(fa):.0f} median=${S.median(fa):.0f}")
print(f"  R1a   mean=${S.mean(ra):.0f} median=${S.median(ra):.0f}")
print(f"  paired diff (R1a-full): mean=${S.mean(d):+.0f} median=${S.median(d):+.0f}")
print(f"  bootstrap 95% CI on mean diff: [{boot_ci_mean(d)[0]:+.0f}, {boot_ci_mean(d)[1]:+.0f}]")
print(f"  diff quantiles p10/p25/p50/p75/p90: "
      f"{quant(d,.1):+.0f}/{quant(d,.25):+.0f}/{quant(d,.5):+.0f}/{quant(d,.75):+.0f}/{quant(d,.9):+.0f}")
# binary risk difference (composite failure)
pairs = [(FAIL(*r1a[s][:2]), FAIL(*full[s][:2])) for s in com]
rd = sum(a for a, b in pairs)/len(pairs) - sum(b for a, b in pairs)/len(pairs)
print(f"  composite-failure risk diff (R1a-full): {100*rd:+.1f} pp  CI [{100*boot_ci_riskdiff(pairs)[0]:+.1f}, {100*boot_ci_riskdiff(pairs)[1]:+.1f}] pp")

# ============ 2. Transition table scripts -> full (#8) ============
print("\n===== 2. Transition table scripts -> full (dead/broke/thriving) =====")
def cls(s, d):
    w, t = d[s][:2]
    if t: return 'dead'
    if w < 50000: return 'broke'
    return 'thriving'
com2 = sorted(set(full) & set(scripts))
cats = ['dead', 'broke', 'thriving']
tab = {a: {b: 0 for b in cats} for a in cats}
for s in com2:
    tab[cls(s, scripts)][cls(s, full)] += 1
print(f"  n={len(com2)}   rows=scripts, cols=full")
print("            full:dead  full:broke  full:thriving   (row total)")
for a in cats:
    row = tab[a]; print(f"  scripts:{a:9s} {row['dead']:5d}      {row['broke']:5d}       {row['thriving']:5d}         ({sum(row.values())})")

# ============ 3. Bankruptcy risk diff full vs scripts w/ CI (#9) ============
print("\n===== 3. Bankruptcy (termination) full vs scripts — risk diff + CI =====")
pb = [(BANK(*full[s][:2]), BANK(*scripts[s][:2])) for s in com2]
rdb = sum(a for a,b in pb)/len(pb) - sum(b for a,b in pb)/len(pb)
lo, hi = boot_ci_riskdiff(pb, seed=7)
print(f"  full bank {100*sum(a for a,b in pb)/len(pb):.0f}%  scripts bank {100*sum(b for a,b in pb)/len(pb):.0f}%")
print(f"  risk diff (full-scripts): {100*rdb:+.1f} pp   CI [{100*lo:+.1f}, {100*hi:+.1f}] pp")

# ============ 4. Threshold risk differences (pp) with CIs (#: stats presentation) ============
print("\n===== 4. Threshold sweep: full-vs-scripts risk difference (pp) + CI =====")
def failat(s, d, thr):
    w, t = d[s][:2]; return t or w < thr
for thr, label in [(0,'terminated'),(25000,'<$250'),(50000,'<$500'),(100000,'<$1,000'),(200000,'<$2,000')]:
    pr = [(failat(s, full, thr), failat(s, scripts, thr)) for s in com2]
    rdp = sum(a for a,b in pr)/len(pr) - sum(b for a,b in pr)/len(pr)  # full - scripts (negative = full better)
    lo, hi = boot_ci_riskdiff(pr, seed=thr+3)
    print(f"  {label:12s} full {100*sum(a for a,b in pr)/len(pr):3.0f}%  scripts {100*sum(b for a,b in pr)/len(pr):3.0f}%  "
          f"advantage {-100*rdp:+5.1f} pp  CI [{-100*hi:+.1f}, {-100*lo:+.1f}]")

# ============ 5. Margin metric feasibility (#7): realized $/unit; COGS presence ============
print("\n===== 5. Margin-metric feasibility =====")
def realized_price_per_unit(db):
    c = sqlite3.connect(db); rev = 0.0; u = 0
    for (rj,) in c.execute("SELECT report_json FROM day_report"):
        j = json.loads(rj); rev += j.get("revenue", 0)
        us = j.get("unitsSold", 0)
        u += sum(v for v in us.values() if isinstance(v,(int,float))) if isinstance(us, dict) else (us or 0)
    # is there any cost-basis table populated?
    cogs_rows = 0
    for t in ('orders','price_book','quotes'):
        try: cogs_rows += c.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
        except Exception: pass
    c.close()
    return (rev/100, u, (rev/u/100 if u else None), cogs_rows)
for name, d in [('full', full), ('scripts', scripts)]:
    ppu = []; cogs = 0
    for s in list(d)[:100]:
        rev, u, p, cr = realized_price_per_unit(d[s][3])
        if p: ppu.append(p)
        cogs += cr
    print(f"  {name}: realized $/unit mean=${S.mean(ppu):.3f} median=${S.median(ppu):.3f} (n={len(ppu)})   "
          f"orders+pricebook+quotes rows across arm = {cogs}")

# ============ 6. §5.3 reconciliation: ONE population, ONE window ============
# Population: co-survivors (both full & scripts reach day 200, not terminated).
# Window: days 101-200 (back half). Per seed: revenue/day, units/day, price/unit.
print("\n===== 6. §5.3 mechanism, reconciled (co-survivors, days 101-200) =====")
def window_stats(db, d0=101, d1=200):
    c = sqlite3.connect(db); rev = 0.0; u = 0; ndays = 0
    for (day, rj) in c.execute("SELECT day, report_json FROM day_report"):
        if day < d0 or day > d1: continue
        j = json.loads(rj); rev += j.get("revenue", 0); ndays += 1
        us = j.get("unitsSold", 0)
        u += sum(v for v in us.values() if isinstance(v,(int,float))) if isinstance(us, dict) else (us or 0)
    c.close()
    return rev/100, u, ndays
def survived_200(s, d):
    w, t, day, db = d[s]; return (not t) and day >= 200
com3 = sorted(set(full) & set(scripts))
cosurv = [s for s in com3 if survived_200(s, full) and survived_200(s, scripts)]
print(f"  co-survivors (both reach day 200): n={len(cosurv)}")
rows = {'full': [], 'scripts': []}
for arm, d in [('full', full), ('scripts', scripts)]:
    for s in cosurv:
        rev, u, nd = window_stats(d[s][3])
        if nd == 0 or u == 0: continue
        rows[arm].append((s, rev/nd, u/nd, rev/u))   # rev/day, units/day, price/unit
# align on seeds present in both
seeds_f = {s for s,_,_,_ in rows['full']}; seeds_s = {s for s,_,_,_ in rows['scripts']}
pop = sorted(seeds_f & seeds_s)
fmap = {s:(rd,ud,pu) for s,rd,ud,pu in rows['full']}
smap = {s:(rd,ud,pu) for s,rd,ud,pu in rows['scripts']}
print(f"  usable paired seeds (both nonzero units in window): n={len(pop)}")
def col(mapp, i): return [mapp[s][i] for s in pop]
for i, name, unit in [(0,'revenue/day','$'),(1,'units/day',''),(2,'price/unit','$')]:
    f = col(fmap, i); s = col(smap, i); dpair = [f[k]-s[k] for k in range(len(pop))]
    lo, hi = boot_ci_mean(dpair, seed=100+i)
    print(f"  {name:14s}: scripts mean={unit}{S.mean(s):.3f} median={unit}{S.median(s):.3f} | "
          f"full mean={unit}{S.mean(f):.3f} median={unit}{S.median(f):.3f} | "
          f"paired Δ mean={unit}{S.mean(dpair):+.3f} CI[{unit}{lo:+.3f},{unit}{hi:+.3f}]")
# identity check: mean(units/day)*mean(price/unit) vs mean(revenue/day)
for arm, mp in [('full',fmap),('scripts',smap)]:
    ud=S.mean(col(mp,1)); pu=S.mean(col(mp,2)); rd=S.mean(col(mp,0))
    print(f"  identity {arm}: mean(units/day) {ud:.2f} x mean(price/unit) ${pu:.3f} = ${ud*pu:.2f}  vs  mean(revenue/day) ${rd:.2f}")

# ============ 7. R5 vs scripts — net worth paired (missing test) ============
print("\n===== 7. R5 vs scripts — net worth paired =====")
r5 = load("runs/r5-box/*.db")
com5 = sorted(set(r5) & set(scripts))
r5v = [dollars(s, r5) for s in com5]; scv = [dollars(s, scripts) for s in com5]
dd = [r5v[i]-scv[i] for i in range(len(com5))]
lo, hi = boot_ci_mean(dd, seed=55)
print(f"  n={len(com5)}  R5 mean=${S.mean(r5v):.0f} median=${S.median(r5v):.0f} | scripts mean=${S.mean(scv):.0f} median=${S.median(scv):.0f}")
print(f"  paired Δ (R5-scripts): mean=${S.mean(dd):+.0f} median=${S.median(dd):+.0f}  bootstrap 95% CI [${lo:+.0f}, ${hi:+.0f}]")

# ============ 8. §5.3 per-seed revenue decomposition (price vs volume) ============
# Exact symmetric (Shapley) decomposition per seed:
#   price_effect  = (p_f - p_s) * (q_f + q_s)/2
#   volume_effect = (q_f - q_s) * (p_f + p_s)/2
#   price_effect + volume_effect == p_f*q_f - p_s*q_s  (revenue/day diff), exactly.
print("\n===== 8. §5.3 per-seed revenue decomposition (co-survivors, days 101-200) =====")
pe = []; ve = []; dr = []
for s in pop:
    q_f, p_f = fmap[s][1], fmap[s][2]
    q_s, p_s = smap[s][1], smap[s][2]
    price_e  = (p_f - p_s) * (q_f + q_s)/2
    vol_e    = (q_f - q_s) * (p_f + p_s)/2
    pe.append(price_e); ve.append(vol_e); dr.append(p_f*q_f - p_s*q_s)
lo_p, hi_p = boot_ci_mean(pe, seed=201)
lo_v, hi_v = boot_ci_mean(ve, seed=202)
lo_r, hi_r = boot_ci_mean(dr, seed=203)
print(f"  n={len(pop)}")
print(f"  price effect  /day: mean=${S.mean(pe):+.2f}  CI[{lo_p:+.2f},{hi_p:+.2f}]")
print(f"  volume effect /day: mean=${S.mean(ve):+.2f}  CI[{lo_v:+.2f},{hi_v:+.2f}]")
print(f"  sum (=Δrevenue/day): mean=${S.mean([pe[i]+ve[i] for i in range(len(pe))]):+.2f}")
print(f"  Δrevenue/day direct: mean=${S.mean(dr):+.2f}  CI[{lo_r:+.2f},{hi_r:+.2f}]  (should match sum)")
