# Research: [Short Title]

> **Branch:** `research/[slug]`
> **Author:** [GitHub handle]
> **Started:** [YYYY-MM-DD]
> **Milestone:** [v0.x — or "unbounded"]
> **Status:** `active` | `concluded` | `abandoned`

---

## Hypothesis

> One paragraph. What do you believe is true, and why does it matter for Exeris Kernel?
> Be specific — "I think X will reduce Y by Z% because W" is a good hypothesis.
> "Let's explore X" is not.

---

## Motivation

Why now? What existing evidence (benchmark, profiling data, JEP, paper, external benchmark)
suggests this is worth investigating? Link to:

- Relevant ADR (if any)
- JEP number (if JVM feature)
- External benchmark / paper (DOI or URL)
- JFR recording or JMH result that shows the bottleneck

---

## Methodology

### What will be measured

| Metric | Tool | Baseline | Target |
|:-------|:-----|:---------|:-------|
| e.g. P99 latency | JMH + JFR | X µs | < Y µs |
| e.g. heap alloc/op | JFR TLAB | X bytes | 0 bytes |

### How it will be measured

Describe the benchmark / test harness setup:

- Hardware (bare metal / VM / CI runner)
- JDK version + flags
- JMH parameters (forks, warmup, iterations)
- Any kernel tunables (cpu governor, io_uring flags, etc.)

### What will NOT be measured (scope boundary)

Explicitly list what is out of scope to prevent scope creep.

---

## Implementation Notes

*Free-form. Updated as work progresses.*

Running notes, dead ends, surprising findings. This is a lab notebook, not a design doc.
Broken code, TODOs, and half-finished ideas are acceptable here.

---

## Results

*Fill in when experiments are complete.*

### Summary

One paragraph conclusion. Did the hypothesis hold?

### Data

| Config | Metric | Value | vs Baseline |
|:-------|:-------|:------|:------------|
| | | | |

Attach JMH JSON (`results/jmh-*.json`) and JFR recordings (`results/*.jfr`) to this branch.

---

## Decision

Choose one:

- [ ] **Promote to ADR** — findings are conclusive, open ADR PR to `main`
- [ ] **Promote to Feature** — no ADR needed, open feature PR with research branch as base
- [ ] **Park** — promising but not urgent, convert to GitHub Discussion
- [ ] **Abandon** — hypothesis falsified or effort not justified

**Rationale:** [why this decision]

**Follow-up issue:** [link to GitHub issue if promoting]

---

## References

- [Link 1]
- [Link 2]
