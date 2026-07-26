import sqlite3, glob, json, collections
DBS="dbs"
print("=== GATE DECISIONS (allowed vs denied) — #4 ===")
for prefix in ["gemma-full","gemma-scripts"]:
    gates=collections.defaultdict(lambda:[0,0])
    for db in glob.glob(f"{DBS}/{prefix}-*.db"):
        c=sqlite3.connect(db)
        for gate_id,dj in c.execute("SELECT gate_id,decision_json FROM gate_log"):
            try: d=json.loads(dj)
            except Exception: continue
            if d.get("allowed",True): gates[gate_id][0]+=1
            else: gates[gate_id][1]+=1
        c.close()
    print(prefix); ta=td=0
    for g,(a,dn) in sorted(gates.items()):
        ta+=a; td+=dn
        print(f"  {g:16} allowed={a:7} denied={dn:5}  reject={100*dn/max(a+dn,1):4.1f}%")
    print(f"  {'OVERALL':16} allowed={ta:7} denied={td:5}  reject={100*td/max(ta+td,1):4.1f}%")
print("\n=== BARE applied_json samples (parsed_ok=1) ===")
db=glob.glob(f"{DBS}/gemma-bare-*.db")[0]; c=sqlite3.connect(db)
for day,aj in c.execute("SELECT day,applied_json FROM baseline_action WHERE parsed_ok=1 AND applied_json IS NOT NULL LIMIT 4"):
    print(f"  day {day}: {(aj or '')[:400]}")
c.close()
print("\n=== BARE inventory_ledger (aggregate over 20 dbs): does it restock? ===")
sk=collections.Counter(); pos=neg=0
for db in glob.glob(f"{DBS}/gemma-bare-*.db")[:20]:
    c=sqlite3.connect(db)
    for kind,units in c.execute("SELECT source_kind, units FROM inventory_ledger"):
        sk[kind]+=1
        if units>0: pos+=1
        elif units<0: neg+=1
    c.close()
print("  source_kind counts:",dict(sk))
print(f"  inventory adds (units>0)={pos}  removals (units<0)={neg}")
print("\n=== BARE cash_ledger sample ===")
db=glob.glob(f"{DBS}/gemma-bare-*.db")[0]; c=sqlite3.connect(db)
for r in c.execute("SELECT source_kind, COUNT(*) FROM cash_ledger GROUP BY source_kind"): print("  kind:",r)
for r in c.execute("SELECT day, amount_cents, source_kind, memo FROM cash_ledger LIMIT 6"): print("  sample:",r)
c.close()
