/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.json;

/**
 * Identifies which Community JSON codec a {@link JsonMapperCustomizer} is being invoked for.
 *
 * <p>The scopes mirror the {@code {request,response} × {encode,decode}} HTTP body-codec matrix
 * (ADR-009 / ADR-034 / ADR-036) plus the event-payload codec (ADR-046). Each value corresponds
 * to exactly one provider-built default codec whose backing {@code tools.jackson} mapper is
 * assembled by {@link CommunityJsonMappers#forScope(JsonMapperScope)}:
 *
 * <ul>
 *   <li>{@link #HTTP_RESPONSE_ENCODE} — server-side response body encoder
 *       ({@code JsonBodyEncoder}); the {@code exchange.respond(status, payload)} hot path.</li>
 *   <li>{@link #HTTP_REQUEST_ENCODE} — client-side request body encoder
 *       ({@code CommunityJsonRequestBodyEncoder}).</li>
 *   <li>{@link #HTTP_RESPONSE_DECODE} — client-side response body decoder
 *       ({@code CommunityJsonResponseBodyDecoder}).</li>
 *   <li>{@link #HTTP_REQUEST_DECODE} — server-side request body decoder
 *       ({@code CommunityJsonRequestBodyDecoder}).</li>
 *   <li>{@link #EVENTS} — domain-event payload codec
 *       ({@code CommunityJsonEventPayloadCodec}).</li>
 * </ul>
 *
 * <p>There is no dedicated client-facade scope: {@code KernelWebClient} lives in
 * {@code exeris-kernel-core} and is constructed explicitly by applications with their chosen
 * registries (ADR-034), so a per-instance client mapper is already an application capability —
 * an application simply builds its own codec with its own mapper. The provider-exposed default
 * client codecs are covered by {@link #HTTP_REQUEST_ENCODE} + {@link #HTTP_RESPONSE_DECODE}.
 *
 * @since 0.10
 */
public enum JsonMapperScope {

    /** Server-side HTTP response body encode ({@code JsonBodyEncoder}). */
    HTTP_RESPONSE_ENCODE,

    /** Client-side HTTP request body encode ({@code CommunityJsonRequestBodyEncoder}). */
    HTTP_REQUEST_ENCODE,

    /** Client-side HTTP response body decode ({@code CommunityJsonResponseBodyDecoder}). */
    HTTP_RESPONSE_DECODE,

    /** Server-side HTTP request body decode ({@code CommunityJsonRequestBodyDecoder}). */
    HTTP_REQUEST_DECODE,

    /** Domain-event payload codec ({@code CommunityJsonEventPayloadCodec}). */
    EVENTS
}
