/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.codec.EventCodecContext;
import eu.exeris.kernel.spi.events.codec.EventPayloadCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Community driver: Jackson 3 implementation of {@link EventPayloadCodec} (ADR-046).
 *
 * <p>The default JSON provider for the event-payload codec seam — the event-side
 * sibling of {@code CommunityJsonRequestBodyDecoder} / {@code CommunityJsonResponseBodyDecoder}.
 * Supports any non-null {@code payloadType} when {@code contentType} is absent
 * (producer relies on the default) or in the JSON family — {@code application/json}
 * or any {@code application/*+json} structured-syntax suffix (RFC 6838 §4.2.8).
 *
 * <p>{@link #encode} serializes the payload to a heap-backed, caller-owned
 * {@link EventPayload} (refCount == 1) the producer hands to {@code EventBus.publish}.
 * {@link #decode} reads the payload bytes without closing or retaining it (ownership
 * stays with the caller). Jackson-specific exceptions are wrapped in
 * {@link IllegalStateException} so no {@code tools.jackson.*} type crosses the SPI
 * boundary (The Wall — ADR-006).
 *
 * @since 0.10.0
 */
public final class CommunityJsonEventPayloadCodec implements EventPayloadCodec {

    private static final String APPLICATION_JSON = "application/json";
    private static final String APPLICATION_PREFIX = "application/";
    private static final String JSON_SUFFIX = "+json";

    private final ObjectMapper mapper;

    /**
     * Creates a Jackson-backed event-payload codec.
     *
     * @param mapper application-owned {@link ObjectMapper}; never null
     */
    public CommunityJsonEventPayloadCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public boolean supports(Class<?> payloadType, String contentType) {
        return payloadType != null && isJsonCompatible(contentType);
    }

    @Override
    public EventPayload encode(Object payload, EventCodecContext ctx) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(ctx, "ctx must not be null");
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(payload);
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "JSON serialization failed for payload type " + payload.getClass().getName(), ex);
        }
        // Takes ownership of the array without copying; refCount == 1, caller-owned.
        return CommunityHeapEventPayload.wrap(bytes);
    }

    @Override
    public Object decode(EventPayload payload, Class<?> targetType, EventCodecContext ctx) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(ctx, "ctx must not be null");
        if (payload.length() == 0) {
            return null;
        }
        // Reads the payload bytes without closing or retaining it — ownership stays with the caller.
        byte[] bytes = CommunityHeapEventPayload.copyBytes(payload);
        try {
            return mapper.readValue(bytes, targetType);
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "JSON deserialization failed for target type " + targetType.getName(), ex);
        }
    }

    /**
     * Returns {@code true} when {@code contentType} is absent/empty (tolerated per the
     * codec contract) or in the JSON family. Parameters are stripped before matching
     * (e.g. {@code application/json; charset=utf-8}). Mirrors the
     * {@code JsonBodyCodecs.isJsonCompatible} predicate; kept local to confine the
     * events codec to its own package (the http helper is package-private).
     */
    private static boolean isJsonCompatible(String contentType) {
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
}
