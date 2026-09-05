---
name: exeris-adr-register
description: Authoring workflow for an Exeris ADR / RFC / Research doc — picks the right document shape, reserves the GLOBAL ADR number BEFORE writing content, and registers the row after landing. Use whenever asked to draft, write, or number an ADR/RFC/research note anywhere in the exeris-* ecosystem.
---

# Exeris ADR Register

## Purpose
Prevent ADR-number collisions and wrong-shape decision docs. ADR numbering is a single GLOBAL namespace across all `exeris-*` repos; claiming a number before reserving it caused the PR #129 / ADR-026 collision. This skill encodes the safe order of operations.

## When to Use
- Asked to draft/write/number an ADR, RFC, or Research note in any exeris-* repo.
- A change's architectural intent or boundary meaning is changing and needs a decision record.

## Step 0 — Pick the document shape FIRST
Three shapes for three question kinds (templates in `~/exeris-systems/exeris-docs/templates/`); they are NOT interchangeable:
- **Research** (`RESEARCH-TEMPLATE.md`) — falsifiable hypothesis, lab-notebook, JMH/JFR-driven. Branch-scoped (`research/<slug>`). **No central registry.**
- **RFC** (`RFC-TEMPLATE.md`) — multi-option strategic question. File: `RFC-YYYY-MM-DD <Short Title>.md`. **No central registry.**
- **ADR** (`ADR-TEMPLATE.md`) — decision ALREADY made. File: `ADR-NNN <Short Title>.md`. **Enters the registry.**

If upstream measurement or option comparison is missing, propose Research or RFC before going straight to ADR.

## Step 1 — Reserve the number BEFORE writing content (ADR only)
- Open `adr-index.md` in the separate `exeris-docs` repo (locally `~/exeris-systems/exeris-docs/adr-index.md`; requires that repo checked out alongside the kernel) and find the next free slot (numbering is chronological by decision date with reserved gap-fillers).
- Reserve the row there FIRST. Only then write the ADR body. NEVER write content first.
- Scope of the registry: `exeris-*` repos plus `Corelio/` stack-level decisions. `budgetHQ/` and `pbm/` have their own internal namespaces — they do NOT enter `adr-index.md`.
- Out of scope: refactor-only ADRs (those live in PR descriptions / commit history), and Polish-language kernel-enterprise refactor notes — never promote them.

## Step 2 — Place the file next to the code it describes
- ADR file lives in the owning repo's `docs/adr/`.
- For cross-repo ADRs, the owning repo holds the authoritative copy; every other affected repo gets a `docs/adr/ADR-NNN.link.md` stub.
- Visibility taxonomy (ADR-020): `public` or `enterprise-private` (the old `public-staged` is deprecated).
- Open-core hygiene: a public kernel ADR never deep-links an enterprise-private repo. A private ADR gets a standalone open-core counterpart ADR (the ADR-039 ↔ ADR-018 pattern), not a dead link stub.

## Step 3 — Register the row AFTER landing
- Add/finalize the row in `adr-index.md` as a SEPARATE commit in the `exeris-docs` repo (separate from the content commit, separate repo).
- This step requires `exeris-docs` access. If it is not available in the current session, do NOT skip it silently — open a follow-up issue/note to register (or reserve) the number so the global namespace stays collision-safe.

## Business decisions are separate
Legal / IP / financial / procurement decisions go in the private decision registry in `~/exeris-systems/exeris-business/`, NOT the public tech registry. Public ADRs invoke a business policy descriptively (e.g. "the IP detachment commercial policy"), never by an internal id.

## Terminology guard
Exeris is pre-1.0 / TRL-3 with no external SPI consumers — do NOT use "breaking change" framing. Reserve that vocabulary for 1.0.0 GA / TRL-5+.

## Output
1. Chosen shape + why.
2. For ADR: reserved number + confirmation the index row was added before content.
3. File path(s) + any `.link.md` stubs + visibility tag.
4. Separate-commit plan for the index row.

## Non-Negotiable Rules
- Reserve the ADR number in the global index BEFORE writing content.
- Research/RFC never enter the central registry; ADRs always do.
- No dead enterprise links in open-core docs.
- No "breaking change" framing pre-1.0.
