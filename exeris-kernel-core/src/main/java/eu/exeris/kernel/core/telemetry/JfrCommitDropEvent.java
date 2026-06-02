/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.telemetry;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when {@link JfrEventCommitter} drops an event because its ring overflowed.
 *
 * <h2>Hot-path discipline</h2>
 * <p>Emitted only on the drop path (off the steady-state hot path), so a single allocation here
 * is acceptable. {@link StackTrace @StackTrace(false)} keeps it lightweight.
 *
 * @since 0.7.1
 */
@Name("eu.exeris.kernel.telemetry.JfrCommitDrop")
@Label("JFR Commit Drop")
@Description("Emitted when the off-thread JFR committer ring overflows and an event is dropped")
@Category({"Exeris Kernel", "Telemetry"})
@StackTrace(false)
public final class JfrCommitDropEvent extends Event {

    @Label("Committer")
    private String committer;

    @Label("Dropped Count")
    private long droppedCount;

    @Label("Ring Capacity")
    private int capacity;

    /**
     * Records a single drop. Constructed and committed inline here — the drop path is cold and
     * runs on the producer thread, but it is rare; the committer's own steady-state commits run
     * on the platform thread.
     */
    public static void emit(String committer, long droppedCount, int capacity) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        JfrCommitDropEvent event = new JfrCommitDropEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.committer = committer;
        event.droppedCount = droppedCount;
        event.capacity = capacity;
        event.commit();
    }
}
