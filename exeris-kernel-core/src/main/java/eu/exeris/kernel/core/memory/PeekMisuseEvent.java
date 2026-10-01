/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.memory;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when {@code retain()} or {@code addCloseAction()} is called on a
 * non-owning peek view returned by {@link AbstractLoanedBuffer#peek(long, long)} — a
 * semantic misuse that silently no-ops and could mask a use-after-free condition.
 *
 * <h2>Error Code</h2>
 * <p>Maps to {@code EX-MEM-1003} — peek-view ownership misuse.
 *
 * <h2>JFR-First Contract</h2>
 * <p>{@code @StackTrace(false)} — zero overhead on the hot path when JFR is not recording.
 * The misuse site is identified by the {@code callerMethod} field, not by capturing an
 * expensive stack walk.
 *
 * @since 0.5
 */
@Name("eu.exeris.kernel.core.PeekViewMisuse")
@Label("Peek View Ownership Misuse")
@Category({"Exeris Kernel", "Memory"})
@Description("EX-MEM-1003: retain() or addCloseAction() called on a non-owning peek() view. "
        + "The call was a no-op — potential use-after-free risk.")
@StackTrace(false)
/* default */ final class PeekMisuseEvent extends Event {

    @Label("Error Code")
    /* default */ String errorCode;

    @Label("Caller Method")
    @Description("Name of the method called on the peek view (retain or addCloseAction)")
    /* default */ String callerMethod;

    /**
     * Emits a misuse event if JFR is active and this event type is enabled.
     *
     * @param method the name of the misused method ({@code "retain"} or {@code "addCloseAction"})
     */
    /* default */
    static void emit(String method) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        PeekMisuseEvent evt = new PeekMisuseEvent();
        if (evt.isEnabled()) {
            evt.errorCode = KernelErrorCodes.EX_MEM_1003;
            evt.callerMethod = method;
            evt.commit();
        }
    }
}



