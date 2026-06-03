/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Shared Jackson 3 JSON body-codec primitives for the Community request/response
 * body decoders.
 *
 * <p>The body-codec matrix has symmetric request/response decode quadrants
 * (ADR-034 response side, ADR-036 §1 request side). Their content-type predicate
 * and off-heap read path are identical — this package-private helper holds the
 * single implementation so each decoder is a thin SPI adapter and future
 * codec-matrix entries inherit the same behaviour without duplication.
 *
 * <p>Jackson stays confined here and in the two decoders; no {@code tools.jackson.*}
 * type crosses the SPI boundary (The Wall — ADR-006).
 */
final class JsonBodyCodecs {

    private static final String APPLICATION_JSON = "application/json";
    private static final String APPLICATION_PREFIX = "application/";
    private static final String JSON_SUFFIX = "+json";
    private static final long EMPTY_SIZE = 0L;

    private JsonBodyCodecs() {
    }

    /**
     * Returns {@code true} when {@code contentType} is absent/empty (tolerated per
     * the decoder contract) or in the JSON family — {@code application/json} or any
     * {@code application/*+json} structured-syntax suffix (RFC 6838 §4.2.8, e.g.
     * {@code application/vnd.exeris+json}). Parameters are stripped before matching
     * (e.g. {@code application/json; charset=utf-8}).
     *
     * @param contentType the {@code content-type} header value, or {@code null}/empty
     * @return {@code true} when a JSON decoder may handle a body of this content type
     */
    /* default */ static boolean isJsonCompatible(String contentType) {
        if (contentType == null || contentType.isEmpty()) {
            return true;
        }
        int semi = contentType.indexOf(';');
        String base = (semi < 0 ? contentType : contentType.substring(0, semi)).trim();
        return APPLICATION_JSON.equalsIgnoreCase(base)
                || (base.regionMatches(true, 0, APPLICATION_PREFIX, 0, APPLICATION_PREFIX.length())
                        && base.regionMatches(true, base.length() - JSON_SUFFIX.length(),
                                JSON_SUFFIX, 0, JSON_SUFFIX.length()));
    }

    /**
     * Decodes the off-heap body into {@code targetType} with a single
     * {@code MemorySegment} → {@code byte[]} copy straight into
     * {@code mapper.readValue(byte[], Class)} — no intermediate {@code String}
     * allocation (No-Waste-Compute, ADR-036 §3). Returns {@code null} when the
     * body is empty; the caller decides whether {@code null} is acceptable.
     *
     * <p>Does NOT close, retain, or otherwise extend the lifetime of the
     * {@link LoanedBuffer}; ownership stays with the caller. Jackson-specific
     * exceptions are wrapped in {@link IllegalStateException} so no driver type
     * crosses the SPI boundary.
     *
     * @param mapper     application-owned {@link ObjectMapper}; never null
     * @param body       off-heap body buffer; never null
     * @param targetType desired payload runtime class; never null
     * @return decoded payload, or {@code null} when the body is empty
     */
    /* default */ static Object readValue(ObjectMapper mapper, LoanedBuffer body, Class<?> targetType) {
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        long size = body.size();
        if (size == EMPTY_SIZE) {
            return null;
        }
        byte[] bytes = new byte[Math.toIntExact(size)];
        MemorySegment.copy(body.segment(), 0L, MemorySegment.ofArray(bytes), 0L, size);
        try {
            return mapper.readValue(bytes, targetType);
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "JSON deserialization failed for target type " + targetType.getName(), ex);
        }
    }
}
