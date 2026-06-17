# JVM Flags Baseline (Community)

> **Scope:** generic, Community-tier recommended JVM flag baseline for running the Exeris Kernel in a
> container. This is **observational + generic guidance only** — it carries no environment-specific
> tuning thresholds or a recommendation ladder. The actionable advisor (read the ergonomics snapshot,
> emit "you are CPU-throttled, raise the quota to N") is the Enterprise advisor's surface per ADR-008.
>
> **Companion surface:** the kernel exposes what it *actually* resolved at runtime through
> `KernelDiagnostics.getJvmErgonomics()` (`RuntimeErgonomicsSnapshot`, since v0.9 — ADR-033). Read that
> snapshot to confirm a flag took effect and to see the cgroup limits the runtime is squeezed by; this
> document is the static counterpart that tells you which flags to set in the first place.

## Why this exists

Operators diagnosing throughput problems frequently discover the JVM is being throttled or
memory-squeezed by its own container limits rather than by application logic. The constrained
`entity-read-by-id` profile recorded **465/547 cgroup periods throttled** — CPU starvation competing
directly with request servicing. A correct flag baseline plus the `getJvmErgonomics()` snapshot makes
that condition visible instead of mysterious.

`RuntimeErgonomicsSnapshot` reports, per call: `gcName`, `heapMaxBytes` / `heapCommittedBytes`,
`availableProcessors`, the cgroup-v2 `cpuQuotaMicros` / `cpuPeriodMicros` / `memoryMaxBytes` /
`cpusetEffective`, and best-effort `largePagesEnabled` / `transparentHugePages` /
`classDataSharingActive` / `aotCacheActive`. Absent data (non-Linux host, cgroup-v1-only hierarchy, no
container limits) is reported as `Optional.empty()` — never a guessed sentinel.

## Container awareness

The HotSpot JVM is container-aware by default on Linux (cgroup-v1 and cgroup-v2): it reads
`cpu.max` / `cpu.cfs_quota_us` and `memory.max` to derive `availableProcessors()` and the default heap.
Confirm what it resolved with the ergonomics snapshot rather than assuming.

- **Do not disable container awareness.** `-XX:-UseContainerSupport` is almost never correct in a
  containerized deployment.
- **Pin the CPU count only when you must.** If the orchestrator's CPU quota does not reflect the
  parallelism you want (e.g. a fractional quota under a burst workload), `-XX:ActiveProcessorCount=N`
  overrides the derived value. The snapshot's `availableProcessors` vs `cpuQuotaMicros` / `cpuPeriodMicros`
  tells you whether the derived count matches the quota.
- **Size the heap against `memory.max`, not the host.** Prefer
  `-XX:MaxRAMPercentage=<pct>` over a fixed `-Xmx` so the heap tracks the cgroup limit. Leave headroom
  for off-heap (the kernel's native arenas, TLS buffers, thread stacks, metaspace). A common Community
  starting point is `-XX:MaxRAMPercentage=60.0` on a memory-limited container.

## Garbage collector

- **G1 (default)** is the Community baseline — predictable pause behavior across a wide workload range.
  No flag needed; `gcName` in the snapshot confirms it.
- Switching collectors (`-XX:+UseZGC`, `-XX:+UseParallelGC`) is a workload-specific decision and is out
  of scope for this generic baseline; benchmark before adopting.

## Large pages / Transparent Huge Pages

- Large pages reduce TLB pressure on large heaps but require host configuration
  (`vm.nr_hugepages`, `memlock` limits, and on many kernels `-XX:+UseTransparentHugePages` vs explicit
  `-XX:+UseLargePages`). They are a **prerequisite-gated** optimization: enable only after the host is
  provisioned, then confirm with `largePagesEnabled` / `transparentHugePages` in the snapshot.
- THP mode is a host-level setting (`/sys/kernel/mm/transparent_hugepage/enabled`); the snapshot reports
  whether the active mode is anything other than `[never]`.

## CDS / AOT

- **Class Data Sharing** shortens startup by memory-mapping a shared archive. The default archive is
  used automatically on modern JDKs; an application archive
  (`-XX:SharedArchiveFile=app.jsa`) can be generated for the kernel + application classpath.
  `classDataSharingActive` reflects best-effort detection.
- **AOT cache** (`-XX:AOTCache=…` / `-XX:AOTMode=…` on JDKs that support it) is an additional
  startup/warmup optimization; `aotCacheActive` reflects best-effort detection. Generic guidance only —
  measure warmup before adopting in production.

## Quick triage with the snapshot

1. Throughput lower than expected? Compare `availableProcessors` against
   `cpuQuotaMicros / cpuPeriodMicros`. A quota well below the processor count means the runtime is
   CPU-throttled by the container.
2. Frequent GC / OOM? Compare `heapMaxBytes` against `memoryMaxBytes` — a heap sized near the cgroup
   limit leaves no room for off-heap and metaspace.
3. Pinned to the wrong cores? Inspect `cpusetEffective`.

See also: [ADR-033 — Kernel Diagnostics SPI](../adr/ADR-033-kernel-diagnostics-spi.md),
[Telemetry subsystem](../subsystems/telemetry.md).
