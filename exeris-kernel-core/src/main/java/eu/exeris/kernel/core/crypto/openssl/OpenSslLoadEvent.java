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
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted exactly once when {@link CoreOpenSslLoader#load} successfully resolves
 * and version-gates an OpenSSL installation.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Zero overhead when JFR is not recording ({@link #isEnabled()} guard). Emitted once per
 * {@code load()} call, on the cold bootstrap path — never on a hot path.
 *
 * <h2>Diagnostic Value</h2>
 * <p>Records <em>which</em> OpenSSL major/minor and shared-object actually loaded, plus the
 * {@code SSL_CTX} construction API in use ({@code SSL_CTX_new_ex}). This makes a multi-version
 * (3.x / 4.x) deployment auditable from a recording: operators can confirm the expected SONAME
 * was selected and that {@code libssl}/{@code libcrypto} agree on a major version. The resolved
 * filesystem paths are diagnostics, not secrets.
 *
 * @since 0.9.0
 */
@Name("eu.exeris.kernel.core.crypto.OpenSslLoad")
@Label("OpenSSL Load")
@Description("Emitted once when CoreOpenSslLoader resolves and version-gates an OpenSSL installation")
@Category({"Exeris Kernel", "Crypto", "OpenSSL"})
@StackTrace(false)
final class OpenSslLoadEvent extends Event {

    @Label("Version Major")
    /* default */ int versionMajor;

    @Label("Version Minor")
    /* default */ int versionMinor;

    @Label("Version Text")
    /* default */ String versionText;

    @Label("SSL_CTX API")
    /* default */ String ctxApi;

    @Label("libssl Path")
    /* default */ String sslPath;

    @Label("libcrypto Path")
    /* default */ String cryptoPath;

    /**
     * Emits a single OpenSSL-load event.
     *
     * @param versionMajor resolved OpenSSL major version
     * @param versionMinor resolved OpenSSL minor version
     * @param versionText  full human-readable version string
     * @param ctxApi       the {@code SSL_CTX} construction API in use ({@code SSL_CTX_new_ex})
     * @param sslPath      the resolved {@code libssl} candidate (path or SONAME)
     * @param cryptoPath   the resolved {@code libcrypto} candidate (path or SONAME)
     */
    /* default */ static void emit(int versionMajor, int versionMinor, String versionText,
                                   String ctxApi, String sslPath, String cryptoPath) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        OpenSslLoadEvent event = new OpenSslLoadEvent();
        if (event.isEnabled()) {
            event.versionMajor = versionMajor;
            event.versionMinor = versionMinor;
            event.versionText  = versionText;
            event.ctxApi       = ctxApi;
            event.sslPath      = sslPath;
            event.cryptoPath   = cryptoPath;
            event.commit();
        }
    }
}
