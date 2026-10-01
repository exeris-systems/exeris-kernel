/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.diagnostics;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR audit event for out-of-process {@code KernelDiagnostics} calls (ADR-033 §EP step 8).
 *
 * <p>Emitted once per diagnostic method call (codes {@code EX-DIAG-1001..1005}) so operators can audit
 * who introspected the kernel. INFO-flavoured, {@link StackTrace}-free. Single-phase commit
 * (construct → set → {@code commit()}); never a {@code begin()}→blocking→{@code commit()} straddle, which
 * would risk a carrier-bound {@code EventWriter} crash on a virtual thread.
 *
 * @since 0.9
 */
@Name("eu.exeris.kernel.diagnostics.KernelDiagnostics")
@Label("Kernel Diagnostics Call")
@Category({"Exeris Kernel", "Diagnostics"})
@StackTrace(false)
final class CommunityKernelDiagnosticsEvent extends Event {

    @Label("Error Code")
    /* default */ String errorCode;

    @Label("Method")
    /* default */ String method;

    /* default */ static void emit(String errorCode, String method) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        CommunityKernelDiagnosticsEvent evt = new CommunityKernelDiagnosticsEvent();
        if (evt.isEnabled()) {
            evt.errorCode = errorCode;
            evt.method = method;
            evt.commit();
        }
    }
}
