/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpResponseBodyDecoder;
import eu.exeris.kernel.spi.http.HttpResponseDecodingContext;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Community driver: raw-text {@link HttpResponseBodyDecoder} that returns the response body as a
 * UTF-8 {@link String} for any content type, leaving structural parsing to the caller.
 *
 * <p>Unlike {@link CommunityJsonResponseBodyDecoder}, this decoder does <em>not</em> map the body
 * through an object mapper: it is the right primitive for opaque documents handed wholesale to a
 * downstream parser — most notably a JWKS document fetched for
 * {@link eu.exeris.kernel.community.security.CommunityOidcIdentityProvider#overJwksEndpoint},
 * where JSON-object mapping the document only to re-serialise it for the JOSE library would be a
 * needless round-trip (No-Waste-Compute). A JWKS endpoint advertises {@code application/json}, so
 * a JSON decoder would otherwise win {@code String.class} resolution and then fail to coerce a
 * JSON object into a {@code String}; give the JWKS client a registry containing only this decoder.
 *
 * <p>Supports {@code String.class} alone, regardless of (and tolerating an absent) content type.
 * Returns {@code null} for an empty body, per the {@link HttpResponseBodyDecoder} contract. Does
 * NOT close, retain, or extend the lifetime of the {@link LoanedBuffer} — ownership stays with the
 * façade.
 *
 * @since 0.10
 */
public final class CommunityTextResponseBodyDecoder implements HttpResponseBodyDecoder {

    private static final long EMPTY_SIZE = 0L;

    @Override
    public boolean supports(Class<?> targetType, String contentType) {
        return targetType == String.class;
    }

    @Override
    public Object decode(LoanedBuffer body, Class<?> targetType, HttpResponseDecodingContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (body == null) {
            return null;
        }
        long size = body.size();
        if (size == EMPTY_SIZE) {
            return null;
        }
        byte[] bytes = new byte[Math.toIntExact(size)];
        MemorySegment.copy(body.segment(), 0L, MemorySegment.ofArray(bytes), 0L, size);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
