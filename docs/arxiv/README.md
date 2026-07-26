# arXiv submission package

This folder is a self-contained LaTeX build of the paper
*"Ablating Persistent Structured State Collapses a Weak-Model Scaffolding
Harness: A Seed-Matched Component Study."* It is generated from
`docs/paper-research-format.md` but is a **separate artifact** — editing here
does not touch the source markdown, and vice versa.

## Contents

```
main.tex            the paper (article class, self-contained, inline bibliography)
figures/            fig1–fig7 as PDF (converted from reproducibility-package/figures/*.svg)
README.md           this file
```

## Before you submit — two things to fill in

1. **Author block.** `main.tex` has a placeholder:
   `\author{[Author name --- fill in]\\ \texttt{[affiliation / contact --- fill in]}}`
   arXiv requires a named author. Put your name (and affiliation/email if you
   want it public) there.
2. **Reproducibility pointer.** The paper refers to a "reproducibility package"
   (Appendix A) — the scripts and databases in `reproducibility-package/`. arXiv
   is not the place for multi-hundred-MB SQLite files. Host the package on
   GitHub or Zenodo and either (a) add the URL to Appendix A, or (b) attach the
   scripts (not the raw DBs) as arXiv *ancillary files*. A Zenodo DOI is the
   cleanest and gives you something citable.

## Build locally

Needs a TeX distribution (TeX Live / MacTeX). No bibtex step — the bibliography
is inline (`thebibliography`), so a plain double run is enough:

```bash
cd docs/arxiv
pdflatex main.tex
pdflatex main.tex      # second pass resolves \ref cross-references
```

If you don't have TeX installed, the same build runs in Docker:

```bash
cd docs/arxiv
docker run --rm -v "$PWD":/w -w /w texlive/texlive:latest \
  sh -c "pdflatex main.tex && pdflatex main.tex"
```

## Submitting to arXiv

- **Category.** Primary `cs.AI`; cross-list `cs.LG` (and optionally `cs.MA`
  for multi-agent). It is agent-evaluation / scaffolding work.
- **Upload the source, not a PDF.** arXiv prefers to compile your LaTeX. Upload
  `main.tex` plus the `figures/` folder (the seven PDFs). No `.bib` is needed.
  If you add ancillary code, put it in a folder named `anc/`.
- **Figures are vector PDF**, so they scale cleanly in arXiv's compiled output.
- **License.** Pick one at submission time; CC BY 4.0 is the usual choice for
  wanting it read and cited.
- **First submission** goes through a short moderation hold before it's
  announced; that's normal.

## Regenerating the figures (if the underlying numbers ever change)

The PDFs here were converted from the canonical SVGs, with one exception noted
below:

```bash
python3 reproducibility-package/figures.py          # writes the 7 SVGs
for f in reproducibility-package/figures/*.svg; do
  rsvg-convert -f pdf -o "docs/arxiv/figures/$(basename "$f" .svg).pdf" "$f"
done
```

**Exception — the transition heatmap (`fig7_transition.pdf`).** The canonical
`figures.py` draws the heatmap cells with `fill-opacity` (alpha). `rsvg-convert`
encodes that as PDF transparency, which some PDF viewers ignore — the colored
cells then render white and the heatmap looks like a plain table. For the arXiv
build we regenerate this one figure with the opacity *baked into solid colors*
(blended over white), which every viewer renders identically:

```bash
python3 docs/arxiv/make_transition_fig.py           # writes /tmp/fig7_transition_solid.svg
rsvg-convert -f pdf -o docs/arxiv/figures/fig7_transition.pdf /tmp/fig7_transition_solid.svg
```

`make_transition_fig.py` recomputes the 3×3 matrix from the archived databases,
so it stays consistent with the paper; it only changes how the colors are
encoded, not the content.

Keep the figure numbering (fig1–fig7 by filename) stable so `main.tex`'s
`\includegraphics` calls keep resolving. Note: in the compiled PDF the
transition heatmap is **Figure 2** (figures are numbered by order of appearance,
not by filename).
