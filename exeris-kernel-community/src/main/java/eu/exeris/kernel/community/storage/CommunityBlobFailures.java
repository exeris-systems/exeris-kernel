/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * The one place a Community blob failure is both recorded and raised (ADR-056 §Telemetry).
 *
 * <p>Each factory emits the JFR event and returns the exception, so a call site reads
 * {@code throw CommunityBlobFailures.transferFailed(...)}. This deviates from the sibling
 * {@code CommunityIdentityJfrEvents} shape, which emits beside the throw: the blob driver has
 * fourteen failure sites across four classes, and pairing emit-and-throw by hand at each one makes
 * "recorded but not thrown" and "thrown but not recorded" both reachable by omission. Returning the
 * exception removes the pairing entirely.
 *
 * <h2>Single-phase commit</h2>
 * <p>Every event is populated and committed in one shot, never a
 * {@code begin() → blocking-I/O → commit()} straddle. Blob transfers block on a channel and run on
 * virtual threads; a straddled event would bind the carrier-local {@code EventWriter} across a park.
 *
 * <h2>Secret-safe</h2>
 * <p>Carries provider, operation, container, and an exception class/message — never an object key.
 * Keys can carry application data, which is why {@link BlobStorageException} refuses to capture one
 * either.
 *
 * @since 0.11.0
 */
final class CommunityBlobFailures {

    /* default */ static final String PROVIDER_NAME = "ExerisCommunity/FilesystemBlob";

    /* default */ static final String OP_INIT = "init";
    /* default */ static final String OP_UPLOAD = "upload";
    /* default */ static final String OP_DOWNLOAD = "download";
    /* default */ static final String OP_STAT = "stat";
    /* default */ static final String OP_DELETE = "delete";
    /* default */ static final String OP_SIGNED_URL = "signed-url";

    /** Reason recorded when a resolved path would land outside the tenant directory. */
    /* default */ static final String REASON_PATH_ESCAPE = "path-escape";

    /**
     * Reason recorded when the ambient context carries no isolation key.
     *
     * <p>Deliberately not the strategy name. {@code ImmutableStorageContext.GLOBAL} carries strategy
     * {@code SHARED} with an empty key, so reporting the strategy would tell an operator "SHARED" for
     * the one case where the interesting fact is that nothing was scoped at all. The strategy is
     * recorded alongside, in its own field.
     */
    /* default */ static final String REASON_NO_ISOLATION_KEY = "no-isolation-key";

    private static final String NO_MESSAGE = "<none>";

    private CommunityBlobFailures() {
        // Utility holder — not instantiable.
    }

    /**
     * Records and builds an I/O failure.
     *
     * @param operation one of the {@code OP_*} constants — which call was in flight
     * @param container the logical container being transferred to or from
     * @param cause     the underlying I/O failure
     * @return the exception to throw
     */
    /* default */ static BlobStorageException transferFailed(String operation, String container,
                                                             Throwable cause) {
        emitTransferFailed(operation, container, cause);
        return BlobStorageException.transferFailed(PROVIDER_NAME, container, cause);
    }

    /**
     * Records and builds an isolation denial.
     *
     * <p>The security-relevant one of the two: it fires both when the ambient context carries no
     * isolation key and when a resolved path escapes the tenant directory, so an operator can tell
     * unscoped access attempts from containment failures without reading a stack trace.
     *
     * @param operation one of the {@code OP_*} constants — which call was denied
     * @param reason    {@link #REASON_NO_ISOLATION_KEY} or {@link #REASON_PATH_ESCAPE}
     * @param strategy  the {@code IsolationStrategy} name of the ambient context
     * @return the exception to throw
     */
    /* default */ static BlobStorageException isolationDenied(String operation, String reason,
                                                              String strategy) {
        emitIsolationDenied(operation, reason, strategy);
        return BlobStorageException.isolationDenied(PROVIDER_NAME, reason);
    }

    private static void emitTransferFailed(String operation, String container, Throwable cause) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        BlobTransferFailedEvent event = new BlobTransferFailedEvent();
        if (event.isEnabled()) {
            event.providerName = PROVIDER_NAME;
            event.operation = operation;
            event.container = container;
            event.exceptionClass = cause == null ? NO_MESSAGE : cause.getClass().getName();
            event.exceptionMessage = messageOf(cause);
            event.commit();
        }
    }

    private static void emitIsolationDenied(String operation, String reason, String strategy) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        BlobIsolationDeniedEvent event = new BlobIsolationDeniedEvent();
        if (event.isEnabled()) {
            event.providerName = PROVIDER_NAME;
            event.operation = operation;
            event.reason = reason;
            event.strategy = strategy;
            event.commit();
        }
    }

    private static String messageOf(Throwable cause) {
        if (cause == null || cause.getMessage() == null) {
            return NO_MESSAGE;
        }
        return cause.getMessage();
    }

    @Name("eu.exeris.kernel.storage.BlobTransferFailed")
    @Label("Blob Transfer Failed")
    @Description("Emitted when a Community blob store fails an I/O operation — object keys are never "
            + "recorded, since they can carry application data")
    @Category({"Exeris Kernel", "Storage"})
    @StackTrace(false)
    /* default */ static final class BlobTransferFailedEvent extends Event {

        @Label("Provider Name")
        /* default */ String providerName;

        @Label("Operation")
        /* default */ String operation;

        @Label("Container")
        /* default */ String container;

        @Label("Exception Class")
        /* default */ String exceptionClass;

        @Label("Exception Message")
        /* default */ String exceptionMessage;
    }

    @Name("eu.exeris.kernel.storage.BlobIsolationDenied")
    @Label("Blob Isolation Denied")
    @Description("Emitted when a blob operation is refused because the ambient StorageContext carries "
            + "no isolation key, or because a resolved path would escape the tenant directory")
    @Category({"Exeris Kernel", "Storage"})
    @StackTrace(false)
    /* default */ static final class BlobIsolationDeniedEvent extends Event {

        @Label("Provider Name")
        /* default */ String providerName;

        @Label("Operation")
        /* default */ String operation;

        @Label("Reason")
        /* default */ String reason;

        @Label("Isolation Strategy")
        /* default */ String strategy;
    }
}
