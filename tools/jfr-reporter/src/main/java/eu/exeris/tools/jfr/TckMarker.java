/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;

/**
 * The window marker {@code JfrAllocationMonitor.measure} writes around the steady-state workload.
 *
 * <p>Two single-phase events per measurement: {@code boundary=start}, and {@code boundary=end} or
 * {@code boundary=abort} depending on whether the workload completed. Both are committed on the
 * workload thread and carry the same {@code measurementId}. The names here are the contract with
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
    static final String F_MEASUREMENT_ID = "measurementId";

    static final String START = "start";
    static final String END = "end";
    /** The workload threw: the window is truncated, so it is not a measurement of anything. */
    static final String ABORT = "abort";

    /** {@link #F_MEASUREMENT_ID} absent — a recording written before the field existed. */
    static final long NO_MEASUREMENT_ID = Long.MIN_VALUE;

    static final String MODE_ZERO = "zero";
    static final String MODE_BOUNDED = "bounded";
    static final String MODE_UNSPECIFIED = "unspecified";

    /** The sentinel {@code JfrAllocationMonitor.ALLOCATED_BYTES_UNAVAILABLE}. */
    static final long BYTES_UNAVAILABLE = -1L;

    private TckMarker() {}

    /**
     * One marker event as recorded.
     *
     * @param boundary            {@code start}, {@code end} or {@code abort}
     * @param at                  the commit time
     * @param eventThreadId       the Java thread id the marker was committed on
     * @param measurementId       the measurement both boundaries belong to, or
     *                            {@link #NO_MEASUREMENT_ID} on a recording that predates the field
     * @param subsystem           subsystem name as the TCK spells it
     * @param testClass           simple name of the test class
     * @param iterations          steady-state iterations
     * @param workloadThreadId    the thread that ran the workload
     * @param contractMode        {@code zero}, {@code bounded} or {@code unspecified}
     * @param budgetPerIteration  the bounded budget, or -1
     * @param allocatedBytesDelta the {@code ThreadMXBean} delta (end only), or -1
     */
    record Boundary(String boundary, Instant at, long eventThreadId, long measurementId, String subsystem,
                    String testClass, int iterations, long workloadThreadId, String contractMode,
                    int budgetPerIteration, long allocatedBytesDelta) {}

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
     * @param measurementId       the measurement the pair belongs to, or {@link #NO_MEASUREMENT_ID}
     */
    record Window(Instant start, Instant end, String subsystem, String testClass, int iterations,
                  long workloadThreadId, String contractMode, int budgetPerIteration,
                  long allocatedBytesDelta, long measurementId) {

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
                e.hasField(F_MEASUREMENT_ID) ? e.getLong(F_MEASUREMENT_ID) : NO_MEASUREMENT_ID,
                e.getString(F_SUBSYSTEM),
                e.getString(F_TEST_CLASS),
                e.getInt(F_ITERATIONS),
                e.getLong(F_WORKLOAD_THREAD_ID),
                e.getString(F_CONTRACT_MODE),
                e.getInt(F_BUDGET),
                e.getLong(F_BYTES_DELTA));
    }

    /**
     * The result of pairing one recording's boundaries.
     *
     * @param windows  the complete pairs, oldest first
     * @param unpaired boundaries that never found their counterpart
     * @param aborted  the {@code abort} boundaries: measurements whose workload threw
     */
    record Pairing(List<Window> windows, List<Boundary> unpaired, List<Boundary> aborted) {

        static Pairing of(List<Window> windows) {
            return new Pairing(windows, List.of(), List.of());
        }

        /** Every boundary in the file accounted for: no orphan, no truncated window. */
        boolean clean() {
            return unpaired.isEmpty() && aborted.isEmpty();
        }
    }

    /**
     * Pairs each measurement's {@code start} with its own closing boundary.
     *
     * <p>Boundaries are grouped by {@link #F_MEASUREMENT_ID} first. Nothing else identifies a
     * measurement: the security contracts run inside {@code @Nested} classes, so two unrelated TCKs
     * write the same {@code (subsystem, testClass, workloadThreadId)} triple, and one test that
     * measures twice writes its triple twice. A recording written before the field existed falls
     * back to that triple plus the config, which is enough while such a recording holds one
     * measurement per key — and when it does not, the pair below reports the leftovers rather than
     * inventing a window.
     *
     * <p>Within a group, file order is not time order: JFR flushes thread buffers as they fill, and
     * a real recording has been seen with the {@code end} marker at position 681 and the
     * {@code start} at 2408. Boundaries are therefore sorted by commit time, {@code start} first at
     * an equal instant.
     *
     * <p>A second {@code start} while one is pending no longer overwrites it silently: the pending
     * one is reported as unpaired. That is the defect that let a JVM-wide recording collapse to
     * exactly one window and be read as a single subsystem's measurement.
     *
     * @param boundaries markers in any order
     * @return the windows and whatever could not be paired
     */
    static Pairing pairAll(List<Boundary> boundaries) {
        Map<String, List<Boundary>> byMeasurement = new LinkedHashMap<>();
        for (Boundary b : boundaries) {
            byMeasurement.computeIfAbsent(correlationKey(b), k -> new ArrayList<>()).add(b);
        }

        List<Window> windows = new ArrayList<>();
        List<Boundary> unpaired = new ArrayList<>();
        List<Boundary> aborted = new ArrayList<>();
        for (List<Boundary> group : byMeasurement.values()) {
            group.sort(Comparator.comparing(Boundary::at)
                    .thenComparing(b -> START.equals(b.boundary()) ? 0 : 1));
            Boundary start = null;
            for (Boundary b : group) {
                if (START.equals(b.boundary())) {
                    if (start != null) {
                        unpaired.add(start);
                    }
                    start = b;
                } else if (ABORT.equals(b.boundary())) {
                    aborted.add(b);
                    start = null;
                } else if (END.equals(b.boundary()) && start != null) {
                    windows.add(new Window(start.at(), b.at(), start.subsystem(), start.testClass(),
                            start.iterations(), start.workloadThreadId(), start.contractMode(),
                            start.budgetPerIteration(), b.allocatedBytesDelta(), start.measurementId()));
                    start = null;
                } else {
                    unpaired.add(b);
                }
            }
            if (start != null) {
                unpaired.add(start);
            }
        }
        windows.sort(Comparator.comparing(Window::start));
        return new Pairing(List.copyOf(windows), List.copyOf(unpaired), List.copyOf(aborted));
    }

    private static String correlationKey(Boundary b) {
        if (b.measurementId() != NO_MEASUREMENT_ID) {
            return "id:" + b.measurementId();
        }
        return "legacy:" + b.subsystem() + '|' + b.testClass() + '|' + b.workloadThreadId()
                + '|' + b.iterations() + '|' + b.contractMode() + '|' + b.budgetPerIteration();
    }
}
