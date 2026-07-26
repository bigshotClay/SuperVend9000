#!/usr/bin/env python3
"""figures.py — generate paper figures as dependency-free SVG from the archived DBs.
Outputs to reproducibility-package/figures/. Stdlib only."""
import sqlite3, glob, os, json, re, math, statistics as st
from collections import defaultdict

OUT = "reproducibility-package/figures"
os.makedirs(OUT, exist_ok=True)
COL = {'bare':'#b23b3b','scripts':'#a8722a','full':'#2f7d6b','R1a':'#5b6472','R1b':'#c25a38'}
DBS = {'bare':'reproducibility-package/dbs/gemma-bare-*.db',
       'scripts':'reproducibility-package/dbs/gemma-scripts-*.db',
       'full':'reproducibility-package/dbs/gemma-full-*.db',
       'R1a':'runs/r1a-box/*.db','R1b':'runs/r1b-box/*.db'}

def rows(db):
    c=sqlite3.connect(db); out=[json.loads(r[0]) for r in c.execute("SELECT report_json FROM day_report ORDER BY day")]; c.close(); return out
def final_pairs(pat):   # (net worth $, terminated)
    out=[]
    for db in glob.glob(pat):
        rs=rows(db)
        if rs: out.append((rs[-1]["netWorthAfter"]/100.0, bool(rs[-1].get("terminated",False))))
    return out
def daily_median(pat, maxday=200):
    byday=defaultdict(list)
    for db in glob.glob(pat):
        for j in rows(db):
            if j["day"]<=maxday: byday[j["day"]].append(j["netWorthAfter"]/100.0)
    return {d: st.median(v) for d,v in sorted(byday.items())}

# ---- SVG canvas ----
W,H=660,420; L,R,T,B=74,24,30,56
PX0,PX1,PY0,PY1=L,W-R,T,H-B
def sx(v,a,b): return PX0+(v-a)/(b-a)*(PX1-PX0)
def sy(v,a,b): return PY1-(v-a)/(b-a)*(PY1-PY0)
def esc(s): return str(s).replace('&','&amp;').replace('<','&lt;')
def nice_ticks(lo,hi,n=5):
    span=hi-lo
    if span<=0: return [(lo,f"{lo:.0f}")]
    step=span/n; mag=10**math.floor(math.log10(step))
    for m in (1,2,2.5,5,10):
        if step<=m*mag: step=m*mag; break
    out=[]; v=math.ceil(lo/step)*step
    while v<=hi+1e-9: out.append((v,f"{v:.0f}")); v+=step
    return out
def polyline(pts,col,a,b,c,d,w=2.2):
    s=" ".join(f"{sx(x,a,b):.1f},{sy(y,c,d):.1f}" for x,y in pts)
    return f'<polyline points="{s}" fill="none" stroke="{col}" stroke-width="{w}"/>'
def frame(title,xlab,ylab,xt,yt,a,b,c,d,body,legend):
    p=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" font-family="system-ui,Arial,sans-serif" font-size="12">',
       f'<rect width="{W}" height="{H}" fill="white"/>',
       f'<text x="{W/2}" y="19" text-anchor="middle" font-size="14" font-weight="600">{esc(title)}</text>',
       f'<line x1="{PX0}" y1="{PY1}" x2="{PX1}" y2="{PY1}" stroke="#333"/>',
       f'<line x1="{PX0}" y1="{PY0}" x2="{PX0}" y2="{PY1}" stroke="#333"/>']
    for xv,xl in xt:
        X=sx(xv,a,b); p.append(f'<line x1="{X:.1f}" y1="{PY1}" x2="{X:.1f}" y2="{PY1+5}" stroke="#333"/>')
        p.append(f'<text x="{X:.1f}" y="{PY1+18}" text-anchor="middle" fill="#333">{esc(xl)}</text>')
    for yv,yl in yt:
        Y=sy(yv,c,d); p.append(f'<line x1="{PX0-5}" y1="{Y:.1f}" x2="{PX0}" y2="{Y:.1f}" stroke="#333"/>')
        p.append(f'<line x1="{PX0}" y1="{Y:.1f}" x2="{PX1}" y2="{Y:.1f}" stroke="#eee"/>')
        p.append(f'<text x="{PX0-9}" y="{Y+4:.1f}" text-anchor="end" fill="#333">{esc(yl)}</text>')
    p.append(f'<text x="{(PX0+PX1)/2}" y="{H-8}" text-anchor="middle">{esc(xlab)}</text>')
    p.append(f'<text x="16" y="{(PY0+PY1)/2}" text-anchor="middle" transform="rotate(-90 16 {(PY0+PY1)/2})">{esc(ylab)}</text>')
    p.append(body)
    if legend:
        lx=PX1-128; ly=PY0+6; bh=len(legend)*17+8
        p.append(f'<rect x="{lx-6}" y="{ly-6}" width="128" height="{bh}" fill="white" stroke="#ddd"/>')
        for name,col in legend:
            p.append(f'<rect x="{lx}" y="{ly-2}" width="12" height="12" fill="{col}"/>')
            p.append(f'<text x="{lx+18}" y="{ly+8}" fill="#333">{esc(name)}</text>'); ly+=17
    p.append('</svg>'); return "\n".join(p)
def write(name,svg):
    with open(os.path.join(OUT,name),"w") as f: f.write(svg)
    print("wrote", os.path.join(OUT,name))

# ---- FIG 1: ECDF of final net worth ----
def fig_ecdf():
    a,b,c,d=-300,4500,0,1; body=[]; leg=[]
    for arm in ('bare','scripts','full'):
        v=sorted(w for w,_ in final_pairs(DBS[arm])); n=len(v)
        pts=[(min(b,max(a,v[i])),(i+1)/n) for i in range(n)]
        body.append(polyline(pts,COL[arm],a,b,c,d)); leg.append((arm,COL[arm]))
    xt=[(-300,'-300'),(0,'0'),(500,'500'),(1500,'1500'),(2500,'2500'),(4500,'4500+')]
    yt=[(0,'0'),(.25,'.25'),(.5,'.5'),(.75,'.75'),(1,'1.0')]
    return frame("Final net worth — empirical CDF (N=100/arm)","net worth ($)","cumulative fraction of runs",xt,yt,a,b,c,d,"\n".join(body),leg)

# ---- FIG 2: failure rate vs success threshold ----
def fig_threshold():
    ths=[(0,'bankrupt'),(250,'250'),(500,'500'),(1000,'1000'),(2000,'2000'),(3000,'3000')]
    a,b,c,d=0,3000,0,100; body=[]; leg=[]
    for arm in ('bare','scripts','full'):
        data=final_pairs(DBS[arm]); pts=[]
        for thv,_ in ths:
            fail=sum(1 for w,t in data if t or (thv>0 and w<thv))
            pts.append((thv,100*fail/len(data)))
        body.append(polyline(pts,COL[arm],a,b,c,d))
        for x,y in pts: body.append(f'<circle cx="{sx(x,a,b):.1f}" cy="{sy(y,c,d):.1f}" r="3" fill="{COL[arm]}"/>')
        leg.append((arm,COL[arm]))
    xt=list(ths); yt=[(0,'0'),(25,'25'),(50,'50'),(75,'75'),(100,'100')]
    return frame("Failure rate vs success bar","success bar: final net worth < ($)","failure rate (%)",xt,yt,a,b,c,d,"\n".join(body),leg)

# ---- FIG 3: median net worth trajectory ----
def fig_traj():
    a,b=0,200; series={arm:daily_median(DBS[arm]) for arm in ('bare','scripts','full')}
    hi=max(max(s.values()) for s in series.values()); lo=min(0,min(min(s.values()) for s in series.values()))
    c,d=lo,hi*1.08; body=[]; leg=[]
    for arm in ('bare','scripts','full'):
        body.append(polyline(sorted(series[arm].items()),COL[arm],a,b,c,d)); leg.append((arm,COL[arm]))
    xt=[(0,'0'),(50,'50'),(100,'100'),(150,'150'),(200,'200')]
    return frame("Median net worth over time","simulated day","median net worth ($)",xt,nice_ticks(c,d),a,b,c,d,"\n".join(body),leg)

# ---- FIG 4: component ablation (median net worth by arm) ----
def fig_diss():
    order=['bare','R1b','scripts','R1a','full']
    meds={}; fails={}
    for arm in order:
        data=final_pairs(DBS[arm]); v=[w for w,_ in data]
        meds[arm]=st.median(v); fails[arm]=100*sum(1 for w,t in data if t or w<500)/len(data)
    hi=max(meds.values()); lo=min(0,min(meds.values()))
    c,d=lo-60,hi*1.12; a,b=0,len(order)
    body=[f'<line x1="{PX0}" y1="{sy(0,c,d):.1f}" x2="{PX1}" y2="{sy(0,c,d):.1f}" stroke="#999" stroke-dasharray="3 3"/>']
    bw=(PX1-PX0)/len(order)*0.6
    for i,arm in enumerate(order):
        cx=sx(i+0.5,a,b); y=meds[arm]; top=sy(max(y,0),c,d); bot=sy(min(y,0),c,d)
        body.append(f'<rect x="{cx-bw/2:.1f}" y="{top:.1f}" width="{bw:.1f}" height="{abs(bot-top):.1f}" fill="{COL[arm]}"/>')
        body.append(f'<text x="{cx:.1f}" y="{(top-6) if y>=0 else (bot+14):.1f}" text-anchor="middle" font-size="11">${y:.0f}</text>')
        body.append(f'<text x="{cx:.1f}" y="{PY1+18:.1f}" text-anchor="middle" font-weight="600">{arm}</text>')
        body.append(f'<text x="{cx:.1f}" y="{PY1+32:.1f}" text-anchor="middle" fill="#777" font-size="10">{fails[arm]:.0f}% fail</text>')
    yt=nice_ticks(c,d)
    p=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" font-family="system-ui,Arial,sans-serif" font-size="12">',
       f'<rect width="{W}" height="{H}" fill="white"/>',
       f'<text x="{W/2}" y="19" text-anchor="middle" font-size="14" font-weight="600">Component ablation — median net worth (N=100/arm)</text>',
       f'<line x1="{PX0}" y1="{PY0}" x2="{PX0}" y2="{PY1}" stroke="#333"/>']
    for yv,yl in yt:
        Y=sy(yv,c,d); p.append(f'<line x1="{PX0-5}" y1="{Y:.1f}" x2="{PX0}" y2="{Y:.1f}" stroke="#333"/>')
        p.append(f'<text x="{PX0-9}" y="{Y+4:.1f}" text-anchor="end" fill="#333">{esc(yl)}</text>')
    p.append(f'<text x="16" y="{(PY0+PY1)/2}" text-anchor="middle" transform="rotate(-90 16 {(PY0+PY1)/2})">median net worth ($)</text>')
    p.append("\n".join(body)); p.append('</svg>'); return "\n".join(p)

def _bars(title, ylab, order, colmap, valfn, subfn):
    vals={n:valfn(pat) for n,pat in order}; subs={n:subfn(pat) for n,pat in order}
    hi=max(vals.values()); lo=min(0,min(vals.values())); c,d=lo-(abs(lo) if lo<0 else 0)-40,hi*1.15; a,b=0,len(order)
    bw=(PX1-PX0)/len(order)*0.55; body=[f'<line x1="{PX0}" y1="{sy(0,c,d):.1f}" x2="{PX1}" y2="{sy(0,c,d):.1f}" stroke="#999" stroke-dasharray="3 3"/>']
    for i,(n,_) in enumerate(order):
        cx=sx(i+0.5,a,b); y=vals[n]; top=sy(max(y,0),c,d); bot=sy(min(y,0),c,d)
        body.append(f'<rect x="{cx-bw/2:.1f}" y="{top:.1f}" width="{bw:.1f}" height="{abs(bot-top):.1f}" fill="{colmap[n]}"/>')
        body.append(f'<text x="{cx:.1f}" y="{(top-6) if y>=0 else (bot+14):.1f}" text-anchor="middle" font-size="12">${y:.0f}</text>')
        body.append(f'<text x="{cx:.1f}" y="{PY1+18:.1f}" text-anchor="middle" font-weight="600" font-size="11.5">{esc(n)}</text>')
        body.append(f'<text x="{cx:.1f}" y="{PY1+32:.1f}" text-anchor="middle" fill="#777" font-size="10">{esc(subs[n])}</text>')
    p=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" font-family="system-ui,Arial,sans-serif" font-size="12">',
       f'<rect width="{W}" height="{H}" fill="white"/>',
       f'<text x="{W/2}" y="19" text-anchor="middle" font-size="13.5" font-weight="600">{esc(title)}</text>',
       f'<line x1="{PX0}" y1="{PY0}" x2="{PX0}" y2="{PY1}" stroke="#333"/>']
    for yv,yl in nice_ticks(c,d):
        Y=sy(yv,c,d); p.append(f'<line x1="{PX0-5}" y1="{Y:.1f}" x2="{PX0}" y2="{Y:.1f}" stroke="#333"/>')
        p.append(f'<text x="{PX0-9}" y="{Y+4:.1f}" text-anchor="end" fill="#333">{esc(yl)}</text>')
    p.append(f'<text x="16" y="{(PY0+PY1)/2}" text-anchor="middle" transform="rotate(-90 16 {(PY0+PY1)/2})">{esc(ylab)}</text>')
    p.append("\n".join(body)); p.append('</svg>'); return "\n".join(p)

def _mean(pat):
    import statistics as S; return S.mean([w for w,_ in final_pairs(pat)])
def _failsub(pat):
    d=final_pairs(pat); return f"{100*sum(1 for w,t in d if t or w<500)/len(d):.0f}% fail"

def fig_seat():
    order=[('full',DBS['full']),('R5',"runs/r5-box/*.db"),('scripts',DBS['scripts'])]
    col={'full':'#2f7d6b','R5':'#b4622a','scripts':'#a8722a'}
    return _bars("Seat ablation — scripting the pricing seat (R5) collapses toward scripts on both axes",
                 "mean net worth ($)", order, col, _mean, _failsub)

def fig_capability():
    order=[('gemma3:4b','reproducibility-package/dbs/gemma-bare-*.db'),
           ('qwen2.5:7b','reproducibility-package/dbs/qwen-bare-*.db'),
           ('deepseek-v4-flash','reproducibility-package/dbs/deepseek-bare-*.db')]
    col={'gemma3:4b':'#b23b3b','qwen2.5:7b':'#a8722a','deepseek-v4-flash':'#2f7d6b'}
    return _bars("Capability gradient — bare-arm net worth by model tier (exploratory)",
                 "bare mean net worth ($)", order, col, _mean, _failsub)

# ---- FIG 7: scripts -> full outcome transition matrix ----
def _final_by_seed(pat):
    out={}
    for db in glob.glob(pat):
        m=re.search(r'(\d+)\.db$', os.path.basename(db))
        if not m: continue
        rs=rows(db)
        if rs: out[int(m.group(1))]=(rs[-1]["netWorthAfter"]/100.0, bool(rs[-1].get("terminated",False)))
    return out
def _cls(w,t):
    if t: return 'dead'
    if w<500: return 'broke'
    return 'thriving'
def fig_transition():
    Sd=_final_by_seed(DBS['scripts']); Fd=_final_by_seed(DBS['full'])
    cats=['dead','broke','thriving']; idx={c:i for i,c in enumerate(cats)}
    com=sorted(set(Sd)&set(Fd))
    M=[[0]*3 for _ in range(3)]
    for s in com: M[idx[_cls(*Sd[s])]][idx[_cls(*Fd[s])]]+=1
    mx=max(max(r) for r in M)
    rtot=[sum(r) for r in M]; ctot=[sum(M[r][c] for r in range(3)) for c in range(3)]
    cell=88; gx0=232; gy0=92
    IMP='#2f7d6b'; REG='#b23b3b'; SAME='#6b7280'
    p=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" font-family="system-ui,Arial,sans-serif" font-size="12">',
       f'<rect width="{W}" height="{H}" fill="white"/>',
       f'<text x="{W/2}" y="20" text-anchor="middle" font-size="14" font-weight="600">Outcome transition: scripts &#8594; full (N=100 matched seeds)</text>',
       f'<text x="{W/2}" y="38" text-anchor="middle" font-size="11" fill="#666">cell = seeds; green = improved, red = regressed, grey = unchanged</text>',
       f'<text x="{gx0+cell*1.5:.0f}" y="{gy0-30:.0f}" text-anchor="middle" font-size="12" font-weight="600">outcome under full &#8594;</text>']
    for c,cat in enumerate(cats):
        p.append(f'<text x="{gx0+cell*(c+0.5):.0f}" y="{gy0-10:.0f}" text-anchor="middle" font-weight="600">{cat}</text>')
    p.append(f'<text x="{gx0-58:.0f}" y="{gy0+cell*1.5:.0f}" text-anchor="middle" font-size="12" font-weight="600" transform="rotate(-90 {gx0-58:.0f} {gy0+cell*1.5:.0f})">under scripts &#8594;</text>')
    for r,cat in enumerate(cats):
        p.append(f'<text x="{gx0-12:.0f}" y="{gy0+cell*(r+0.5)+4:.0f}" text-anchor="end" font-weight="600">{cat}</text>')
    for r in range(3):
        for c in range(3):
            v=M[r][c]; col=SAME if r==c else (IMP if c>r else REG)
            op=0.0 if v==0 else 0.18+0.82*(v/mx)
            x=gx0+cell*c; y=gy0+cell*r
            p.append(f'<rect x="{x}" y="{y}" width="{cell}" height="{cell}" fill="{col}" fill-opacity="{op:.3f}" stroke="#ccc"/>')
            if v: p.append(f'<text x="{x+cell/2:.0f}" y="{y+cell/2+7:.0f}" text-anchor="middle" font-size="20" font-weight="700" fill="{"#fff" if op>0.55 else "#222"}">{v}</text>')
    # row totals (right) and col totals (bottom)
    for r in range(3):
        p.append(f'<text x="{gx0+cell*3+16:.0f}" y="{gy0+cell*(r+0.5)+4:.0f}" text-anchor="start" fill="#555">{rtot[r]}</text>')
    for c in range(3):
        p.append(f'<text x="{gx0+cell*(c+0.5):.0f}" y="{gy0+cell*3+20:.0f}" text-anchor="middle" fill="#555">{ctot[c]}</text>')
    p.append(f'<text x="{gx0+cell*3+16:.0f}" y="{gy0-10:.0f}" text-anchor="start" font-size="10" fill="#888">row &#931;</text>')
    p.append(f'<text x="{gx0-12:.0f}" y="{gy0+cell*3+20:.0f}" text-anchor="end" font-size="10" fill="#888">col &#931;</text>')
    p.append('</svg>'); return "\n".join(p)

write("fig1_ecdf.svg", fig_ecdf())
write("fig2_threshold.svg", fig_threshold())
write("fig3_trajectory.svg", fig_traj())
write("fig4_dissociation.svg", fig_diss())
write("fig5_seat.svg", fig_seat())
write("fig6_capability.svg", fig_capability())
write("fig7_transition.svg", fig_transition())
print("done — 7 figures")
