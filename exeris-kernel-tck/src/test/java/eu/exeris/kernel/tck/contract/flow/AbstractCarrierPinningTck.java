/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.tck.contract.JfrPinningMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for carrier thread pinning verification.
 *
 * <h2>Contract (#24)</h2>
 * <p>Verifies that no operation in an Exeris implementation pins a carrier thread
 * for longer than {@value JfrPinningMonitor#DEFAULT_THRESHOLD_MS} ms.
 * Uses {@code jdk.VirtualThreadPinned} JFR events via {@link JfrPinningMonitor} —
 * no new SPI invented.
 *
 * <h2>Three test suites</h2>
 * <ol>
 *   <li><b>VT Spike</b> — 10 000 VTs concurrently exercise one subsystem workload.</li>
 *   <li><b>Subsystem Audit</b> — each of the nine subsystems probed in isolation (100 VTs).</li>
 *   <li><b>Native Workloads</b> — implementation-declared workloads (Enterprise tier).
 *       Community returns {@code List.of()} → suite is skipped.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunityCarrierPinningTest extends AbstractCarrierPinningTck {
 *     \@Override
 *     protected Runnable subsystemWorkload(SubsystemUnderTest s) {
 *         return switch (s) {
 *             case FLOW        -> () -> flowEngine.scheduler().schedule(plan, ctx);
 *             case PERSISTENCE -> () -> eventStore.pollPending(1);
 *             // ...
 *         };
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 * @see JfrPinningMonitor
 * @see AbstractFlowEngineTck
 */
@DisplayName("Carrier Pinning TCK")
public abstract class AbstractCarrierPinningTck {

    private static final int VT_SPIKE_COUNT  = 10_000;
    private static final int AUDIT_VT_COUNT  = 100;
    private static final int NATIVE_VT_COUNT = 50;

    // =========================================================================
    // Template methods
    // =========================================================================

    /**
     * Returns a {@link Runnable} exercising one hot-path unit of work for the given
     * subsystem via its existing SPI contracts. Subsystems must be fully started
     * before the test runs — no setup/teardown inside the workload.
     */
    protected abstract Runnable subsystemWorkload(SubsystemUnderTest subsystem);

    /**
     * Returns implementation-declared native workloads to audit for carrier pinning.
     *
     * <p>TCK is <b>implementation-blind</b> — it has no knowledge of any native library.
     * The subclass provides labels and workloads. Return {@code List.of()} (default)
     * to skip the native suite entirely.
     *
     * @return ordered list of named native workloads; never {@code null}
     */
    protected List<NativeWorkload> nativeWorkloads() {
        return List.of();
    }

    // =========================================================================
    // Test 1 — VT Spike (10 000 VTs)
    // =========================================================================

    @Nested
    @DisplayName("VT Spike — 10 000 virtual threads")
    class VtSpikePinningTest {

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("10 000 VTs produce zero carrier pinning events > 20 ms")
        void zeroPinningUnder10kVtSpike() throws Exception {
            Runnable workload    = subsystemWorkload(SubsystemUnderTest.TRANSPORT);
            CountDownLatch ready = new CountDownLatch(VT_SPIKE_COUNT);
            CountDownLatch go    = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(VT_SPIKE_COUNT);
            AtomicInteger errors = new AtomicInteger();

            JfrPinningMonitor.Result result = JfrPinningMonitor.measure(
                    JfrPinningMonitor.Config.defaults("vt-spike-10k"),
                    () -> {
                        for (int i = 0; i < VT_SPIKE_COUNT; i++) {
                            Thread.ofVirtual().name("exeris-spike-", i).start(() -> {
                                ready.countDown();
                                try {
                                    go.await();
                                } catch (InterruptedException _) {
                                    Thread.currentThread().interrupt();
                                    errors.incrementAndGet();
                                    done.countDown();
                                    return;
                                }
                                try { workload.run(); }
                                catch (Exception _) { errors.incrementAndGet(); }
                                finally { done.countDown(); }
                            });
                        }
                        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
                        go.countDown();
                        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
                    });

            JfrPinningMonitor.assertNoPinning(result, "VT-Spike-10k");
            assertThat(errors.get()).as("No VT may throw unexpectedly during spike").isZero();
        }
    }

    // =========================================================================
    // Test 2 — Subsystem Audit (100 VTs per subsystem)
    // =========================================================================

    @Nested
    @DisplayName("Subsystem Pinning Audit — all nine subsystems")
    class SubsystemPinningAuditTest {

        @Test @Timeout(30) @DisplayName("Memory: no carrier pinning")
        void memory()      throws Exception { audit(SubsystemUnderTest.MEMORY); }

        @Test @Timeout(30) @DisplayName("Transport: no carrier pinning")
        void transport()   throws Exception { audit(SubsystemUnderTest.TRANSPORT); }

        @Test @Timeout(30) @DisplayName("Security: no carrier pinning")
        void security()    throws Exception { audit(SubsystemUnderTest.SECURITY); }

        @Test @Timeout(30) @DisplayName("Persistence: no carrier pinning")
        void persistence() throws Exception { audit(SubsystemUnderTest.PERSISTENCE); }

        @Test @Timeout(30) @DisplayName("Graph: no carrier pinning")
        void graph()       throws Exception { audit(SubsystemUnderTest.GRAPH); }

        @Test @Timeout(30) @DisplayName("Events: no carrier pinning")
        void events()      throws Exception { audit(SubsystemUnderTest.EVENTS); }

        @Test @Timeout(30) @DisplayName("Flow: no carrier pinning")
        void flow()        throws Exception { audit(SubsystemUnderTest.FLOW); }

        @Test @Timeout(30) @DisplayName("Bootstrap: no carrier pinning")
        void bootstrap()   throws Exception { audit(SubsystemUnderTest.BOOTSTRAP); }

        @Test @Timeout(30) @DisplayName("Config: no carrier pinning")
        void config()      throws Exception { audit(SubsystemUnderTest.CONFIG); }

        private void audit(SubsystemUnderTest s) throws Exception {
            Runnable workload    = subsystemWorkload(s);
            CountDownLatch done  = new CountDownLatch(AUDIT_VT_COUNT);
            AtomicInteger errors = new AtomicInteger();

            JfrPinningMonitor.Result result = JfrPinningMonitor.measure(
                    JfrPinningMonitor.Config.defaults("subsystem-audit-" + s.name().toLowerCase()),
                    () -> {
                        for (int i = 0; i < AUDIT_VT_COUNT; i++) {
                            Thread.ofVirtual().name("exeris-audit-" + s + "-", i).start(() -> {
                                try { workload.run(); }
                                catch (Exception _) { errors.incrementAndGet(); }
                                finally { done.countDown(); }
                            });
                        }
                        assertThat(done.await(20, TimeUnit.SECONDS))
                                .as("%s: 100 VTs must complete within 20 s", s)
                                .isTrue();
                    });

            JfrPinningMonitor.assertNoPinning(result, s.name());
            org.assertj.core.api.Assertions.assertThat(errors.get()).as("Audit workload exceptions").isZero();
        }
    }

    // =========================================================================
    // Test 3 — Native Workloads (implementation-declared)
    // =========================================================================

    @Nested
    @DisplayName("Native Workload Pinning — implementation-declared workloads")
    class NativeWorkloadPinningTest {

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("All declared native workloads produce zero carrier pinning > 20 ms")
        void noNativeCallPinning() throws Exception {
            List<NativeWorkload> workloads = nativeWorkloads();
            if (workloads.isEmpty()) return;

            for (NativeWorkload nw : workloads) {
                CountDownLatch done  = new CountDownLatch(NATIVE_VT_COUNT);
                AtomicInteger errors = new AtomicInteger();

                JfrPinningMonitor.Result result = JfrPinningMonitor.measure(
                        JfrPinningMonitor.Config.defaults(
                                "native-" + nw.label().toLowerCase().replace(' ', '-')),
                        () -> {
                            for (int i = 0; i < NATIVE_VT_COUNT; i++) {
                                Thread.ofVirtual()
                                      .name("exeris-native-" + nw.label() + "-", i)
                                      .start(() -> {
                                          try { nw.workload().run(); }
                                          catch (Exception _) { errors.incrementAndGet(); }
                                          finally { done.countDown(); }
                                      });
                            }
                            assertThat(done.await(20, TimeUnit.SECONDS))
                                    .as("Native workload '%s': %d VTs must finish within 20 s",
                                            nw.label(), NATIVE_VT_COUNT)
                                    .isTrue();
                            org.assertj.core.api.Assertions.assertThat(errors.get()).as("Native workload '%s' exceptions", nw.label()).isZero();
                        });

                JfrPinningMonitor.assertNoPinning(result, "NativeWorkload-" + nw.label());
            }
        }
    }

    // =========================================================================
    // Types
    // =========================================================================

    /** All nine Exeris Kernel subsystems covered by the pinning audit. */
    public enum SubsystemUnderTest {
        MEMORY, TRANSPORT, SECURITY, PERSISTENCE, GRAPH, EVENTS, FLOW, BOOTSTRAP, CONFIG
    }

    /**
     * A named native workload for the Native Workload Pinning suite.
     *
     * <p>TCK is blind to what the workload does — only that it runs on virtual threads
     * and must produce no carrier pinning. {@code label} appears in JFR report filenames
     * and assertion messages only.
     *
     * @param label    human-readable name (e.g. {@code "TLS Handshake"})
     * @param workload one unit of native work executed per virtual thread
     */
    public record NativeWorkload(String label, Runnable workload) {}
}

