/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * The window marker {@link JfrAllocationMonitor#measure} writes around the steady-state workload.
 *
 * <p>Two single-phase events per measurement — {@code boundary = "start"} and
 * {@code boundary = "end"} — committed on the workload thread. They let a reader of the recording
 * ({@code tools/jfr-reporter}, which reads this event by name and has no dependency on this jar)
 * know which subsystem and test class produced it, which thread ran the workload, what contract was
 * asserted, and the exact {@code ThreadMXBean} bytes delta the monitor measured, without parsing
 * the file name. Because JFR writes every enabled event to every active recording, the same pair
 * also lands in any JVM-wide recording open at the time, one pair per measurement that JVM ran; a
 * reader pairs the two by {@link #measurementId}, since a recording's file order is not its time
 * order and several measurements can share a subsystem, a test class and a thread.
 *
 * <p><b>Allocation ordering invariant.</b> Both event objects are allocated <em>before</em> the
 * steady-state recording starts, and both commits happen <em>outside</em> the
 * {@code ThreadMXBean.getThreadAllocatedBytes} bracket. A zero-allocation contract therefore never
 * sees the marker: not as an {@code eu.exeris.*} sample (the objects predate the recording), and not
 * in the bytes delta (the commits sit outside it). {@code AllocationWindowMarkerSelfTest} pins this
 * against a zero-allocation assertion.
 *
 * @since 0.12
 */
@Name("eu.exeris.tck.AllocationWindow")
@Label("TCK allocation measurement window")
@Category({"Exeris", "TCK"})
@StackTrace(false)
public final class AllocationWindowEvent extends Event {

    /**
     * Creates an unset marker; {@link JfrAllocationMonitor} fills every field before committing.
     *
     * @since 0.12
     */
    public AllocationWindowEvent() {
        super();
    }

    /**
     * {@link JfrAllocationMonitor#BOUNDARY_START}, {@link JfrAllocationMonitor#BOUNDARY_END} or
     * {@link JfrAllocationMonitor#BOUNDARY_ABORT}.
     *
     * @since 0.12
     */
    @Label("Boundary")
    public String boundary;

    /**
     * Identifies the measurement this marker belongs to; the {@code start} and the closing marker
     * of one {@link JfrAllocationMonitor#measure} call carry the same value, and no two calls in a
     * JVM carry the same one.
     *
     * <p>Without it a reader has only {@code (subsystem, testClass, workloadThreadId)}, which is
     * not a key: a {@code @Nested} contract class reports its own simple name, so two unrelated
     * TCKs already write the same triple, and one test measuring twice writes it twice more.
     *
     * @since 0.12
     */
    @Label("Measurement id")
    public long measurementId;

    /**
     * Subsystem name as the TCK spells it, e.g. {@code EventBus}.
     *
     * @since 0.12
     */
    @Label("Subsystem")
    public String subsystem;

    /**
     * Simple name of the test class that ran the measurement.
     *
     * @since 0.12
     */
    @Label("Test class")
    public String testClass;

    /** Steady-state iterations the workload ran. */
    @Label("Iterations")
    public int iterations;

    /** {@link Thread#threadId()} of the thread the workload ran on. */
    @Label("Workload thread id")
    public long workloadThreadId;

    /** {@code "zero"}, {@code "bounded"} or {@code "unspecified"}. */
    @Label("Contract mode")
    public String contractMode;

    /** The bounded budget per iteration, or {@code -1} when the mode has none. */
    @Label("Budget per iteration")
    public int budgetPerIteration;

    /**
     * The bounded-bytes budget per iteration, or {@code -1} when the mode has none.
     *
     * @since 0.12
     */
    @Label("Budget bytes per iteration")
    public double budgetBytesPerIteration;

    /**
     * On the {@code end} marker, the {@code ThreadMXBean} allocated-bytes delta across the workload;
     * {@link JfrAllocationMonitor#ALLOCATED_BYTES_UNAVAILABLE} on the {@code start} marker and when
     * the JVM cannot report per-thread allocation.
     */
    @Label("Allocated bytes delta")
    public long allocatedBytesDelta;
}
