/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract base for per-subsystem carrier thread pinning verification.
 *
 * <h2>Role in the TCK hierarchy</h2>
 * <p>Mirrors {@link AbstractSubsystemZeroAllocTck} — every subsystem that has a
 * {@code XxxZeroAllocTck} MUST also have a {@code XxxCarrierPinningTck} extending
 * this class. The two monitors together form the dual performance contract:
 * <ol>
 *   <li>{@link JfrAllocationMonitor} — proves the hot-path is heap-allocation-free.</li>
 *   <li>{@link JfrPinningMonitor} — proves the hot-path never pins a carrier thread.</li>
 * </ol>
 *
 * <h2>Protocol</h2>
 * <pre>
 *   bootstrapSubsystem()          // cold path — SPI objects created, NOT measured
 *       ↓
 *   warm-up: {@value #WARMUP_VT_COUNT} VTs each run runSingleIteration()   // discarded
 *       ↓
 *   JFR recording starts
 *   steady-state: {@value #STEADY_VT_COUNT} VTs each run runSingleIteration()
 *   JFR recording stops
 *       ↓
 *   assertNoPinning()             // zero jdk.VirtualThreadPinned > 20 ms
 *       ↓
 *   tearDownSubsystem()
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * \@DisplayName("Transport carrier pinning TCK")
 * public abstract class TransportCarrierPinningTck extends AbstractSubsystemCarrierPinningTck {
 *
 *     protected abstract TransportEngine createEngine();
 *
 *     private TransportEngine engine;
 *
 *     \@Override protected String subsystemName()        { return "TransportEngine"; }
 *     \@Override protected String hotPathDescription()   { return "channel.send(buffer)"; }
 *
 *     \@Override protected void bootstrapSubsystem()  { engine = createEngine(); engine.start(); }
 *     \@Override protected void runSingleIteration()  { engine.channel().send(PRE_ALLOC_BUFFER); }
 *     \@Override protected void tearDownSubsystem()   { engine.close(); }
 * }
 * }</pre>
 *
 * @since 0.5.0
 * @see AbstractSubsystemZeroAllocTck
 * @see JfrPinningMonitor
 */
public abstract class AbstractSubsystemCarrierPinningTck {

    /** Number of virtual threads in the warm-up phase (results discarded). */
    private static final int WARMUP_VT_COUNT  = 200;

    /** Number of virtual threads in the steady-state (measured) phase. */
    private static final int STEADY_VT_COUNT  = 1_000;

    // =========================================================================
    // Template methods — MUST override
    // =========================================================================

    /** Human-readable subsystem name (e.g. {@code "TransportEngine"}, {@code "FlowEngine"}). */
    protected abstract String subsystemName();

    /** Human-readable hot-path description for assertion messages. */
    protected abstract String hotPathDescription();

    /**
     * Bootstrap phase: create all SPI objects.
     * Called <em>before</em> JFR recording starts. Must not perform any business logic.
     */
    protected abstract void bootstrapSubsystem();

    /**
     * Execute one iteration of the hot-path under observation.
     * Called N times inside the JFR recording window from virtual threads.
     * Must NOT create SPI objects — only exercise an already-bootstrapped subsystem.
     */
    protected abstract void runSingleIteration();

    /**
     * Release all resources created in {@link #bootstrapSubsystem()}.
     * Called after measurement completes.
     */
    protected abstract void tearDownSubsystem();

    // =========================================================================
    // JUnit lifecycle
    // =========================================================================

    @BeforeEach
    final void setUpCarrierPinningTck() {
        bootstrapSubsystem();
    }

    @AfterEach
    final void tearDownCarrierPinningTck() {
        tearDownSubsystem();
    }

    // =========================================================================
    // The Test — single, final, non-overridable
    // =========================================================================

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("Hot-path virtual threads produce zero carrier pinning events > 20 ms")
    final void hotPathProducesNoPinning() throws Exception {

        // Phase 1 — warm-up (not measured, JFR dark)
        runVtBatch(WARMUP_VT_COUNT);

        // Phase 2 — steady-state under JFR
        String label = subsystemName().toLowerCase(java.util.Locale.ROOT).replace(' ', '-') + "-steady";
        JfrPinningMonitor.Result result = JfrPinningMonitor.measure(
                JfrPinningMonitor.Config.defaults(label),
                () -> runVtBatch(STEADY_VT_COUNT));

        JfrPinningMonitor.assertNoPinning(result,
                subsystemName() + " — " + hotPathDescription());
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private void runVtBatch(int vtCount) throws InterruptedException {
        CountDownLatch done  = new CountDownLatch(vtCount);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < vtCount; i++) {
            Thread.ofVirtual()
                  .name("exeris-pin-" + subsystemName() + "-", i)
                  .start(() -> {
                      try { runSingleIteration(); }
                      catch (Throwable _) { errors.incrementAndGet(); }
                      finally { done.countDown(); }
                  });
        }

        assertThat(done.await(60, TimeUnit.SECONDS))
                .as("%s: %d VTs must complete within 60 s", subsystemName(), vtCount)
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(errors.get()).as("VT batch exceptions").isZero();
    }
}

