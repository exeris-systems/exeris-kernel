# Loom Continuation Locality - Research Benchmark Docs

This directory contains research-specific benchmark documentation for the Loom
continuation locality study, focused on Core/Community execution path optimization.

## Purpose

These docs specialize benchmark conventions and practices for this particular
research question: can we measure scheduler-aware execution improvements on the
simpler Exeris Community stack, independent of native transport details?

## Shared Conventions

Shared benchmark conventions, templates, harness infrastructure, and cross-project
reporting practices are maintained in ~/exeris-systems/exeris-benchmarks/docs.

This local set of documents specializes those conventions for this research track.

## Structure

- `hypotheses.md` - Research hypotheses and questions
- `methodology.md` - Benchmark design, load/rate patterns, and harness discipline
- `metrics.md` - Concrete metrics, collection methods, and aggregation rules
- `reporting-rules.md` - Output format, CI/relative-error rules, and validation gates

## Quick Start

1. Read hypotheses.md to understand what we are trying to measure.
2. Review methodology.md for fixed-rate load patterns and backend-variant x concurrency matrix.
3. Study metrics.md for which counters matter and how to collect them.
4. Consult reporting-rules.md for pass/fail criteria and post-run validation.

## References

- Parent research: docs/research/loom-continuation-locality/RESEARCH-loom-continuation-locality-community.md
- Shared research template: docs/research/RESEARCH-TEMPLATE.md
- Architecture: docs/architecture.md
- Performance contract: docs/performance-contract.md
