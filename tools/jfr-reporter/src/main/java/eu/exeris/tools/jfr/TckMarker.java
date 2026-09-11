/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import java.time.Instant;
import java.util.List;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;

/**
 * The window marker {@code JfrAllocationMonitor.measure} writes around the steady-state workload.
 *
 * <p>Two single-phase events per measurement, {@code boundary=start} and {@code boundary=end},
 * committed on the workload thread. The names here are the contract with
 * {@code eu.exeris.kernel.tck.contract.AllocationWindowEvent}; this tool has no dependency on the
 * TCK jar and reads the event by name.
 */
final class TckMarker {

    static final String EVENT_NAME = "eu.exeris.tck.AllocationWindow";
    /** The event's own class, excluded from the contract count exactly as the TCK excludes it. */
    static final String EVENT_CLASS = "eu.exeris.kernel.tck.contract.AllocationWindowEvent";

    static final String F_BOUNDARY = "boundary";
    static final String F_SUBSYSTEM = "subsystem";
    static final String F_TEST_CLASS = "testClass";
    static final String F_ITERATIONS = "iterations";
    static final String F_WORKLOAD_THREAD_ID = "workloadThreadId";
    static final String F_CONTRACT_MODE = "contractMode";
    static final String F_BUDGET = "budgetPerIteration";
    static final String F_BYTES_DELTA = "allocatedBytesDelta";

    static final String START = "start";
    static final String END = "end";

    static final String MODE_ZERO = "zero";
    static final String MODE_BOUNDED = "bounded";
    static final String MODE_UNSPECIFIED = "unspecified";

    /** The sentinel {@code JfrAllocationMonitor.ALLOCATED_BYTES_UNAVAILABLE}. */
    static final long BYTES_UNAVAILABLE = -1L;

    private TckMarker() {}

    /**
     * One marker event as recorded.
     *
     * @param boundary            {@code start} or {@code end}
     * @param at                  the commit time
     * @param eventThreadId       the Java thread id the marker was committed on
     * @param subsystem           subsystem name as the TCK spells it
     * @param testClass           simple name of the test class
     * @param iterations          steady-state iterations
     * @param workloadThreadId    the thread that ran the workload
     * @param contractMode        {@code zero}, {@code bounded} or {@code unspecified}
     * @param budgetPerIteration  the bounded budget, or -1
     * @param allocatedBytesDelta the {@code ThreadMXBean} delta (end only), or -1
     */
    record Boundary(String boundary, Instant at, long eventThreadId, String subsystem, String testClass,
                    int iterations, long workloadThreadId, String contractMode, int budgetPerIteration,
                    long allocatedBytesDelta) {}

    /**
     * A start/end pair.
     *
     * @param start               commit time of the start marker
     * @param end                 commit time of the end marker
     * @param subsystem           subsystem name as the TCK spells it
     * @param testClass           simple name of the test class
     * @param iterations          steady-state iterations
     * @param workloadThreadId    the thread that ran the workload
     * @param contractMode        {@code zero}, {@code bounded} or {@code unspecified}
     * @param budgetPerIteration  the bounded budget, or -1
     * @param allocatedBytesDelta the {@code ThreadMXBean} delta, or -1 when the JVM could not report it
     */
    record Window(Instant start, Instant end, String subsystem, String testClass, int iterations,
                  long workloadThreadId, String contractMode, int budgetPerIteration,
                  long allocatedBytesDelta) {

        boolean contains(long epochMillis) {
            return epochMillis >= start.toEpochMilli() && epochMillis <= end.toEpochMilli();
        }
    }

    static boolean isMarker(RecordedEvent event) {
        return EVENT_NAME.equals(event.getEventType().getName());
    }

    static Boundary read(RecordedEvent e) {
        RecordedThread thread = e.getThread();
        long eventThreadId = thread != null ? thread.getJavaThreadId() : -1L;
        return new Boundary(
                e.getString(F_BOUNDARY),
                e.getStartTime(),
                eventThreadId,
                e.getString(F_SUBSYSTEM),
                e.getString(F_TEST_CLASS),
                e.getInt(F_ITERATIONS),
                e.getLong(F_WORKLOAD_THREAD_ID),
                e.getString(F_CONTRACT_MODE),
                e.getInt(F_BUDGET),
                e.getLong(F_BYTES_DELTA));
    }

    /**
     * Pairs every {@code start} with the first {@code end} that follows it <em>in time</em>.
     *
     * <p>File order is not time order: JFR flushes thread buffers as they fill, and a real
     * recording has been seen with the {@code end} marker at position 681 and the {@code start} at
     * 2408. The boundaries are therefore sorted by commit time first, {@code start} before
     * {@code end} at an equal instant.
     *
     * <p>A recording {@code JfrAllocationMonitor.measure} wrote holds exactly one pair. A JVM-wide
     * recording that outlives several measurements holds one pair per measurement, because JFR
     * writes every enabled event to every active recording — which is why a file with more than
     * one pair is not a subsystem recording.
     *
     * @param boundaries markers in any order
     * @return the windows, possibly empty
     */
    static List<Window> pairAll(List<Boundary> boundaries) {
        List<Boundary> ordered = new java.util.ArrayList<>(boundaries);
        ordered.sort(java.util.Comparator.comparing(Boundary::at)
                .thenComparing(b -> START.equals(b.boundary()) ? 0 : 1));
        List<Window> windows = new java.util.ArrayList<>();
        Boundary start = null;
        for (Boundary b : ordered) {
            if (START.equals(b.boundary())) {
                start = b;
            } else if (start != null && END.equals(b.boundary()) && !b.at().isBefore(start.at())) {
                windows.add(new Window(start.at(), b.at(), start.subsystem(), start.testClass(),
                        start.iterations(), start.workloadThreadId(), start.contractMode(),
                        start.budgetPerIteration(), b.allocatedBytesDelta()));
                start = null;
            }
        }
        return windows;
    }
}
