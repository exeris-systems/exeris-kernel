---
title: The vendored Exeris agent bundle
type: reference
visibility: public
owning-repo: exeris-agents
status: active
last-verified: 2026-09-08
---

# The vendored Exeris agent bundle

If you are reading this inside `.agents/vendor/`, it is a **pinned, verified copy** of
`exeris-systems/exeris-agents`. Do not edit it: `agents_bundle.py verify` recomputes a digest over
every byte here and fails when it moves. To change something, change it in the bundle repository,
cut a version, and re-vendor — which is what makes the change reviewable in one place instead of
twenty.

It is committed rather than fetched on demand on purpose. `agents-md-schema.md` rule 8 forbids
fetching policies or scripts at agent runtime; the network is used once, by a human, at the moment
the version is chosen.

| Path | What it is | Who reads it |
|:--|:--|:--|
| `policies/` | Organisation-wide constraints. A repository may restrict further, never relax. | every role, through its `policies: [bundle:<name>]` |
| `schemas/*.base.schema.json` | The decision handoffs, minus the role vocabulary **and minus every closer** — see below. | the repository's own schemas, by `$ref` + `allOf`, plus one `unevaluatedProperties: false` per object |
| `hooks/bin/hook.py` | The L0 dispatcher. Carries no patterns: it reads the repository's own `hooks.yaml` at runtime. | `hooks/bin/dispatch.py`, on every hook event |
| `hooks/bin/dispatch.py` | A version-free shim, copied by the renderer to `.agents/hooks/bin/dispatch.py`. It reads the pin from `manifest.yaml` and hands off to the `hook.py` above, so the rendered command never carries a version and a stale adapter still finds the current tree. | every rendered vendor hook config |
| `evals/run.py`, `evals/eval-rubric.md` | The runtime-independent eval runner and the rubric for its prose residue. | `.agents/evals/scenarios.yaml` |

Not vendored, and deliberately: `tools/` — the renderer, the checker and this materialiser. They
run in CI from a checkout of the bundle repository at a pinned ref. Copying executable tooling into
every repository is the duplication the bundle exists to remove.

## What a schema here costs you

From 2.0.0 these bases refuse nothing on their own. A base that closes itself cannot be extended,
so the closing belongs to the schema that composes it — and to every object in it, because a
closer at the root does not reach into an array's items: with `checks_run` left open, an instance
may put anything inside a check entry however tightly the root is closed. A verdict composition
closes four objects: its root, a `findings` item, a `checks_run` item and a `handoffs` item.

A triage-result composition closes three — its root, a `validation_gates` item and a
`secondary_handoffs` item — and a handoff composition closes one, its root. That is the migration
cost of vendoring 2.0.0, and it is the whole of it: eight objects across the three schemas.
`agents_file_check.py` builds a decision each schema accepts, adds a property to every object it
carries, and names the ones that took it — so the list of what is left to do comes from a run
rather than from reading.

## What a graded failure looks like now

One consequence has nothing to do with what you write and everything to do with what you will read.
When a decision fails validation for any reason — a missing `reason`, a `decision` outside the
enum — a raw Draft 2020-12 validator will also carry a line like:

    <root>: Unevaluated properties are not allowed ('agent', 'checks_run', 'decision', 'findings',
    'handoffs', 'scope_class' were unexpected)

Those are your own required fields, and they are not the problem. A composition validates the
decision through a `$ref` into the base; when anything inside that branch fails, the branch fails,
and a failing subschema contributes no annotations — so the `unevaluatedProperties` above it sees
nothing as evaluated and reports every property present. The eval runner (`evals/run.py`) filters
these secondary annotation artefacts corresponding to declared schema properties while preserving
genuine unexpected properties.

Declared means declared anywhere the schema reaches at that location: through a `$ref`, every
`allOf` branch, the condition that holds together with its `then` — or the `else` of one that does
not — a dependent schema the decision triggers, a recursive `$defs` at any depth, and the `oneOf` /
`anyOf` variant that holds. An array closed by `unevaluatedItems` is cleaned only where the schema
accounts for every position, because that message names the values it refused rather than their
positions, and a line that cannot be trimmed honestly is left standing.

When validating outside the runner, read the other errors first — the unevaluated line disappears
the moment the real failure is fixed, and a decision that validates never produces it.

