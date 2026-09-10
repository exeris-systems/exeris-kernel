---
title: "Diagnostics Subsystem — Read-Only Introspection for Adapters"
type: subsystem
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-09
---

# Diagnostics Subsystem — Read-Only Introspection for Adapters

An agent, an IDE plugin or an operator asking "what is actually running in there" had two options
before this subsystem: read the boot log, or reach into Core types the Wall says they may not. This
gives them a third — a **read-only, versioned, machine-readable** answer, and an out-of-process
adapter that speaks it.

It is read-only by construction. Nothing on this surface starts, stops, reconfigures or mutates
anything; a diagnostics call that changed state would be a control plane, which is a different
decision nobody has made.

## Contract

`KernelDiagnostics` is three abstract methods and one default:

```java
ProvidersSnapshot       listProviders();
BootstrapDagSnapshot    getBootstrapDag();
SubsystemSnapshot       describeSubsystem(String name);
default RuntimeErgonomicsSnapshot getJvmErgonomics();   // answered by the SPI itself
```

`getJvmErgonomics()` being **default** is the load-bearing detail: the JVM's own view of its
ergonomics is not driver-specific, so the SPI answers it and an implementer overrides only if it has
something better — a container-aware reading, for instance.

**Every snapshot is stamped.** `KernelDiagnostics.SCHEMA_VERSION` is `"1.0"`, and each method
returns a non-null record carrying that version plus its own `capturedAt`. A consumer therefore
never has to guess whether two answers came from the same shape, and a wire break is a version
change rather than a surprise.

The records, in full:

| Record | Components |
|:--|:--|
| `ProvidersSnapshot` | `schemaVersion`, `capturedAt`, `providers` |
| `ProviderDescriptor` | `providerName`, `spiType`, `priority`, `displayName` |
| `BootstrapDagSnapshot` | `schemaVersion`, `capturedAt`, `nodes` |
| `DagNode` | `name`, `phase`, `dependsOn`, `running`, `optional` |
| `SubsystemSnapshot` | `schemaVersion`, `capturedAt`, `requestedName`, `subsystem` |
| `SubsystemDescriptor` | `name`, `phase`, `dependsOn`, `running`, `optional` |
| `RuntimeErgonomicsSnapshot` | `schemaVersion`, `capturedAt`, `gcName`, `heapMaxBytes`, `heapCommittedBytes`, `availableProcessors`, `cpuQuotaMicros`, `cpuPeriodMicros`, `memoryMaxBytes`, `cpusetEffective`, `largePagesEnabled`, `transparentHugePages`, `classDataSharingActive`, `aotCacheActive` |

**Returned collections reject mutation.** A caller cannot edit a snapshot into something the kernel
never said, and the TCK pins it.

**This is not a bootstrap subsystem.** It has no DAG node, no phase and no `dependsOn`; it is
discovered by `ServiceLoader` on `KernelDiagnosticsProvider`. `BootstrapSelector.forNames(...)` will
never name it, and it answers whether or not a kernel is running — see below.

## Hot path

**There is none, and that is the design.** All four methods are cold-path: each captures its own
`capturedAt` and allocates fresh records rather than handing out a cached view. A snapshot that
reused a mutable structure would be a snapshot of whenever the caller last looked, which is worse
than no answer.

`listProviders` is the expensive one and the discriminating one: it instantiates **every** provider
on the classpath through `ServiceLoader`, so it runs their class initialisers. That makes it the
method a classpath defect breaks first — which is exactly why the CLI's integration suite asserts
all nine SPI types come back by name.

## Failure modes

| Situation | What the caller gets |
|:--|:--|
| Read outside a bound kernel scope | Subsystem-derived snapshots come back **empty, not thrown** — the honest answer for "nothing is running" |
| `describeSubsystem` with an unknown name | A well-formed snapshot whose `subsystem` is `Optional.empty()` — `null` on the JSON wire, never a `null` component in Java; the `requestedName` echoes what was asked |
| A provider's class initialiser fails | Reaches the CLI as an `Error`, not an exception, and is caught there — one broken provider degrades one method, never the session |
| Not on Linux, or no cgroup v2 | The container fields read `null`; a `null` here means *unknown*, never *unlimited* |

That last distinction matters more than it looks. `cpuQuotaMicros: null` does not mean the process
is unconstrained — it means this reading could not establish a constraint. A consumer that treats
`null` as "no limit" will size a pool wrongly inside a container.

**Four** error codes name an audited call rather than a crash: `EX-DIAG-1001`, `EX-DIAG-1003`,
`EX-DIAG-1004` and `EX-DIAG-1005`. `EX-DIAG-1002` is a **reserved gap** — the `listCapabilities()`
method it audited was removed before the SPI froze, and the number was kept rather than reused so the
range still means what an older log says it meant. The registry in
[`exceptions.md`](exceptions.md) is authoritative for their text.

## Owning ADRs

- [ADR-033](../adr/ADR-033-kernel-diagnostics-spi.md) — the interface surface, the concrete
  obligations (fresh records, own timestamp, immutable collections), and what is explicitly not in
  scope.
- [ADR-006](../adr/ADR-006.link.md) — why an adapter reads this SPI instead of reaching into Core.
- [ADR-039](../adr/ADR-039-open-core-observability-boundary.md) — the observability boundary this
  sits beside: diagnostics answers *what is configured and running*, telemetry answers *what
  happened*, and what the open-core kernel itself commits to on that line.

## The CLI adapter

`exeris-kernel-diagnostics-cli` builds an **executable shaded jar**, and v0.12.0 is the release that
takes it to Maven Central — the coordinates are not resolvable there until that upload happens, so
today it is built from this repository or taken from GitHub Packages. It boots the kernel with
`inspect()` — resolving config and topology **without** calling `initialize()` or
`start()` — and then serves **newline-delimited JSON**: one request object per line on stdin, one
response line on stdout.

```
{"method":"listProviders"}
{"method":"getBootstrapDag"}
{"method":"getJvmErgonomics"}
{"method":"describeSubsystem","name":"memory"}
```

**A blank line is the exception, and it is the one that can hang a caller**: `serve()` skips it and
writes nothing, so a client that sends one and then blocks on `readLine()` waits for a response that
is never coming. Send a request or send nothing.

Anything else — an unknown method, a line that is not JSON — is answered with an `{"error":"…"}`
object on the line the caller is waiting for. **The session survives it.** That is the contract, not
a courtesy: a consumer caches the child process across calls, so a process that dies on one bad line
costs the caller every later request. `DiagnosticsCliShadedJarIT` drives the shipped jar
out-of-process and pins both halves — the error answer, and the responses that follow it.

The caller owns the process; closing the child's stdin ends the session and the jar exits 0.

## Stability

`eu.exeris.kernel.spi.diagnostics` is **stable** in the
[SPI Stability Matrix](../stability-matrix.md), and has been since 0.9.0 — the first new SPI surface
this repository classified `stable` outright rather than promoting later. The surface was trimmed
before it froze rather than after: `listCapabilities()`, `CompositionSnapshot` and
`CapabilityDescriptor` were removed pre-1.0 because composition is a build-time and manifest concern,
which is why `EX-DIAG-1002` is a gap.

The matrix records one follow-up against this row: selecting a `KernelDiagnosticsProvider` at
`priority=100`. Community ships the `priority=0` provider; an Enterprise overlay at `100` returns the
**same record types** with Enterprise-only fields populated where useful. An overlay populates
additional fields and never forks the shapes, so nothing in the contract above changes when one is
present.

## Verification

`AbstractKernelDiagnosticsTck` pins the schema invariants (`schemaVersion == "1.0"` and a non-null
`capturedAt` on all four methods), that the bound subsystem inventory drives the snapshots, that
`describeSubsystem` answers for a known name and empties for an unknown one, that discovery yields
well-formed descriptors, that snapshots are empty rather than exceptional outside a kernel scope,
and that returned lists reject mutation.

Beyond the TCK, the shaded jar is started in CI and answered-to — `listProviders` must return all
nine SPI types by name, which is the assertion that noticed the Jackson-annotations collapse that
once shipped a CLI dying on its first command.

## Telemetry

One JFR event, carrying the method name and an error code:

```text
eu.exeris.kernel.diagnostics.KernelDiagnostics
```

Every call that **returns** is audited, including the ones that answer empty — an operator asking
why a tool saw nothing needs to know the call happened. A call that **throws** emits nothing: the
event is committed after the operation produces its snapshot, so the failing branch is dark. An
operator reconstructing a session from JFR alone therefore sees successes and silence, not successes
and failures.

## Not in scope

No mutation, no control plane, no live streaming. Diagnostics answers what is configured and
running at the moment it is asked; what *happened* is
[telemetry](telemetry.md)'s question and is answered by JFR, not here.
