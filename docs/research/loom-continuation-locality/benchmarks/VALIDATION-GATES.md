# Validation Gates - Loom Continuation Locality Benchmark

Minimal, actionable merge-gate framework for micro locality benchmark code and execution layers.

## 1) Scope: In-Process vs. External Validation

### In-Process Validation (Benchmark Code Responsibility)

Code-level validation baked into harness binary. **Must pass before any measurement attempt.**

- **SPI Preflight:** StreamExecutionBackend provider discovery and SPI contract validation.
- **Harness Initialization:** Load-generator thread pool, NativeTcpCarrier setup, JFR/perf-stat API readiness.
- **Matrix Completeness:** Configured concurrency levels (16, 32, 64), backend mode variants (default-vt, locality-aware).
- **Measurement Discipline:** Fixed warmup (5s), measurement window (30s), cool-down (2s), repetitions (>= 3 after filter).
- **Artifact Staging:** JFR file paths writable, perf-stat available and runnable.

**Failure Mode:** Hard fail with diagnostic detail before any run starts.

---

### External Validation (Runner / CI Responsibility)

Environment-level validation performed by CI harness or local runner script. **Must pass before launching benchmark.**

- **Host Preflight:**
  - CPU governor fixed (performance or powersave, not on-demand).
  - Optional: CPU affinity / NUMA pinning available and tested on target socket.
  - Thermal throttling check (run baseline once; compare against known warm baseline; flag large outliers).
- **Service Readiness:**
  - Backend server (NativeTcpCarrier peer) online and accepting loopback TCP connections.
  - Network stack not congested (basic connectivity test).
- **JVM Preflight:**
  - JVM version and build metadata captured before runs.
  - JFR available (jdk.jfr module, -XX:+FlightRecorder).
  - Perf-stat binary available and runnable (perf stat --help).
- **Artifact Directories:**
  - Output paths writable and have >= 2GB free space per run batch.
  - Prior run artifacts backed up or cleaned.

**Failure Mode:** Diagnostic report; skip benchmark; log reason in CI.

---

## 2) Concurrency Matrix Validation

### Matrix Definition (In Code)

```
concurrency_levels = [16, 32, 64]
backend_modes = ["default-vt", "locality-aware"]
load_profiles = ["sub-max", "moderate", "max-throughput"]  # For full measurement; greylisting only at merge gates
scenarios = ["baseline"]                                    # Only baseline required for Phase 1 micro gates
repetitions_per_point = 5 (minimum; 10 if RE > 2%)
```

### Merge-Gate Matrix (Minimal Subset)

Only **baseline / sub-max** required for merge approval; full 18-point matrix deferred to CI/nightly.

```
| Matrix Point                      | Harness Code Gate | CI / Nightly |
|-----------------------------------|-------------------|--------------|
| backend=default-vt, conc=64vt     | REQUIRED          | ✓            |
| backend=locality-aware, conc=64vt | REQUIRED          | ✓            |
| all other points                  | CODE PATH ONLY    | ✓            |
```

**In-Code Validation:**
- Verify all 6 points (2 backends × 3 concurrency levels) initialize without error.
- Confirm matrix execution order deterministic (same seed, same request pattern).
- Assert warmup, measurement, cool-down timings honored.

**Commands:**

```bash
# Validate matrix code path (unit-level, no actual measurement):
mvn -pl exeris-kernel-community test -Dtest=LocalityBenchmarkMatrixTest

# Light integration: run 64vt baseline only (1 min on fast hardware):
mvn -pl exeris-kernel-community test -Dtest=LocalityBenchmarkTest \
    -Dexeris.benchmark.concurrency=64 \
    -Dexeris.benchmark.backend=default-vt \
    -Dexeris.benchmark.reps=1 \
    -Dexeris.benchmark.warmup=2s
```

---

## 3) Backend Availability Preflight: Code vs. Runner

### In-Process (Benchmark Code)

```
Preflight Gate: StreamExecutionBackend SPI Validation
├─ Load provider via ServiceLoader
├─ Instantiate default-vt backend (C)
├─ Instantiate locality-aware backend (E)
├─ Call public getExecutorService() or equivalent on both
├─ If any fails: throw ServiceConfigurationError with detail
└─ If all pass: proceed to harness initialization
```

**Code Implementation Pattern:**

```java
// In benchmark harness init
private void validateBackendAvailability() throws Exception {
  StreamExecutionBackendProvider provider = 
    ServiceLoader.load(StreamExecutionBackendProvider.class)
                 .findFirst()
                 .orElseThrow(() -> 
                    new IllegalStateException("No StreamExecutionBackend provider found"));
  
  // Attempt instantiation for both modes
  for (String mode : ["default-vt", "locality-aware"]) {
    try {
      var backend = provider.get(mode);
      backend.start();  // Quick lifecycle check
      backend.stop();
    } catch (Exception e) {
      throw new IllegalStateException(
        "Backend [" + mode + "] failed validation: " + e.getMessage(), e);
    }
  }
}
```

**Error Output:** Include in harness stderr/JFR/structured log:
```
[PREFLIGHT_FAIL] StreamExecutionBackend=[locality-aware] reason=[Method not found: Thread.Builder.OfVirtual.scheduler(...)] 
mark_as=[NOT_RUNNABLE_ON_THIS_JVM] jvm_version=[22.0.1+8]
```

### External (Runner / CI)

```
Preflight Gate: NativeTcpCarrier Peer Availability
├─ Spawn backend server process on loopback (port configurable, default 9999)
├─ Poll for TCP accept on 127.0.0.1:9999 with timeout 5s
├─ If available: record port, proceed
└─ If unavailable: fail with diagnostic and back off
```

**Runner Script Pattern:**

```bash
# Preflight backend availability before launching benchmark
check_backend_ready() {
  local PORT=${1:-9999}
  local TIMEOUT=5
  local ELAPSED=0
  
  echo "[PREFLIGHT] Checking backend TCP on 127.0.0.1:${PORT}..."
  
  while [ $ELAPSED -lt $TIMEOUT ]; do
    if nc -z 127.0.0.1 $PORT 2>/dev/null; then
      echo "[PREFLIGHT] ✓ Backend ready on port ${PORT}"
      return 0
    fi
    sleep 0.1
    ELAPSED=$((ELAPSED + 1))
  done
  
  echo "[PREFLIGHT_FAIL] Backend not ready after ${TIMEOUT}s"
  return 1
}

# In main benchmark entrypoint
check_backend_ready $BACKEND_PORT || {
  echo "Backend preflight failed; exiting"
  exit 1
}
```

---

## 4) Observability Integration Boundaries

### What Belongs in Benchmark Code

**Responsibility: Harness**
- Instantiate JFR event stream and record target events (ThreadCPULoad, VirtualThreadStart, JDKContinuationFork, GC).
- Wrap measurement window with `@BenchmarkState` or equivalent lifecycle annotation.
- Emit pre-measured request latencies per request (wall-clock delta).
- Collect perf-stat output (via `perf stat -o <file>` subprocess).
- Aggregate raw run artifacts (CSV/JSON logs, JFR files, perf-stat dumps).
- **Do NOT:** Post-process statistical aggregation, outlier filtering, CI/CD decision logic.

**Code Pattern:**

```java
// In benchmark run
private void runMeasurement(int concurrency, String backendMode) throws Exception {
  // 1. Start JFR recording
  Recording jfr = new Recording();
  jfr.enable("jdk.ThreadCPULoad");
  jfr.enable("jdk.VirtualThreadStart");
  jfr.enable("jdk.JDKContinuationFork");
  jfr.start();
  
  // 2. Start perf-stat in background
  ProcessBuilder perfProc = new ProcessBuilder(
    "perf", "stat", "-o", perfStatFile, 
    "-e", "cycles,instructions,context-switches,cpu-migrations",
    "sleep", "35"  // 5s warmup + 30s measurement
  );
  Process perf = perfProc.start();
  
  // 3. Execute benchmark (5s warmup + 30s measurement)
  long t0 = System.nanoTime();
  for (int i = 0; i < totalRequests; i++) {
    long reqStart = System.nanoTime();
    issueRequest(backendMode, concurrency);
    long reqEnd = System.nanoTime();
    logLatency((reqEnd - reqStart) / 1_000_000.0);  // millis
  }
  long elapsed = System.nanoTime() - t0;
  
  // 4. Stop JFR and collect file
  jfr.stop();
  jfr.dump(jfrFile);
  
  // 5. Wait for perf-stat to finish
  perf.waitFor();
  
  // 6. Write structured output: JSON blob with paths and metadata
  writeArtifactManifest(jfrFile, perfStatFile, latencyLog, elapsed);
}
```

---

### What Belongs in External Runner

**Responsibility: CI Harness / Analysis Script**
- Filter outlier runs (GC pause > 500ms).
- Compute CV (coefficient of variation) on baseline.
- Aggregate across repetitions: mean, 95% CI, relative error.
- Apply statistical quality gates (RE <= 2% target, <= 10% acceptance).
- Compare E vs C: IPR delta, latency deltas, monotonic scaling check.
- Produce final report with pass/fail/warn status.
- Make merge/deploy decisions.

**Runner Script Pattern:**

```bash
# Post-run aggregation
aggregate_batch() {
  local BATCH_DIR=$1  # Contains all run artifacts
  local OUTPUT_FILE=$2
  
  # 1. Collect all JFR files and perf-stat dumps
  # 2. Parse perf-stat for cycles, instructions, context-switches
  # 3. Parse harness latency logs
  # 4. Filter runs with GC pause > 500ms
  # 5. Compute CV on remaining runs
  # 6. If CV < 3%: pass; else rerun with 10 reps
  # 7. Compute mean ± 95% CI for IPR, throughput, context-switches
  # 8. Write JSON summary with quality metrics
  
  python3 scripts/aggregate_bench_results.py \
    --batch_dir "$BATCH_DIR" \
    --output "$OUTPUT_FILE" \
    --gc_filter 500ms \
    --cv_target 3% \
    --re_target 10%
}
```

---

## 5) Reproducibility and Statistical Quality Gates

### Determinism Gates (In Code)

```
Gate: Fixed Seed and Request Pattern
├─ Seed for request distribution (RNG) logged and embedded in artifact manifest
├─ Request size constant (echo ~100 bytes)
├─ Request rate fixed (not adaptive; fail if rate controller can't maintain target ± 2%)
└─ JVM flags identical across runs (captured in manifest)

Gate: Environment Isolation
├─ Single JVM process per run (no background noise)
├─ Affinity pinning (if configured) applied before warmup
└─ No concurrent workloads on measurement CPU during 37s window (5s warmup + 30s measure + 2s cool)
```

**Code Check:**

```bash
# Verify determinism in harness code
grep -n "setSeed\|nextInt\|nextLong" exeris-kernel-community/src/main/java/*/benchmark/*.java \
  | grep -v "// Fixed seed:" || echo "✓ All RNG calls have fixed seeds"
```

---

### Statistical Quality Gates (External)

```
Gate 1: Baseline Stability (post-filter CV)
├─ Collect >= 5 baseline (default-vt) runs
├─ Remove runs with GC pause > 500ms
├─ If remaining n < 3: FAIL, rerun batch
├─ Compute CV on remaining runs
├─ If CV < 3%: PASS
└─ Else: collect 10 reps and recompute; fail if CV still > 4%

Gate 2: Measurement Quality (Relative Error)
├─ For primary metric (IPR): compute 95% CI
├─ Relative error = (CI width / mean) * 100
├─ Internal target: RE <= 2% (high confidence)
├─ Decision-level acceptance: RE <= 10% (allows lower-confidence batches)
└─ If RE > 2%: flag as "extended measurement needed" but not merge-blocking

Gate 3: No Systematic Drift
├─ Compare first run vs last run (baseline)
├─ If drift > 5%: investigate thermal drift, kernel activity
└─ If unexplained drift > 5%: rerun in isolated CI slice

Gate 4: Request Completion Rate
├─ No more than 5% of requests dropped or timed out
├─ Count per run logged; aggregate reported
└─ Fail batch if dropout > 5% in any run

Gate 5: Constraint Monotonicity
├─ At 16vt: measure baseline efficiency
├─ At 32vt: should be >= 0.95 × 16vt baseline (allow small regression as concurrency adds noise)
├─ At 64vt: should be >= 0.95 × 32vt baseline (monotonic or plateau)
└─ Regression > 5% at higher concurrency: investigate; may indicate harness saturation
```

---

## 6) Merge-Gate Checklist

### PR Code Review Gates

- [ ] `StreamExecutionBackendProvider` SPI discovery code uses `ServiceLoader.load()` with exception wrapping.
- [ ] Harness initialization calls `validateBackendAvailability()` before any measurement.
- [ ] Matrix completeness check runs and confirms all 6 code paths (2 backends × 3 concurrency) reachable.
- [ ] JFR collection setup follows `Recording.enable("jdk.ThreadCPULoad")` pattern; no custom events.
- [ ] Perf-stat subprocess invocation includes `--no-multiplex` flag for deterministic cycle counting.
- [ ] Artifact manifest written as JSON with fields: `{jfrFile, perfStatFile, latencyLog, elapsed, jvmFlags, seed, backend, concurrency, runTime}`.
- [ ] No dependency on external config files; all defaults baked in with override via system properties.

### Unit Test Gates

```bash
# SPI validation test
mvn -pl exeris-kernel-community test -Dtest=StreamExecutionBackendSpiTest
# Matrix code-path test
mvn -pl exeris-kernel-community test -Dtest=LocalityBenchmarkMatrixTest
# JFR setup test
mvn -pl exeris-kernel-community test -Dtest=LocalityBenchmarkJfrSetupTest
# Perf-stat integration test
mvn -pl exeris-kernel-community test -Dtest=LocalityBenchmarkPerfstatTest
```

### Light Integration Gates (Required Before Merge)

```bash
# Run 64vt baseline only; 1 rep; 10s total (warmup + meas + cool reduced)
mvn -pl exeris-kernel-community test -Dtest=LocalityBenchmarkIntegrationTest \
    -Dexeris.benchmark.concurrency=64 \
    -Dexeris.benchmark.backend=default-vt \
    -Dexeris.benchmark.reps=1 \
    -Dexeris.benchmark.warmup=2s \
    -Dexeris.benchmark.measurement=5s \
    -Dexeris.benchmark.collect.jfr=true \
    -Dexeris.benchmark.collect.perfstat=false  # Skip perf-stat for speed

# Expected output: JSON artifact manifest in target/benchmark-artifacts/
# Validate artifact schema
python3 docs/research/loom-continuation-locality/benchmarks/scripts/validate_artifact_schema.py \
  target/benchmark-artifacts/manifest.json
```

### Performance Validation Gates (Optional for Merge; Required for Publication)

```bash
# Full 5-rep baseline + experimental at 64vt only
./docs/research/loom-continuation-locality/benchmarks/scripts/run_benchmark_validated.sh \
    --concurrency 64 \
    --reps 5 \
    --backend default-vt locality-aware \
    --output results/merge-gate-baseline.json \
    --skip-full-matrix  # Skip 16/32vt and contention/alloc scenarios

# Aggregate and check quality gates
python3 docs/research/loom-continuation-locality/benchmarks/scripts/aggregate_results.py \
    --input results/merge-gate-baseline.json \
    --cv_threshold 3 \
    --re_threshold 10 \
    --output results/merge-gate-report.json

# Validate gates; exit 0 if all pass, 1 if any fail
python3 docs/research/loom-continuation-locality/benchmarks/scripts/validate_gates.py \
    results/merge-gate-report.json && echo "✓ Merge gates PASS" || echo "✗ Merge gates FAIL"
```

---

## 7) Verification Commands (Quick Reference)

### For Local Developer (Pre-Push)

```bash
# 1. Validate harness code compiles and SPI discovery works
mvn -pl exeris-kernel-community clean test -Dtest=StreamExecutionBackendSpiTest

# 2. Quick integration smoke test (1 rep, baseline only, no perf-stat)
mvn -pl exeris-kernel-community test -Dtest=LocalityBenchmarkIntegrationTest \
    -Dexeris.benchmark.concurrency=64 -Dexeris.benchmark.reps=1 \
    -Dexeris.benchmark.warmup=2s -Dexeris.benchmark.measurement=5s \
    -Dexeris.benchmark.collect.perfstat=false

# 3. Validate artifact schema
python3 docs/research/loom-continuation-locality/benchmarks/scripts/validate_artifact_schema.py \
    target/benchmark-artifacts/manifest.json && echo "✓ Artifacts valid"
```

### For CI Merge Gate (PR Approval)

```bash
# 1. Code review checklist (automated)
mvn -pl exeris-kernel-community clean install -DskipTests
grep -n "validateBackendAvailability" exeris-kernel-community/src/main/java/*/benchmark/harness/*.java \
  || echo "MISSING: validateBackendAvailability() not found"

# 2. Light integration: 64vt baseline + experimental, 5 reps each
./ci/scripts/run_benchmark_merge_gate.sh --output ci-results/

# 3. Aggregate and validate
python3 docs/research/loom-continuation-locality/benchmarks/scripts/aggregate_results.py \
    --input ci-results/*.json --output ci-results/summary.json
python3 docs/research/loom-continuation-locality/benchmarks/scripts/validate_gates.py \
    ci-results/summary.json --merge_gate_only

# 4. Publish artifact summary to PR comment
cat ci-results/summary.json | python3 -m json.tool > ci-results/summary-pretty.txt
```

### For Nightly / Full Matrix (Publication)

```bash
# Run full 18-point matrix with 5 reps each
./ci/scripts/run_benchmark_full_matrix.sh \
    --output nightly/results/ \
    --profile production \
    --timeout 3h

# Aggregate all results
python3 docs/research/loom-continuation-locality/benchmarks/scripts/aggregate_results.py \
    --input nightly/results/*.json \
    --output nightly/report-full.json \
    --include_all_points

# Generate public report
python3 docs/research/loom-continuation-locality/benchmarks/scripts/publish_report.py \
    nightly/report-full.json --output nightly/report.html
```

---

## 8) Failure Escalation and Recovery

| Failure                        | Owner       | Recovery Path                                        |
|--------------------------------|-------------|------------------------------------------------------|
| SPI preflight fails            | Code review | Fix provider configuration; retest in unit tests     |
| Matrix code-path unreachable   | Code review | Add integration test; verify all 6 paths executable  |
| JFR collection error           | Code review | Check JFR API usage; validate Recording().enable() calls |
| Perf-stat unavailable          | CI config   | Ensure perf-tools installed in CI image              |
| Backend not ready (PREFLIGHT_FAIL) | Runner   | Check backend server process; add 10s retry loop     |
| Baseline CV > 3% (post-filter) | Nightly     | Extend reps to 10; investigate thermal noise; escalate if unexplained |
| Measurement RE > 2% (internal target) | Nightly | Not merge-blocking; flag for extended measurement; retry on less contended hardware |
| No improvement (E >= C) | Research phase | Debug experimental backend; check affinity assumptions; defer to next phase |
| P50 latency regression > 5% | Analysis | Request JFR deep-dive; decide if tradeoff acceptable in hypothesis scope |

---

## 9) Definition of Done (DoDH - Delivery to Hypothesis)

A benchmark code change is merge-ready when:

1. ✅ All PR code review gates pass (validateBackendAvailability, matrix completeness, JFR/perf-stat setup, JSON manifest).
2. ✅ Unit test gates pass (SPI, matrix, JFR setup).
3. ✅ Light integration gate passes (64vt baseline 1 rep, artifact schema valid).
4. ✅ No new compiler/checkstyle warnings.
5. ✅ Artifact manifest schema stable and backward-compatible (if > v1 planned).

Benchmark results (from full matrix runs) are **publication-ready** when:

- ✅ Full 18-point matrix complete with 5+ reps per point (or 10+ if RE > 2%).
- ✅ Baseline stability (post-filter CV) < 3%.
- ✅ Measurement quality (RE) <= 10% for all points.
- ✅ Request dropout < 5% in all runs.
- ✅ Outlier filtering (GC pause > 500ms) applied and documented.
- ✅ JFR files and perf-stat dumps archived.
- ✅ Summary report generated with H1 pass/fail and scaling monotonicity check.
- ✅ Comparison eligible verdict assigned per reporting-rules.md.

---

## Appendix: Testing Commands Template

### Local Validation (copy-paste ready)

```bash
#!/bin/bash
set -e

echo "=== Locality Benchmark: Pre-Push Validation ==="

# 1. Unit tests
echo "[1/3] Running unit tests..."
mvn -pl exeris-kernel-community clean test \
  -Dtest=StreamExecutionBackendSpiTest,LocalityBenchmarkMatrixTest

# 2. Light smoke test
echo "[2/3] Running light integration..."
mvn -pl exeris-kernel-community test -Dtest=LocalityBenchmarkIntegrationTest \
  -Dexeris.benchmark.concurrency=64 \
  -Dexeris.benchmark.reps=1 \
  -Dexeris.benchmark.warmup=2s \
  -Dexeris.benchmark.measurement=5s \
  -Dexeris.benchmark.collect.perfstat=false

# 3. Validate artifacts
echo "[3/3] Validating artifacts..."
python3 docs/research/loom-continuation-locality/benchmarks/scripts/validate_artifact_schema.py \
  target/benchmark-artifacts/manifest.json

echo "✓ All pre-push checks PASS"
exit 0
```

### CI Merge-Gate (Nightly Integration)

```bash
#!/bin/bash
set -e

echo "=== Locality Benchmark: CI Merge Gate ==="

OUTPUT_DIR="ci-results-$(date +%s)"
mkdir -p "$OUTPUT_DIR"

# 1. Compile and code checks
mvn -pl exeris-kernel-community clean install -DskipTests

# 2. Unit tests
mvn -pl exeris-kernel-community test -Dtest=StreamExecutionBackendSpiTest

# 3. Run merge-gate subset (64vt baseline + experimental, 5 reps)
./docs/research/loom-continuation-locality/benchmarks/scripts/run_benchmark_validated.sh \
  --concurrency 64 \
  --reps 5 \
  --backend default-vt locality-aware \
  --output "$OUTPUT_DIR/results.json" \
  --skip-full-matrix

# 4. Aggregate and validate
python3 docs/research/loom-continuation-locality/benchmarks/scripts/aggregate_results.py \
  --input "$OUTPUT_DIR/results.json" \
  --output "$OUTPUT_DIR/summary.json" \
  --merge_gate_only

python3 docs/research/loom-continuation-locality/benchmarks/scripts/validate_gates.py \
  "$OUTPUT_DIR/summary.json" && EXIT=0 || EXIT=1

# 5. Publish summary
if [ $EXIT -eq 0 ]; then
  echo "✓ Merge gates PASS"
  cat "$OUTPUT_DIR/summary.json" | python3 -m json.tool
else
  echo "✗ Merge gates FAIL"
  cat "$OUTPUT_DIR/summary.json" | python3 -m json.tool >&2
fi

exit $EXIT
```
