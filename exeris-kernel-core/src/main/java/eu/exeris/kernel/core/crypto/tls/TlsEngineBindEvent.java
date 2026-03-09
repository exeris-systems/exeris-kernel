/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.tls;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted when an {@code OffHeapTlsEngine} binds its {@code SSL*} handle
 * to a file descriptor via {@code SSL_set_fd}.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Zero overhead when JFR is not recording ({@link #isEnabled()} guard prevents
 * allocation of the event object). Never emitted on the I/O hot path — only at
 * the one-time bind point during connection setup.
 *
 * <h2>Diagnostic Value</h2>
 * <p>Correlates a raw {@code SSL*} pointer (visible in native crash dumps and
 * OpenSSL error logs) with the corresponding Java-level file descriptor, enabling
 * post-mortem analysis of TLS bind failures or descriptor leaks.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.tls.EngineBind")
@Label("TLS Engine Bind")
@Description("Emitted when OffHeapTlsEngine binds an SSL* handle to a file descriptor via SSL_set_fd")
@Category({"Exeris Kernel", "TLS"})
@StackTrace(false)
final class TlsEngineBindEvent extends Event {

    @Label("SSL Pointer")
    /* default */ long sslPtr;

    @Label("File Descriptor")
    /* default */ int fileDescriptor;

    @Label("Mode")
    /* default */ String mode; // "SERVER" or "CLIENT"

    /**
     * Emits the bind event.
     *
     * @param sslPtr         raw {@code SSL*} address
     * @param fileDescriptor OS file descriptor bound via {@code SSL_set_fd}
     * @param server         {@code true} for server mode, {@code false} for client
     */
    /* default */ static void emit(long sslPtr, int fileDescriptor, boolean server) {
        TlsEngineBindEvent event = new TlsEngineBindEvent();
        if (event.isEnabled()) {
            event.sslPtr          = sslPtr;
            event.fileDescriptor  = fileDescriptor;
            event.mode            = server ? "SERVER" : "CLIENT";
            event.commit();
        }
    }
}
