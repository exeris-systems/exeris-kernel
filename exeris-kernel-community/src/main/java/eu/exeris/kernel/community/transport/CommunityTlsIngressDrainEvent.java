/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * PERF-RESEARCH instrumentation — distribution of TLS records drained per readable event.
 *
 * <h2>Why this exists</h2>
 * <p>Confirm-experiment (1) for the TLS-vs-plaintext CPU gap: the per-record off-heap segment
 * acquire/return cycle in {@code readIngress} dominates only if the fd-owner drain loop typically
 * yields {@code >= 2} records per readable event. If the mean is {@code ~1.0} the records are
 * already one-per-event and ingress coalescing buys nothing — the cost is reactor/selector
 * iteration frequency instead. {@code emptyEvents} (drained {@code == 0}) measures wasted wakeups,
 * which is direct evidence for the reactor-frequency hypothesis.
 *
 * <p>Cumulative snapshots are emitted periodically (and once on shutdown) so the last event in the
 * JFR file holds the full distribution even under SIGKILL. <strong>This is research-branch-only
 * instrumentation and is not intended to merge.</strong>
 *
 * @since 0.9.0
 */
@Name("eu.exeris.kernel.transport.CommunityTlsIngressDrain")
@Label("Community TLS Ingress Drain Distribution")
@Description("Cumulative distribution of TLS records drained per readable event (PERF-RESEARCH)")
@Category({"Exeris Kernel", "Transport"})
@StackTrace(false)
final class CommunityTlsIngressDrainEvent extends Event {

    @Label("Readable Events")
    /* default */ long readableEvents;

    @Label("Total Records Drained")
    /* default */ long totalRecords;

    @Label("Mean Records Per Event")
    /* default */ double meanRecordsPerEvent;

    @Label("Multi-Record Events (>=2) %")
    /* default */ double pctMultiRecord;

    @Label("Empty Events (0 records)")
    /* default */ long emptyEvents;

    @Label("Capped Events (== max)")
    /* default */ long cappedEvents;

    @Label("Histogram (bucket:count, ...)")
    /* default */ String histogram;

    @Label("Final Snapshot")
    /* default */ boolean finalSnapshot;

    /* default */ static void emit(long readableEvents,
                                   long totalRecords,
                                   double meanRecordsPerEvent,
                                   double pctMultiRecord,
                                   long emptyEvents,
                                   long cappedEvents,
                                   String histogram,
                                   boolean finalSnapshot) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityTlsIngressDrainEvent event = new CommunityTlsIngressDrainEvent();
        if (event.isEnabled()) {
            event.readableEvents = readableEvents;
            event.totalRecords = totalRecords;
            event.meanRecordsPerEvent = meanRecordsPerEvent;
            event.pctMultiRecord = pctMultiRecord;
            event.emptyEvents = emptyEvents;
            event.cappedEvents = cappedEvents;
            event.histogram = histogram;
            event.finalSnapshot = finalSnapshot;
            event.commit();
        }
    }
}
