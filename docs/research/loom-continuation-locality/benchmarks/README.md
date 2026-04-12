# Loom Continuation Locality - Research Benchmark Docs

This directory contains research-specific benchmark documentation for the Loom
continuation locality study, focused on Core/Community execution path optimization.

## Purpose

These docs specialize benchmark conventions and practices for this particular
research question: can scheduler-aware continuation execution improve locality
signals on the Exeris Community path, and does this signal survive E2E checks.

## Shared Conventions

Shared benchmark conventions, templates, harness infrastructure, and cross-project
reporting practices are maintained in ~/exeris-systems/exeris-benchmarks/docs.

This local set of documents specializes those conventions for this research track.

## Current Execution Model (Real State)

- Kernel side: micro JMH mechanism benchmarks.
- Benchmark lab side: E2E scenario campaigns in exeris-benchmarks.
- Decision policy: progression requires both tracks to be quality-valid.

Current program status: NO_GO for progression in this cycle.
Reason: no consistent signal that `locality-aware` provides measurable benefit
versus `default-vt` in this cycle (including exploratory interpretation).
E2E eligibility/leg stability issues are treated as secondary constraints,
not the primary decision driver.

## Structure

- `hypotheses.md` - Research hypotheses and questions
- `methodology.md` - Dual-track methodology: kernel micro JMH + E2E campaigns
- `metrics.md` - Concrete metrics, collection methods, and aggregation rules
- `reporting-rules.md` - Claim gates, decision policy, and NO_GO criteria

## Quick Start

1. Read hypotheses.md to understand what we are trying to measure.
2. Review methodology.md for the real split of responsibilities between micro and E2E.
3. Study metrics.md for both mechanism metrics and E2E eligibility/status metrics.
4. Consult reporting-rules.md for integrated GO/NO_GO rules.

## References

- Parent research: docs/research/loom-continuation-locality/RESEARCH-loom-continuation-locality-community.md
- Shared research template: docs/research/RESEARCH-TEMPLATE.md
- Architecture: docs/architecture.md
- Performance contract: docs/performance-contract.md
