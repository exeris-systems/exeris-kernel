/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
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
 * The Enterprise Black-Box tier serialises these args directly to a binary struct.
 *
 * <h2>Error Codes</h2>
 * <ul>
 *   <li>{@value KernelErrorCodes#EX_PERS_5001} — Bootstrap failure</li>
 *   <li>{@value KernelErrorCodes#EX_PERS_5002} — Connection acquisition failure</li>
 *   <li>{@value KernelErrorCodes#EX_PERS_5003} — Query execution failure</li>
 *   <li>{@value KernelErrorCodes#EX_PERS_5004} — Authentication failure</li>
 *   <li>{@value KernelErrorCodes#EX_PERS_5005} — Transport I/O failure</li>
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
     * @param providerName  provider display name
     * @param connectionUrl database URL
     * @param cause         root cause
     * @return exception with rawArgs: [providerName, connectionUrl]
     */
    public static PersistenceProviderException bootstrapFailure(
            String providerName, String connectionUrl, Throwable cause) {
        return new PersistenceProviderException(
                KernelErrorCodes.EX_PERS_5001, BOOTSTRAP_MSG, cause,
                providerName, connectionUrl);
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
     * Transport I/O failure — socket read/write error.
     *
     * @param transportName transport display name
     * @param fileDescriptor file descriptor
     * @param errno         OS error number
     * @param cause         root cause
     * @return exception with rawArgs: [transportName, fileDescriptor, errno]
     */
    public static PersistenceProviderException transportFailure(
            String transportName, long fileDescriptor, int errno, Throwable cause) {
        return new PersistenceProviderException(
                KernelErrorCodes.EX_PERS_5005, TRANSPORT_MSG, cause,
                transportName, fileDescriptor, errno);
    }
}


