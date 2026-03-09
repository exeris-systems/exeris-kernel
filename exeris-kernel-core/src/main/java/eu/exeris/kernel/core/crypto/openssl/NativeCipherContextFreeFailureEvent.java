/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.openssl;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when {@code SSL_free} invocation fails inside
 * {@code NativeCipherContext.release()}.
 *
 * <h2>JFR-First Principle</h2>
 * <p>Instead of silently swallowing {@code SSL_free} failures or propagating them
 * from a destructor path, every failure is recorded as a zero-cost JFR event.
 * This enables flight-recorder-based diagnostics (native pointer leaks, OpenSSL
 * double-free races) without adding runtime overhead when JFR is not recording.
 *
 * <h2>Cardinality</h2>
 * <p>This event should be extremely rare in production — {@code SSL_free} failing
 * indicates a serious native-heap corruption. A non-zero count in a JFR recording
 * MUST be treated as a P0 incident.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.core.crypto.NativeCipherContextFreeFailure")
@Label("NativeCipherContext SSL_free Failure")
@Category({"Exeris Kernel", "Crypto", "OpenSSL"})
@Description("SSL_free invocation failed during NativeCipherContext release — potential native-heap leak.")
@StackTrace(true)
/* default */ final class NativeCipherContextFreeFailureEvent extends Event {

    @Label("SSL Pointer")
    /* default */ long sslPtr;

    @Label("Exception Class")
    /* default */ String exceptionClass;

    @Label("Exception Message")
    /* default */ String exceptionMessage;

    /**
     * Emits the failure event for a failed {@code SSL_free} call.
     *
     * @param sslPtr  the raw {@code SSL*} address that could not be freed
     * @param thrown  the exception thrown by the native invocation
     */
    /* default */ static void emit(long sslPtr, Throwable thrown) {
        NativeCipherContextFreeFailureEvent evt = new NativeCipherContextFreeFailureEvent();
        evt.sslPtr       = sslPtr;
        evt.exceptionClass   = thrown.getClass().getName();
        evt.exceptionMessage = thrown.getMessage();
        evt.commit();
    }
}
