# jfr-reporter

Reads the JFR recordings a kernel test run leaves under `exeris-kernel-<module>/target/` and writes
the JSON that GitHub Pages serves at `data/jfr/latest` (job `parse-jfr-to-json` in
`.github/workflows/maven.yml`, `main` only). It sits outside the Maven reactor on purpose: it is
CI tooling, never deployed, and it keeps its own dependency set (Jackson 2).

```
mvn -f tools/jfr-reporter/pom.xml clean verify
java -jar tools/jfr-reporter/target/jfr-reporter-1.0.0.jar \
  --core exeris-kernel-core/target --community exeris-kernel-community/target \
  --commit $(git rev-parse HEAD) --branch $(git branch --show-current) --out build/jfr-report
```

## What is a measurement

`JfrAllocationMonitor.measure` (in `exeris-kernel-tck`) writes one recording per steady-state
window to `target/jfr-reports/{TestClass}-{Subsystem}-{yyyyMMdd}-{HHmmss}.jfr`, and inside it a
pair of `eu.exeris.tck.AllocationWindow` marker events (`boundary = start | end`) carrying the
subsystem, the test class, the workload thread id, the iterations, the contract mode and budget, and
on `end` the exact `ThreadMXBean` bytes delta the monitor measured.

Marker pairs are matched by commit time, not by file order — JFR flushes thread buffers as they
fill, and a real recording has been read with the `end` marker 1 700 events before the `start`.

A recording is a **subsystem measurement** when it holds exactly one marker pair (identity source
`marker`), or no pair and the file name above (`filename`: recordings made before the marker
existed; their verdict is `NOT_MEASURED`). Everything else is listed under `unattributed` in
`jfr-summary.json` with a reason and counted nowhere else:

| reason | what it is |
|---|---|
| `multiple_windows` | A JVM-wide recording that outlived several measurements. JFR writes every enabled event to every active recording, so such a file holds one marker pair per measurement that JVM ran, plus bootstrap and every other test. |
| `duplicate_window` | A second file holding the same window (subsystem, test class, start time) — a JVM-wide recording from a JVM that ran exactly one measurement. The TCK's own file wins; otherwise the smaller one. |
| `no_marker_and_no_tck_filename` | `jmh-benchmarks.jfr`, the pinning monitor's `pin-<label>-<ts>.jfr`, ad-hoc test recordings — and, in a module build, `target/surefire.jfr`: every fork started with the root pom's `-XX:StartFlightRecording=…filename=target/surefire.jfr` overwrites it, and in `core` and `community` the last such fork is failsafe's (a one-second JVM that selects no tests), so the file a build leaves behind holds no measurement. |

Only measurements enter `evidence.json` and the module-level `timeline.json`, `stacks.json` and
`alloc-top-classes.json`. When a measurement has a window, only events inside it are attributed.

## Two attributions, one event

Every allocation event (`jdk.ObjectAllocationSample`, `jdk.ObjectAllocationInNewTLAB`,
`jdk.ObjectAllocationOutsideTLAB`) is classified twice, on independent axes.

**Owner** (`category`) — who allocated. The *owner frame* is the first stack frame whose class is
not in the runtime set `java.` `javax.` `jdk.` `sun.` `com.sun.` `org.jacoco.`. A test frame further
down the stack changes nothing: production code called from a TCK still allocated.

| owner | rule on the owner frame's class |
|---|---|
| `test_harness` | package segment `.tck.` `.testkit.` `.test.` `.jmh_generated.`; or outer simple name (nested and lambda suffixes stripped) ending `Test` `IT` `Tck`; or prefix `org.junit.` `org.opentest4j.` `org.assertj.` `org.mockito.` `org.openjdk.jmh.` `org.apache.maven.surefire.` |
| `production` | otherwise, prefix `eu.exeris.` |
| `third_party` | otherwise |
| `no_owner` | every frame is in the runtime set, or the stack is empty |

There is deliberately no substring test on `test`: it matched `latest`, `contest` and `testkit` alike.

**Kind** (`kind`) — what was allocated, by the class of the object.

| kind | rule on the object class |
|---|---|
| `exeris` | prefix `eu.exeris.` — the only kind the TCK's own contract count sees |
| `loom` | prefix `java.lang.VirtualThread` `java.lang.ThreadBuilders$` `jdk.internal.vm.Continuation` `jdk.internal.vm.StackChunk` `java.util.concurrent.ForkJoinTask$` |
| `panama` | prefix `jdk.internal.foreign.` `java.lang.foreign.` |
| `array` | a `[`-prefixed descriptor or a `[]` suffix |
| `jdk` | prefix `java.` `javax.` `jdk.` `sun.` `com.sun.` |
| `other` | anything else |

So a `VirtualThread` allocated by `InMemoryEventBus.publish` is `production` / `loom`, and a payload
built by `EventBusZeroAllocTck.runSingleIteration` is `test_harness` / `exeris`.

## Bytes are per event type, never added across types

| event | count field | bytes field | what the bytes are |
|---|---|---|---|
| `jdk.ObjectAllocationSample` | `samples` | `sample_weight_bytes` = Σ `weight` | the sampler's extrapolation of bytes allocated on that thread since its previous sample; a sum estimates pressure, a single value is not an object size |
| `jdk.ObjectAllocationInNewTLAB` | `tlab_refills` | `tlab_bytes` = Σ `tlabSize` | the buffer handed out when the previous one filled; `allocationSize` (the one object that did not fit) is kept per event as `objectSize` but never summed |
| `jdk.ObjectAllocationOutsideTLAB` | `outside_tlab` | `outside_tlab_bytes` = Σ `allocationSize` | exact |

The monitor enables all three with the sample throttle off, so one large allocation can appear as
two events; the counters are separate, so nothing is added twice.

## Verdict

`verdict` reproduces the TCK's own assertion from the marker and refuses to guess without one:

- `PASS` / `FAIL` — the count of `exeris`-kind events on the workload thread inside the window
  (the class of the marker event itself excluded, exactly as the TCK excludes it) against the mode
  the marker carries: `zero` needs a count of 0 and a bytes delta below the iteration count (or an
  unavailable delta); `bounded` needs count ≤ iterations × budget.
- `NOT_MEASURED` — no marker, or mode `unspecified` (a caller of `measure` that asserts something
  of its own).

A subsystem with several recordings takes the worst: any `FAIL` fails, else any `PASS` passes.
The verdict says what the TCK asserted; `owned.production` says what the hot path allocated
whatever its type, and `contract.bytes_per_iteration` is the number the bounded mode does not yet
cap.

## `evidence.json` (schema 2)

```
meta: { schema, generated, jdk, commit, branch }
<module>: {
  <subsystem>: {
    verdict, measured_recordings,
    recordings: [ { file, test_class, window_source, verdict,
                    contract: { mode, budget_per_iteration, iterations,
                                exeris_events_on_workload_thread, allocated_bytes_delta,
                                bytes_per_iteration, satisfied } } ],
    total_events, exeris_alloc_count,
    exeris_production_alloc_count,      # owner = production, any kind
    exeris_test_harness_count,          # owner = test_harness, any kind
    owned: { production | test_harness | third_party | no_owner:
             { events, samples, sample_weight_bytes, tlab_refills, tlab_bytes,
               outside_tlab, outside_tlab_bytes, by_kind: { exeris, loom, panama, array, jdk, other } } },
    top_production_frames: [ { frame, events, …same counters… } ]   # top 20 by events
  }
}
```

Schema 1 (before this file existed) computed `verdict` as `production == 0` for core and
`production < 1000` for community, after a classifier that demoted every allocation with a
test frame anywhere in its stack to harness — so `VERIFIED` meant nothing had been attributed, not
that nothing had been allocated. `exeris_production_alloc_count` and `exeris_test_harness_count`
keep their names and change meaning to the owner-frame counts above.

Other files: `timeline.json` (`t type class thread threadId size sizeKind kind category owner stackId`),
`stacks.json` (`stackId → frames`), `alloc-top-classes.json` (per `class × category × kind`, the
counters above), `jfr-summary.json` (`topThreads`, `topClasses`, `phaseBoundaries` from the markers,
`unattributed`).
