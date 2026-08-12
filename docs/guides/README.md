# Developer Guides

Task-oriented paths through Exeris Kernel: how to start, in the order you actually need things.

These are **not** the normative documents. [`docs/subsystems/`](../subsystems/) holds the contracts
and [`docs/adr/`](../adr/) holds the decisions; when a guide touches a contract it links out rather
than restating it, so there is one place to fix when behaviour changes.

---

## Pick your path

| Guide | Read it when |
|:--|:--|
| [01 — Platform and Dependencies](./01-platform-and-dependencies.md) | You need the JDK baseline, the JVM flags, and the Maven coordinates. Start here regardless of which of the other two you want. |
| [02 — Build an Application](./02-build-an-application.md) | You are writing an application: boot the kernel from your own `main()`, serve HTTP, configure it, test it. |
| [03 — Implement a Provider](./03-implement-a-provider.md) | You are implementing an SPI contract: a driver, an engine, a provider. |

---

## What these guides promise

Every guide carries a verification header naming the commit it was checked against, and every code
snippet carries a citation line giving its source file, line range, and whether it was quoted
verbatim or adapted.

The rule that follows from that: **if a snippet and its source disagree, the source wins and the
guide is the bug.** Report it or fix it — do not code against the guide.

Where the kernel does not do something yet, the guides say so in a *Not available today* section
rather than describing an intended future. Nothing here is target-state.

---

## Maintaining these guides

**Citations are `path:line`, and the path is the durable half.** Line ranges rot first; a stale range
still leads to the right file. Keep the `(quoted)` / `(adapted)` marker accurate — it tells the next
reader whether a diff is drift or deliberate trimming.

**Prefer sources with teeth.** When the same behaviour can be shown from a test body or from prose,
quote the test: an API change stops it compiling, so someone has to touch it. Production code is the
next best thing; javadoc is the weakest, because nothing breaks when it goes stale.

**Check that every cited path still exists** after a refactor that moves files:

```bash
grep -ohE '[A-Za-z0-9_./-]+\.(java|xml|md):[0-9]+' docs/guides/*.md CONTRIBUTING.md \
  | cut -d: -f1 | sort -u \
  | while read -r p; do [ -e "$p" ] || echo "MISSING: $p"; done
```

Widen the file list as `path:line` citations spread — the check is only worth as much as its glob.

This catches renames and deletions — the common real failure. It does **not** verify that a line
range still contains what the guide claims; that needs a human, and it is the one step worth doing by
hand when a subsystem changes.

Keep the snippet count low. Every snippet is a maintenance liability, so the question for each one is
whether deleting it and linking instead would lose the reader.

---

## Where these guides stop

- [`docs/subsystems/`](../subsystems/) — the normative contract for each subsystem
- [`docs/modules/`](../modules/) — what each reactor module is and may depend on
- [`docs/architecture.md`](../architecture.md) — The Wall, the layer model, the request path
- [`docs/stability-matrix.md`](../stability-matrix.md) — how far you can lean on a given SPI surface
- [`docs/support-matrix.md`](../support-matrix.md) — supported database, broker, TLS, HTTP versions
- [`docs/ROADMAP.md`](../ROADMAP.md) — known gaps and what is planned
- [`CONTRIBUTING.md`](../../CONTRIBUTING.md) — contributing *to* the kernel, as opposed to building on it
