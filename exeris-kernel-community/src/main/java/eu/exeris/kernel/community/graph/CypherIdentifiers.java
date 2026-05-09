/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;

import java.util.regex.Pattern;

/**
 * Identifier validation shared by {@link CommunityGraphCypherReader} and
 * {@link CommunityGraphCypherWriter} (QA-008 decomposition). Cypher does not allow
 * parameter substitution for labels and relation types, so every label/type that we
 * splice into a query string must match {@code [A-Za-z][A-Za-z0-9_]*} — the rule both
 * Neo4j and Cypher admit for valid identifiers.
 *
 * @since 0.7.0
 */
final class CypherIdentifiers {

    /* default */ static final String QUERY_TYPE = "CYPHER_IDENTIFIER";

    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z]\\w*$");

    private CypherIdentifiers() { /* static utility — not instantiable */ }

    /**
     * Validates that {@code identifier} is a safe Cypher label/type token.
     *
     * @param identifier candidate string
     * @return {@code identifier} unchanged when valid
     * @throws GraphQueryException when {@code identifier} is null or contains characters
     *                             other than ASCII letters, digits, or underscores
     */
    /* default */ static String requireIdentifier(String identifier) {
        if (identifier == null || !PATTERN.matcher(identifier).matches()) {
            throw new GraphQueryException(QUERY_TYPE,
                    "Invalid Cypher identifier: expected [A-Za-z][A-Za-z0-9_]*");
        }
        return identifier;
    }
}
