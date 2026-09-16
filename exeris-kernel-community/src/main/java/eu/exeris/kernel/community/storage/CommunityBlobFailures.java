/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * {@code throw failures.transferFailed(...)}. This deviates from the sibling
 * {@code CommunityIdentityJfrEvents} shape, which emits beside the throw: the blob drivers have some
 * twenty failure sites across eight classes, and pairing emit-and-throw by hand at each one makes
 * "recorded but not thrown" and "thrown but not recorded" both reachable by omission. Returning the
 * exception removes the pairing entirely.
 *
 * <h2>Why this is an instance</h2>
 * <p>The provider name is bound once, at construction, rather than passed at each of the roughly
 * twenty call sites across both drivers. Bound once, a driver cannot attribute a failure to its
 * sibling by getting an argument wrong, and the shared event names stay shared — an operator filters
 * {@code eu.exeris.kernel.storage.*} and reads {@code providerName} to tell the drivers apart.
 *
 * <h2>Single-phase commit</h2>
 * <p>Every event is populated and committed in one shot, never a
 * {@code begin() → blocking-I/O → commit()} straddle. Blob transfers block on a channel or a socket
 * and run on virtual threads; a straddled event would bind the carrier-local {@code EventWriter}
 * across a park.
 *
 * <h2>Secret-safe</h2>
 * <p>Carries provider, operation, container, and an exception class/message — never an object key.
 * Keys can carry application data, which is why {@link BlobStorageException} refuses to capture one
 * either. Endpoint credentials are never recorded for the same reason.
 *
 * @since 0.11
 */
final class CommunityBlobFailures {

    /** Display name of the filesystem driver. */
    /* default */ static final String FILESYSTEM_PROVIDER = "ExerisCommunity/FilesystemBlob";

    /** Display name of the S3-compatible driver. */
    /* default */ static final String S3_PROVIDER = "ExerisCommunity/S3Blob";

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

    private final String providerName;

    private CommunityBlobFailures(String providerName) {
        this.providerName = providerName;
    }

    /**
     * Binds a failure channel to one driver's display name.
     *
     * @param providerName one of {@link #FILESYSTEM_PROVIDER} or {@link #S3_PROVIDER}
     * @return the channel; never {@code null}
     */
    /* default */ static CommunityBlobFailures forProvider(String providerName) {
        return new CommunityBlobFailures(providerName);
    }

    /**
     * Returns the driver display name this channel reports.
     *
     * @return the provider name
     */
    /* default */ String providerName() {
        return providerName;
    }

    /**
     * Records and builds an I/O failure.
     *
     * @param operation one of the {@code OP_*} constants — which call was in flight
     * @param container the logical container being transferred to or from
     * @param cause     the underlying I/O failure
     * @return the exception to throw
     */
    /* default */ BlobStorageException transferFailed(String operation, String container,
                                                      Throwable cause) {
        BlobTransferFailedEvent.emit(providerName, operation, container, cause);
        return BlobStorageException.transferFailed(providerName, container, cause);
    }

    /**
     * Records and builds an isolation denial.
     *
     * <p>The security-relevant one of the set: it fires both when the ambient context carries no
     * isolation key and when a resolved location escapes the tenant namespace, so an operator can tell
     * unscoped access attempts from containment failures without reading a stack trace.
     *
     * @param operation one of the {@code OP_*} constants — which call was denied
     * @param reason    {@link #REASON_NO_ISOLATION_KEY} or {@link #REASON_PATH_ESCAPE}
     * @param strategy  the {@code IsolationStrategy} name of the ambient context
     * @return the exception to throw
     */
    /* default */ BlobStorageException isolationDenied(String operation, String reason,
                                                       String strategy) {
        BlobIsolationDeniedEvent.emit(providerName, operation, reason, strategy);
        return BlobStorageException.isolationDenied(providerName, reason);
    }

    /**
     * Records and builds a refusal by a remote store.
     *
     * <p>Recorded on the transfer-failed event with the status in place of an exception class: from an
     * operator's side a {@code 403} from the store and a socket reset are the same kind of interruption,
     * and having both on one event means one filter shows the whole picture.
     *
     * @param operation  one of the {@code OP_*} constants
     * @param container  the logical container the request addressed
     * @param statusCode the status the store returned
     * @return the exception to throw
     */
    /* default */ BlobStorageException remoteRefused(String operation, String container,
                                                     int statusCode) {
        BlobTransferFailedEvent.emitRefusal(providerName, operation, container, statusCode);
        return BlobStorageException.remoteRefused(providerName, container, statusCode);
    }

    /**
     * Records and builds a refusal to transfer an object larger than the configured ceiling.
     *
     * @param operation     {@link #OP_UPLOAD} or {@link #OP_DOWNLOAD}
     * @param container     the logical container the request addressed
     * @param declaredBytes the object size asked for
     * @param ceilingBytes  the configured ceiling
     * @return the exception to throw
     */
    /* default */ BlobStorageException exceedsCeiling(String operation, String container,
                                                      long declaredBytes, long ceilingBytes) {
        BlobCeilingExceededEvent.emit(providerName, operation, container, declaredBytes,
                ceilingBytes);
        return BlobStorageException.exceedsCeiling(providerName, declaredBytes, ceilingBytes);
    }

    /**
     * Builds a not-found failure without recording one.
     *
     * <p>The only factory here that emits nothing. An absent object is ordinary control flow — a caller
     * asking whether something exists gets its answer this way — and recording it would bury the
     * failures that matter under lookups that are working exactly as intended.
     *
     * @param container the logical container the lookup resolved into
     * @return the exception to throw
     */
    /* default */ BlobStorageException notFound(String container) {
        return BlobStorageException.notFound(providerName, container);
    }

    @Name("eu.exeris.kernel.storage.BlobTransferFailed")
    @Label("Blob Transfer Failed")
    @Description("Emitted when a Community blob store fails an I/O operation or a remote store refuses "
            + "the request — object keys are never recorded, since they can carry application data")
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

        @Label("Remote Status")
        @Description("HTTP status a remote store returned, or 0 when the failure was local")
        /* default */ int remoteStatus;

        private static void emit(String providerName, String operation, String container,
                                 Throwable cause) {
            if (!FlightRecorder.isInitialized()) {
                return;
            }
            BlobTransferFailedEvent event = new BlobTransferFailedEvent();
            if (event.isEnabled()) {
                event.providerName = providerName;
                event.operation = operation;
                event.container = container;
                event.exceptionClass = cause == null ? NO_MESSAGE : cause.getClass().getName();
                event.exceptionMessage = cause == null || cause.getMessage() == null
                        ? NO_MESSAGE
                        : cause.getMessage();
                event.commit();
            }
        }

        private static void emitRefusal(String providerName, String operation, String container,
                                        int statusCode) {
            if (!FlightRecorder.isInitialized()) {
                return;
            }
            BlobTransferFailedEvent event = new BlobTransferFailedEvent();
            if (event.isEnabled()) {
                event.providerName = providerName;
                event.operation = operation;
                event.container = container;
                event.exceptionClass = NO_MESSAGE;
                event.exceptionMessage = NO_MESSAGE;
                event.remoteStatus = statusCode;
                event.commit();
            }
        }
    }

    @Name("eu.exeris.kernel.storage.BlobIsolationDenied")
    @Label("Blob Isolation Denied")
    @Description("Emitted when a blob operation is refused because the ambient StorageContext carries "
            + "no isolation key, or because a resolved location would escape the tenant namespace")
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

        private static void emit(String providerName, String operation, String reason,
                                 String strategy) {
            if (!FlightRecorder.isInitialized()) {
                return;
            }
            BlobIsolationDeniedEvent event = new BlobIsolationDeniedEvent();
            if (event.isEnabled()) {
                event.providerName = providerName;
                event.operation = operation;
                event.reason = reason;
                event.strategy = strategy;
                event.commit();
            }
        }
    }

    @Name("eu.exeris.kernel.storage.BlobCeilingExceeded")
    @Label("Blob Ceiling Exceeded")
    @Description("Emitted when a driver refuses a transfer larger than its configured single-object "
            + "ceiling — a refusal by policy, not a failure")
    @Category({"Exeris Kernel", "Storage"})
    @StackTrace(false)
    /* default */ static final class BlobCeilingExceededEvent extends Event {

        @Label("Provider Name")
        /* default */ String providerName;

        @Label("Operation")
        /* default */ String operation;

        @Label("Container")
        /* default */ String container;

        @Label("Declared Bytes")
        /* default */ long declaredBytes;

        @Label("Ceiling Bytes")
        /* default */ long ceilingBytes;

        private static void emit(String providerName, String operation, String container,
                                 long declaredBytes, long ceilingBytes) {
            if (!FlightRecorder.isInitialized()) {
                return;
            }
            BlobCeilingExceededEvent event = new BlobCeilingExceededEvent();
            if (event.isEnabled()) {
                event.providerName = providerName;
                event.operation = operation;
                event.container = container;
                event.declaredBytes = declaredBytes;
                event.ceilingBytes = ceilingBytes;
                event.commit();
            }
        }
    }
}
