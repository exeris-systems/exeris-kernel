/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;

/**
 * Per-HTTP-request persistence session binding.
 *
 * <h2>Semantics</h2>
 * <p>Wraps an active persistence connection and its transaction state scoped to a single HTTP request.
 * Immutable, with no identity-sensitive operations, and declared {@code value record} on the
 * `preview` line (JEP 401). The {@code PersistenceConnection} it carries keeps its own identity
 * and its own lifecycle; only the carrier around it loses identity.
 *
 * <h2>Lifecycle</h2>
 * <p>Created at HTTP entry (with {@code active = true}) and deactivated at HTTP exit (exception or nominal).
 * Once deactivated, the connection is committed/rolled-back and released back to the pool by the HTTP subsystem.
 *
 * <h2>Usage in ScopedValue</h2>
 * <pre>
 * var session = REQUEST_SESSION.orElse(null);
 * if (session != null && session.active()) {
 *     conn = session.connection();  // reuse within request
 * } else {
 *     conn = pool.acquire();  // fallback to legacy per-operation
 * }
 * </pre>
 *
 * @param connection     Persistence connection from pool, transactionally open
 * @param isolation      Isolation level set at request entry (immutable for request lifetime)
 * @param readOnly       Read-only hint (inferred from HTTP method or explicit header)
 * @param active         Flag: true if session is active, false if deactivated (post-exit)
 *
 * @since 0.5.0
 */
public value record RequestPersistenceSession(
    PersistenceConnection connection,
    TransactionIsolation isolation,
    boolean readOnly,
    boolean active
) {

    /**
     * Factory: create an active session with a connection and isolation level.
     *
     * @param connection Persistence connection (must be freshly acquired from pool)
     * @param isolation  Transaction isolation level (default: READ_COMMITTED)
     * @param readOnly   Read-only hint
     * @return Active session
     */
    public static RequestPersistenceSession active(PersistenceConnection connection,
                                                   TransactionIsolation isolation,
                                                   boolean readOnly) {
        return new RequestPersistenceSession(connection, isolation, readOnly, true);
    }

    /**
     * Deactivate this session (called on HTTP exit or exception).
     * Creates a new record with {@code active = false}.
     *
     * @return Deactivated session
     */
    public RequestPersistenceSession deactivate() {
        return new RequestPersistenceSession(connection, isolation, readOnly, false);
    }
}
