# Harness Experiment Report

## 1. Harness lift (G2) — naive vs. harnessed net worth

| Model | naive mean | naive min | harness mean | harness min | lift? |
|---|--:|--:|--:|--:|:--:|
| gemma3:4b | $476.00 | $476.00 | $729.00 | $729.00 | ✓ |
| mistral-small:24b | $476.00 | $476.00 | $729.00 | $729.00 | ✓ |
| phi4:14b | $476.00 | $476.00 | $729.00 | $729.00 | ✓ |

## 2. Flat curve (G1) — harness score across models

| Model | mean | min | CV(seeds) |
|---|--:|--:|--:|
| gemma3:4b | $729.00 | $729.00 | 0.0% |
| mistral-small:24b | $729.00 | $729.00 | 0.0% |
| phi4:14b | $729.00 | $729.00 | 0.0% |

**CV(models) = 0.0%, CV(seeds) = 0.0% → FLAT (CV_models < CV_seeds ✓)**

## 3. Failure classes (G3) — realized incidence (target: zeros for harness)

| Config | meltdowns | below-cost sales | unvalidated payments | (blocked below-cost) | (blocked unvalidated) |
|---|--:|--:|--:|--:|--:|
| baseline/gemma3:4b | 0 | — | — | 0 | 0 |
| baseline/mistral-small:24b | 0 | — | — | 0 | 0 |
| baseline/phi4:14b | 0 | — | — | 0 | 0 |
| harness/gemma3:4b | 0 | 0 | 0 | 0 | 0 |
| harness/mistral-small:24b | 0 | 0 | 0 | 0 | 0 |
| harness/phi4:14b | 0 | 0 | 0 | 0 | 0 |

## 4. Efficiency (G5) — per sim-day

| Config | LLM calls/day | tokens/day | gate fires/day | incidents/day |
|---|--:|--:|--:|--:|
| baseline/gemma3:4b | 1.0 | 1232 | 0.0 | 0.00 |
| baseline/mistral-small:24b | 1.0 | 1080 | 0.0 | 0.00 |
| baseline/phi4:14b | 1.0 | 1727 | 0.0 | 0.00 |
| harness/gemma3:4b | 1.0 | 87 | 8.8 | 0.00 |
| harness/mistral-small:24b | 1.0 | 216 | 8.8 | 0.00 |
| harness/phi4:14b | 1.0 | 80 | 8.8 | 0.00 |
