/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import java.sql.SQLException;
import java.util.Map;

/**
 * Static helpers shared by {@link CommunityHikariSupport}: unwrapping the nested
 * {@link SQLException} from a driver/pool failure, checking whether it carries a usable SQL
 * state, and a case-insensitive key lookup over a connection-property map.
 */
/* default */ final class CommunityHikariUtils {

    private CommunityHikariUtils() {
    }

    /* default */ static SQLException findSqlException(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    /* default */ static boolean hasSqlState(SQLException sqlException) {
        String sqlState = sqlException.getSQLState();
        return sqlState != null && !sqlState.isBlank();
    }

    /* default */ static boolean containsKeyIgnoreCase(Map<String, String> properties, String key) {
        for (String candidate : properties.keySet()) {
            if (candidate.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }
}