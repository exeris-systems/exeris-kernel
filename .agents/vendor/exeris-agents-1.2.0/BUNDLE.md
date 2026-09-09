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
| `schemas/*.base.schema.json` | The decision handoffs, minus the role vocabulary. | the repository's own schemas, by `$ref` + `allOf` |
| `hooks/bin/hook.py` | The L0 dispatcher. Carries no patterns: it reads the repository's own `hooks.yaml` at runtime. | every rendered vendor hook config |
| `evals/run.py`, `evals/eval-rubric.md` | The runtime-independent eval runner and the rubric for its prose residue. | `.agents/evals/scenarios.yaml` |

Not vendored, and deliberately: `tools/` — the renderer, the checker and this materialiser. They
run in CI from a checkout of the bundle repository at a pinned ref. Copying executable tooling into
every repository is the duplication the bundle exists to remove.
