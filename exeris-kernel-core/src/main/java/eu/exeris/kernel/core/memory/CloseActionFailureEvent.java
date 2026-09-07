/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.memory;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when a {@link AbstractLoanedBuffer} close action throws
 * an unexpected exception. Close actions must never propagate, but failures
 * must be observable for post-mortem analysis.
 *
 * <h2>JFR-First Principle</h2>
 * <p>Instead of silently swallowing exceptions with an empty catch block,
 * every close-action failure is recorded as a JFR event with full exception
 * details. This enables flight-recorder-based diagnostics without runtime
 * overhead (JFR events are zero-cost when not recorded).
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.core.CloseActionFailure")
@Label("LoanedBuffer Close Action Failure")
@Category({"Exeris Kernel", "Memory"})
@Description("A close action on a LoanedBuffer threw an exception that was suppressed.")
@StackTrace(true)
/* default */ final class CloseActionFailureEvent extends Event {

    @Label("Exception Class")
    /* default */ String exceptionClass;

    @Label("Exception Message")
    /* default */ String exceptionMessage;

    /**
     * Emits a failure event for a close action that threw.
     *
     * @param thrown the exception thrown by the close action
     */
    /* default */
    static void emit(RuntimeException thrown) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CloseActionFailureEvent evt = new CloseActionFailureEvent();
        if (evt.isEnabled()) {
            evt.exceptionClass = thrown.getClass().getName();
            evt.exceptionMessage = thrown.getMessage();
            evt.commit();
        }
    }
}
