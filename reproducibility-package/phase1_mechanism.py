import sqlite3, glob, json, collections, statistics as st
DBS="dbs"
def sc(x):
    if isinstance(x,dict): return sum(v for v in x.values() if isinstance(v,(int,float)))
    return x if isinstance(x,(int,float)) else 0
def traj(prefix):
    us=collections.defaultdict(list); rev=collections.defaultdict(list); nw=collections.defaultdict(list); unpaid=collections.defaultdict(list)
    for db in glob.glob(f"{DBS}/{prefix}-*.db"):
        c=sqlite3.connect(db)
        for (rj,) in c.execute("SELECT report_json FROM day_report"):
            r=json.loads(rj); b=(r["day"]-1)//50
            us[b].append(sc(r.get("unitsSold",0))); rev[b].append(sc(r.get("revenue",0))/100.0)
            nw[b].append(sc(r.get("netWorthAfter",0))/100.0); unpaid[b].append(sc(r.get("consecutiveUnpaidDays",0)))
        c.close()
    return us,rev,nw,unpaid
print("=== TRAJECTORY (mean per day, by 50-day bucket) — does bare lose the thread? ===")
for prefix in ["gemma-bare","gemma-scripts","gemma-full"]:
    us,rev,nw,unpaid=traj(prefix); print(prefix)
    for k in sorted(us):
        print(f"  day {k*50+1:3}-{k*50+50:3}: units/day={st.mean(us[k]):5.1f}  rev/day=${st.mean(rev[k]):6.1f}  netWorth=${st.mean(nw[k]):8.0f}  unpaidDays={st.mean(unpaid[k]):4.1f}  (obs={len(us[k])})")
tot=bad=0
for db in glob.glob(f"{DBS}/gemma-bare-*.db"):
    c=sqlite3.connect(db)
    for (ok,) in c.execute("SELECT parsed_ok FROM baseline_action"): tot+=1; bad+=(0 if ok else 1)
    c.close()
print(f"\n=== BARE parse failures: {bad}/{tot} actions unparseable ({100*bad/max(tot,1):.1f}%) ===")
calls=[]; toks=[]; nonok=tot2=0
for db in glob.glob(f"{DBS}/gemma-full-*.db"):
    c=sqlite3.connect(db); perday=collections.Counter()
    for day,ti,to,hs in c.execute("SELECT day,tokens_in,tokens_out,http_status FROM llm_calls"):
        perday[day]+=1; toks.append((ti or 0)+(to or 0)); tot2+=1; nonok+=(0 if hs==200 else 1)
    calls.extend(perday.values()); c.close()
if calls: print(f"\n=== FULL llm efficiency: {st.mean(calls):.1f} calls/sim-day (max {max(calls)}), {st.mean(toks):.0f} tokens/call, http-non200 {nonok}/{tot2} ===")
db=glob.glob(f"{DBS}/gemma-full-*.db")[0]; c=sqlite3.connect(db)
print("\n=== gate_log gate_id counts (one full run) ===")
for r in c.execute("SELECT gate_id, COUNT(*) FROM gate_log GROUP BY gate_id ORDER BY 2 DESC"): print("  ",r[0],r[1])
print("=== decision_json samples ===")
for (dj,) in c.execute("SELECT decision_json FROM gate_log LIMIT 3"): print("  ",dj[:200])
print("=== cash_ledger source_kind ===")
for r in c.execute("SELECT source_kind, COUNT(*) FROM cash_ledger GROUP BY source_kind"): print("  ",r)
c.close()
print("\n=== orders/suppliers/price_book population (first 20 dbs/arm) ===")
for prefix in ["gemma-full","gemma-scripts","gemma-bare"]:
    o=s=pb=n=0
    for db in glob.glob(f"{DBS}/{prefix}-*.db")[:20]:
        c=sqlite3.connect(db); n+=1
        try:
            o+=c.execute("SELECT COUNT(*) FROM orders").fetchone()[0]
            s+=c.execute("SELECT COUNT(*) FROM suppliers").fetchone()[0]
            pb+=c.execute("SELECT COUNT(*) FROM price_book").fetchone()[0]
        except Exception: pass
        c.close()
    print(f"  {prefix}: over {n} dbs — orders={o} suppliers={s} price_book={pb}")
