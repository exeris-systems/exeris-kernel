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

final class CommunityGraphBufferOps {
    /* default */ static final byte[] EMPTY_JSON_ARRAY = "[]".getBytes(StandardCharsets.UTF_8);

    private CommunityGraphBufferOps() {
    }

    /* default */ static String decodeProperties(LoanedBuffer properties) {
        if (properties != null && properties.size() > 0) {
            byte[] bytes = properties.segment()
                    .asSlice(0, properties.size())
                    .toArray(ValueLayout.JAVA_BYTE);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return "{}";
    }

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