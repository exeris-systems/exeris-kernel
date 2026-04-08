/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions.persistence;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when the persistence subsystem encounters a fatal or recoverable error.
 *
 * <h2>Zero-Allocation Telemetry Contract</h2>
 * <p>All domain context is passed as raw {@code Object[]} args to
 * {@link ExerisKernelException#rawArgs()} — no String formatting on the hot path.
 * The Enterprise Glass-Box tier serialises these args directly to a binary struct.
 *
 * <h2>Error Codes</h2>
 * <ul>
 *   <li>{@value KernelErrorCodes#EX_PERS_5001} — Bootstrap failure</li>
 *   <li>{@value KernelErrorCodes#EX_PERS_5002} — Connection acquisition failure</li>
 *   <li>{@value KernelErrorCodes#EX_PERS_5003} — Query execution failure</li>
 *   <li>{@value KernelErrorCodes#EX_PERS_5004} — Authentication failure</li>
 *   <li>{@value KernelErrorCodes#EX_PERS_5005} — Transport I/O failure</li>
 *   <li>{@value KernelErrorCodes#EX_PERS_5006} — Interceptor initialization error</li>
 * </ul>
 *
 * @since 0.5.0
 */
public final class PersistenceProviderException extends ExerisKernelException {

    private static final String BOOTSTRAP_MSG = "Persistence provider bootstrap failure";
    private static final String CONNECTION_MSG = "Persistence connection acquisition failure";
    private static final String QUERY_MSG = "Persistence query execution failure";
    private static final String AUTH_MSG = "Persistence authentication failure";
    private static final String TRANSPORT_MSG = "Persistence transport I/O failure";
    private static final String INTERCEPTOR_MSG = "Persistence interceptor initialization failure";

    // -----------------------------------------------------------------------
    // Private constructor — use static factories
    // -----------------------------------------------------------------------

    private PersistenceProviderException(String errorCode, String message,
                                         Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }

    // -----------------------------------------------------------------------
    // Static factories (zero-allocation constructors)
    // -----------------------------------------------------------------------

    /**
     * Bootstrap failure — engine could not be created.
     *
     * <p>The {@code connectionUrl} is sanitized before capture — any embedded
     * {@code user:password@} userinfo is stripped to prevent credential leakage
     * into telemetry / Glass-Box dumps.
     *
     * @param providerName  provider display name
     * @param connectionUrl database URL (sanitized before storage in rawArgs)
     * @param cause         root cause
     * @return exception with rawArgs: [providerName, sanitizedUrl]
     */
    public static PersistenceProviderException bootstrapFailure(
            String providerName, String connectionUrl, Throwable cause) {
        return new PersistenceProviderException(
                KernelErrorCodes.EX_PERS_5001, BOOTSTRAP_MSG, cause,
                providerName, sanitizeUrl(connectionUrl));
    }

    /**
     * Strips any embedded userinfo ({@code user:password@}) from a connection URL.
     */
    private static String sanitizeUrl(String url) {
        if (url == null) {
            return "[null]";
        }
        return url.replaceAll("//[^@]*@", "//");
    }

    /**
     * Connection acquisition failure — pool exhausted or timeout.
     *
     * @param providerName      provider display name
     * @param timeoutMs         configured timeout
     * @param activeConnections current active connections
     * @return exception with rawArgs: [providerName, timeoutMs, activeConnections]
     */
    public static PersistenceProviderException connectionExhausted(
            String providerName, long timeoutMs, int activeConnections) {
        return new PersistenceProviderException(
                KernelErrorCodes.EX_PERS_5002, CONNECTION_MSG, null,
                providerName, timeoutMs, activeConnections);
    }

    /**
     * Query execution failure — SQL error, protocol error, or I/O.
     *
     * @param sqlState PostgreSQL SQLSTATE code
     * @param detail   error detail message
     * @param cause    root cause
     * @return exception with rawArgs: [sqlState, detail]
     */
    public static PersistenceProviderException queryFailed(
            String sqlState, String detail, Throwable cause) {
        return new PersistenceProviderException(
                KernelErrorCodes.EX_PERS_5003, QUERY_MSG, cause,
                sqlState, detail);
    }

    /**
     * Authentication failure — SCRAM/MD5 rejected.
     *
     * @param mechanism     auth mechanism name
     * @param serverMessage server error message
     * @return exception with rawArgs: [mechanism, serverMessage]
     */
    public static PersistenceProviderException authFailed(
            String mechanism, String serverMessage) {
        return new PersistenceProviderException(
                KernelErrorCodes.EX_PERS_5004, AUTH_MSG, null,
                mechanism, serverMessage);
    }

    /**
     * Authentication failure — SCRAM/MD5 rejected, with root cause preserved.
     *
     * @param mechanism     auth mechanism name (or SQLSTATE in JDBC path)
     * @param serverMessage server error message
     * @param cause         root cause (may be {@code null})
     * @return exception with rawArgs: [mechanism, serverMessage]
     */
    public static PersistenceProviderException authFailed(
            String mechanism, String serverMessage, Throwable cause) {
        return new PersistenceProviderException(
                KernelErrorCodes.EX_PERS_5004, AUTH_MSG, cause,
                mechanism, serverMessage);
    }

    /**
     * Transport I/O failure — socket read/write error.
     *
     * @param transportName  transport display name
     * @param fileDescriptor file descriptor
     * @param errno          OS error number
     * @param cause          root cause
     * @return exception with rawArgs: [transportName, fileDescriptor, errno]
     */
    public static PersistenceProviderException transportFailure(
            String transportName, long fileDescriptor, int errno, Throwable cause) {
        return new PersistenceProviderException(
                KernelErrorCodes.EX_PERS_5005, TRANSPORT_MSG, cause,
                transportName, fileDescriptor, errno);
    }

    /**
     * Interceptor initialization failure — a {@link eu.exeris.kernel.spi.persistence.ConnectionInterceptor}
     * failed to prepare the connection for the given isolation context.
     *
     * <p>The engine MUST discard the connection on receiving this exception —
     * it MUST NOT be returned to the pool.
     *
     * @param interceptorClass simple class name of the failing interceptor
     * @param isolationKey     value from {@code StorageContext.isolationKey()},
     *                         or {@code "[none]"} for system-scope operations
     * @param cause            root cause
     * @return exception with rawArgs: [interceptorClass, isolationKey]
     */
    public static PersistenceProviderException interceptorInitFailed(
            String interceptorClass, String isolationKey, Throwable cause) {
        return new PersistenceProviderException(
                KernelErrorCodes.EX_PERS_5006, INTERCEPTOR_MSG, cause,
                interceptorClass, isolationKey);
    }

    /**
     * Dedicated datasource routing failure — the datasource key from the DEDICATED
     * {@link eu.exeris.kernel.spi.security.StorageContext} was not found in
     * {@link eu.exeris.kernel.spi.persistence.PersistenceConfig#dedicatedDataSources()}.
     *
     * @param providerName  provider display name
     * @param dataSourceKey the routing key that was not found in the configured map
     * @return exception with rawArgs: [providerName, dataSourceKey]
     */
    public static PersistenceProviderException dedicatedDatasourceNotFound(
            String providerName, String dataSourceKey) {
        return new PersistenceProviderException(
                KernelErrorCodes.EX_PERS_5006,
                "Dedicated datasource key not found in configuration",
                null,
                providerName, dataSourceKey);
    }

    /**
     * No {@link eu.exeris.kernel.spi.persistence.PersistenceProvider} found on the classpath.
     *
     * <p>Kernel start MUST abort when this exception is thrown. The operator must add
     * {@code exeris-kernel-community} or {@code exeris-kernel-enterprise} to the runtime
     * dependencies.
     *
     * @param message human-readable diagnostic (logged before abort)
     * @return exception with rawArgs: [message]
     */
    public static PersistenceProviderException noProviderAvailable(String message) {
        return new PersistenceProviderException(
                KernelErrorCodes.EX_PERS_5007,
                "No PersistenceProvider available — kernel start aborted",
                null, message);
    }
}
