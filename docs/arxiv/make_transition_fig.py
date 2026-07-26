#!/usr/bin/env python3
"""Regenerate the transition-matrix figure for the arXiv build with SOLID,
pre-blended cell colors (no fill-opacity). rsvg-convert encodes fill-opacity as
PDF alpha transparency, which some PDF viewers ignore — making the heatmap look
like a plain table. Baking the opacity into a solid RGB (blended over white)
renders identically everywhere.

Recomputes the 3x3 scripts->full outcome matrix from the archived DBs so the
figure stays honest. Writes an SVG to /tmp; the caller converts it to
docs/arxiv/figures/fig7_transition.pdf with rsvg-convert.

Run from the repo root:  python3 docs/arxiv/make_transition_fig.py
"""
import sqlite3, glob, os, re, json

DBS = {'scripts': 'reproducibility-package/dbs/gemma-scripts-*.db',
       'full':    'reproducibility-package/dbs/gemma-full-*.db'}
W, H = 660, 420

def final_by_seed(pat):
    out = {}
    for db in glob.glob(pat):
        m = re.search(r'(\d+)\.db$', os.path.basename(db))
        if not m:
            continue
        c = sqlite3.connect(db)
        row = c.execute("SELECT report_json FROM day_report ORDER BY day DESC LIMIT 1").fetchone()
        c.close()
        if row:
            j = json.loads(row[0])
            out[int(m.group(1))] = (j["netWorthAfter"] / 100.0, bool(j.get("terminated", False)))
    return out

def cls(w, t):
    if t: return 'dead'
    if w < 500: return 'broke'
    return 'thriving'

def blend(hexc, op):
    """Blend hex color over white at the given opacity -> solid hex."""
    r, g, b = int(hexc[1:3], 16), int(hexc[3:5], 16), int(hexc[5:7], 16)
    r = round(op * r + (1 - op) * 255)
    g = round(op * g + (1 - op) * 255)
    b = round(op * b + (1 - op) * 255)
    return f"#{r:02x}{g:02x}{b:02x}"

def esc(s):
    return str(s).replace('&', '&amp;').replace('<', '&lt;')

def build():
    Sd = final_by_seed(DBS['scripts'])
    Fd = final_by_seed(DBS['full'])
    cats = ['dead', 'broke', 'thriving']
    idx = {c: i for i, c in enumerate(cats)}
    com = sorted(set(Sd) & set(Fd))
    M = [[0] * 3 for _ in range(3)]
    for s in com:
        M[idx[cls(*Sd[s])]][idx[cls(*Fd[s])]] += 1
    mx = max(max(r) for r in M)
    rtot = [sum(r) for r in M]
    ctot = [sum(M[r][c] for r in range(3)) for c in range(3)]
    cell = 88; gx0 = 232; gy0 = 92
    IMP = '#2f7d6b'; REG = '#b23b3b'; SAME = '#6b7280'
    p = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" font-family="Helvetica,Arial,sans-serif" font-size="12">',
         f'<rect width="{W}" height="{H}" fill="white"/>',
         f'<text x="{W/2}" y="20" text-anchor="middle" font-size="14" font-weight="600">Outcome transition: scripts &#8594; full (N=100 matched seeds)</text>',
         f'<text x="{W/2}" y="38" text-anchor="middle" font-size="11" fill="#666">cell = seeds; green = improved, red = regressed, grey = unchanged</text>',
         f'<text x="{gx0+cell*1.5:.0f}" y="{gy0-30:.0f}" text-anchor="middle" font-size="12" font-weight="600">outcome under full &#8594;</text>']
    for c, cat in enumerate(cats):
        p.append(f'<text x="{gx0+cell*(c+0.5):.0f}" y="{gy0-10:.0f}" text-anchor="middle" font-weight="600">{cat}</text>')
    p.append(f'<text x="{gx0-58:.0f}" y="{gy0+cell*1.5:.0f}" text-anchor="middle" font-size="12" font-weight="600" transform="rotate(-90 {gx0-58:.0f} {gy0+cell*1.5:.0f})">under scripts &#8594;</text>')
    for r, cat in enumerate(cats):
        p.append(f'<text x="{gx0-12:.0f}" y="{gy0+cell*(r+0.5)+4:.0f}" text-anchor="end" font-weight="600">{cat}</text>')
    for r in range(3):
        for c in range(3):
            v = M[r][c]
            base = SAME if r == c else (IMP if c > r else REG)
            op = 0.0 if v == 0 else 0.18 + 0.82 * (v / mx)
            fill = "#ffffff" if v == 0 else blend(base, op)
            x = gx0 + cell * c; y = gy0 + cell * r
            p.append(f'<rect x="{x}" y="{y}" width="{cell}" height="{cell}" fill="{fill}" stroke="#cccccc"/>')
            if v:
                txtcol = "#ffffff" if op > 0.55 else "#222222"
                p.append(f'<text x="{x+cell/2:.0f}" y="{y+cell/2+7:.0f}" text-anchor="middle" font-size="20" font-weight="700" fill="{txtcol}">{v}</text>')
    for r in range(3):
        p.append(f'<text x="{gx0+cell*3+16:.0f}" y="{gy0+cell*(r+0.5)+4:.0f}" text-anchor="start" fill="#555555">{rtot[r]}</text>')
    for c in range(3):
        p.append(f'<text x="{gx0+cell*(c+0.5):.0f}" y="{gy0+cell*3+20:.0f}" text-anchor="middle" fill="#555555">{ctot[c]}</text>')
    p.append(f'<text x="{gx0+cell*3+16:.0f}" y="{gy0-10:.0f}" text-anchor="start" font-size="10" fill="#888888">row &#931;</text>')
    p.append(f'<text x="{gx0-12:.0f}" y="{gy0+cell*3+20:.0f}" text-anchor="end" font-size="10" fill="#888888">col &#931;</text>')
    p.append('</svg>')
    return "\n".join(p), M

if __name__ == "__main__":
    svg, M = build()
    with open("/tmp/fig7_transition_solid.svg", "w") as f:
        f.write(svg)
    print("matrix rows=scripts cols=full:", M)
    print("wrote /tmp/fig7_transition_solid.svg")
