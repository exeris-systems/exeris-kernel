/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import org.neo4j.driver.Result;

import java.util.Map;
import java.util.function.Function;

/**
 * Session/transaction-aware Cypher execution surface shared by
 * {@link CommunityGraphCypherReader} and {@link CommunityGraphCypherWriter}.
 *
 * <p>The implementation owns the session and transaction state; readers and writers carry no
 * lifecycle of their own. This preserves transactional cohesion across read+write
 * operations against a single backend session — extraction is structural only, not a
 * boundary widening.
 *
 * @since 0.7
 */
interface CypherExecutor {

    /**
     * Runs {@code query} as a read; if a transaction is active, runs inside it, otherwise
     * opens a session-scoped read.
     *
     * @param <T>    reader return type
     * @param query  Cypher text
     * @param params named parameter bindings
     * @param reader function that consumes the {@link Result}
     * @return the value computed by {@code reader}
     */
    <T> T executeRead(String query, Map<String, Object> params, Function<Result, T> reader);

    /**
     * Runs {@code query} as a write; if a transaction is active, runs inside it, otherwise
     * opens a session-scoped write and consumes the result.
     *
     * @param query  Cypher text
     * @param params named parameter bindings
     */
    void executeWrite(String query, Map<String, Object> params);
}
