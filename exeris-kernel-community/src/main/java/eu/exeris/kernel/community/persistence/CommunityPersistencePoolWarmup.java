/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import com.zaxxer.hikari.HikariDataSource;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Community-internal connection-pool warm-up helper. Extracted from
 * {@link CommunityPersistenceEngine} in QA-010 (v0.8 Sprint 1) so the engine carries
 * one less responsibility: the "hold N connections at once to force HikariCP to
 * materialise them concurrently before the first user request arrives" workflow lives
 * here.
 *
 * <h2>Why hold connections, not loop {@code getConnection()/close()}</h2>
 * <p>HikariCP creates a fresh connection on demand only when there is no idle
 * connection to hand out. A loop of {@code getConnection()} immediately followed by
 * {@code close()} would return the same connection to the pool every iteration — so
 * the pool would never grow past one. Holding all {@code N} connections at once forces
 * Hikari to create them concurrently; once they are released the pool is fully idle
 * and ready for the request load.
 *
 * <h2>Fail-fast on bootstrap</h2>
 * <p>If any acquired connection fails {@link Connection#isValid(int)} the helper
 * surfaces {@link PersistenceProviderException#bootstrapFailure} so a misconfigured
 * database (auth, network) shows up at engine construction rather than on first
 * request.
 *
 * @since 0.8
 */
final class CommunityPersistencePoolWarmup {

    private CommunityPersistencePoolWarmup() {
        // utility — no instances
    }

    /**
     * Pre-warms {@code sharedPool} to {@code min(config.poolWarmupConnections(),
     * config.maxPoolSize())} concurrent connections. No-op when warm-up is disabled or
     * the requested count is non-positive. Held connections are always released —
     * success or failure.
     *
     * @param sharedPool the HikariCP pool to warm up
     * @param config     persistence config (warm-up flag, target count, max pool size)
     * @throws PersistenceProviderException ({@code EX-PERS-5001}) when an acquired connection
     *         fails validation or pool acquisition itself raises an SQL error
     */
    /* default */ static void prewarm(HikariDataSource sharedPool, PersistenceConfig config) {
        int warmupConnections = config.poolWarmupConnections();
        if (!config.poolWarmupEnabled() || warmupConnections <= 0) {
            return;
        }
        int targetConnections = Math.min(warmupConnections, config.maxPoolSize());
        List<Connection> heldConnections = new ArrayList<>(targetConnections);
        try {
            acquireWarmupConnections(sharedPool, targetConnections, heldConnections, config);
        } catch (SQLException sqlEx) {
            throw PersistenceProviderException.bootstrapFailure(
                    CommunityPersistenceConstants.PROVIDER_ID,
                    config.connectionUrl(),
                    sqlEx);
        } finally {
            releaseConnectionsQuietly(heldConnections);
        }
    }

    private static void acquireWarmupConnections(HikariDataSource sharedPool,
                                                 int warmupConnections,
                                                 List<Connection> heldConnections,
                                                 PersistenceConfig config) throws SQLException {
        for (int i = 0; i < warmupConnections; i++) {
            Connection connection = sharedPool.getConnection(); // NOPMD CloseResource -- held for warm-up
            heldConnections.add(connection);
            validateWarmupConnection(connection, config);
        }
    }

    private static void validateWarmupConnection(Connection connection,
                                                 PersistenceConfig config) throws SQLException {
        if (!connection.isValid(5)) {
            throw PersistenceProviderException.bootstrapFailure(
                    CommunityPersistenceConstants.PROVIDER_ID,
                    config.connectionUrl(),
                    new IllegalStateException("Connection validation returned false during pool warm-up"));
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    private static void releaseConnectionsQuietly(List<Connection> connections) {
        for (Connection connection : connections) {
            if (connection == null) {
                continue;
            }
            try {
                connection.close();
            } catch (SQLException _) {
                // best-effort return to pool
            }
        }
    }
}
