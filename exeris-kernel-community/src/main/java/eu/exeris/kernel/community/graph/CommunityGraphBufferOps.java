/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.graph;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Buffer/JSON conversion helpers shared by the SQL and Cypher graph backends. Every method
 * is static and stateless.
 */
final class CommunityGraphBufferOps {
    /** The UTF-8 bytes of {@code "[]"}, returned by {@link #toUuidJsonArray} for an empty list. */
    /* default */ static final byte[] EMPTY_JSON_ARRAY = "[]".getBytes(StandardCharsets.UTF_8);

    private CommunityGraphBufferOps() {
        /* static utility — not instantiable */
    }

    /**
     * Reads {@code properties}'s off-heap segment as a UTF-8 string.
     *
     * @param properties encoded node properties; may be {@code null} or empty. Read but not
     *                   closed — the caller retains ownership of the buffer
     * @return the decoded string, or {@code "{}"} when {@code properties} is {@code null} or
     *         has size zero
     */
    /* default */ static String decodeProperties(LoanedBuffer properties) {
        if (properties != null && properties.size() > 0) {
            byte[] bytes = properties.segment()
                    .asSlice(0, properties.size())
                    .toArray(ValueLayout.JAVA_BYTE);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return "{}";
    }

    /**
     * Encodes {@code ids} as a UTF-8 JSON array of quoted UUID strings, e.g.
     * {@code ["3fa8..","7c21.."]}.
     *
     * @param ids node IDs to encode, in iteration order
     * @return the encoded JSON array bytes; {@link #EMPTY_JSON_ARRAY} when {@code ids} is empty
     */
    /* default */ static byte[] toUuidJsonArray(List<UUID> ids) {
        if (ids.isEmpty()) {
            return EMPTY_JSON_ARRAY;
        }
        StringBuilder builder = new StringBuilder(ids.size() * 40 + 2);
        builder.append('[');
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(ids.get(i)).append('"');
        }
        builder.append(']');
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }
}