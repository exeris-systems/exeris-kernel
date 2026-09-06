/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Locale;
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
 * {@snippet lang="java" :
 * @DisplayName("Transport carrier pinning TCK")
 * public abstract class TransportCarrierPinningTck extends AbstractSubsystemCarrierPinningTck {
 *
 *     protected abstract TransportEngine createEngine();
 *
 *     private TransportEngine engine;
 *
 *     @Override protected String subsystemName()        { return "TransportEngine"; }
 *     @Override protected String hotPathDescription()   { return "channel.send(buffer)"; }
 *
 *     @Override protected void bootstrapSubsystem()  { engine = createEngine(); engine.start(); }
 *     @Override protected void runSingleIteration()  { engine.channel().send(PRE_ALLOC_BUFFER); }
 *     @Override protected void tearDownSubsystem()   { engine.close(); }
 * }
 * }
 *
 * @since 0.5
 * @see AbstractSubsystemZeroAllocTck
 * @see JfrPinningMonitor
 */
public abstract class AbstractSubsystemCarrierPinningTck {

    /**
     * Number of virtual threads in the warm-up phase (results discarded).
     */
    private static final int WARMUP_VT_COUNT = 200;

    /**
     * Number of virtual threads in the steady-state (measured) phase.
     */
    private static final int STEADY_VT_COUNT = 1_000;

    /**
     * Exposes the warm-up virtual-thread count to subclasses so that per-VT
     * buffers/slots can be sized without duplicating the constant value.
     * Using this accessor prevents {@link ArrayIndexOutOfBoundsException} if the
     * base constant is ever changed.
     *
     * @return number of warm-up VTs (currently {@value #WARMUP_VT_COUNT})
     */
    protected final int warmupVtCount() {
        return WARMUP_VT_COUNT;
    }

    /**
     * Exposes the steady-state virtual-thread count to subclasses so that per-VT
     * buffers/slots can be sized without duplicating the constant value.
     *
     * @return number of steady-state VTs (currently {@value #STEADY_VT_COUNT})
     */
    protected final int steadyVtCount() {
        return STEADY_VT_COUNT;
    }

    // =========================================================================
    // Template methods — MUST override
    // =========================================================================

    /**
     * Human-readable subsystem name (e.g. {@code "TransportEngine"}, {@code "FlowEngine"}).
     *
     * @return the subsystem name used to label the JFR recording and the assertion message
     */
    protected abstract String subsystemName();

    /**
     * Human-readable hot-path description for assertion messages.
     *
     * @return the operation under test, e.g. {@code "channel.send(buffer)"}
     */
    protected abstract String hotPathDescription();

    /**
     * Bootstrap phase: create all SPI objects.
     *
     * @implSpec Called <em>before</em> JFR recording starts; must not perform any business
     *           logic and must leave the subsystem ready for {@link #runSingleIteration()}.
     */
    protected abstract void bootstrapSubsystem();

    /**
     * Execute one iteration of the hot-path under observation.
     *
     * @implSpec Called from a virtual thread, once per warm-up and once per steady-state
     *           thread, inside the JFR recording window during the steady-state phase. Must
     *           not create SPI objects — only exercise the subsystem {@link #bootstrapSubsystem()}
     *           already prepared.
     */
    protected abstract void runSingleIteration();

    /**
     * Release all resources created in {@link #bootstrapSubsystem()}.
     *
     * @implSpec Called once, after measurement completes.
     */
    protected abstract void tearDownSubsystem();

    // =========================================================================
    // JUnit lifecycle
    // =========================================================================

    /**
     * Runs {@link #bootstrapSubsystem()} before each test, with JFR still dark.
     */
    @BeforeEach
    public final void setUpCarrierPinningTck() {
        bootstrapSubsystem();
    }

    /**
     * Runs {@link #tearDownSubsystem()} after each test.
     */
    @AfterEach
    public final void tearDownCarrierPinningTck() {
        tearDownSubsystem();
    }

    // =========================================================================
    // The Test — single, final, non-overridable
    // =========================================================================

    /**
     * Asserts that running {@link #steadyVtCount()} virtual threads through
     * {@link #runSingleIteration()} produces no {@code jdk.VirtualThreadPinned} event above
     * {@link JfrPinningMonitor#DEFAULT_THRESHOLD_MS} ms, after a discarded warm-up batch of
     * {@link #warmupVtCount()} threads.
     *
     * @throws Exception if bootstrapping, the warm-up batch or the measured batch fails
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("Hot-path virtual threads produce zero carrier pinning events > 20 ms")
    public final void hotPathProducesNoPinning() throws Exception {

        // Phase 1 — warm-up (not measured, JFR dark)
        runVtBatch(WARMUP_VT_COUNT);

        // Phase 2 — steady-state under JFR
        String label = subsystemName().toLowerCase(Locale.ROOT).replace(' ', '-') + "-steady";
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
        CountDownLatch done = new CountDownLatch(vtCount);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < vtCount; i++) {
            Thread.ofVirtual()
                    .name("exeris-pin-" + subsystemName() + "-", i)
                    .start(() -> {
                        try {
                            runSingleIteration();
                        } catch (Throwable _) {
                            errors.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    });
        }

        assertThat(done.await(60, TimeUnit.SECONDS))
                .as("%s: %d VTs must complete within 60 s", subsystemName(), vtCount)
                .isTrue();
        Assertions.assertThat(errors.get()).as("VT batch exceptions").isZero();
    }
}
