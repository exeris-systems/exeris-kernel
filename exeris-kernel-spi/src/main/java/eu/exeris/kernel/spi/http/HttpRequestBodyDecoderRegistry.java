/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * SPI: Registry contract for typed request body decoder resolution.
 *
 * <h2>Priority ordering</h2>
 * <p>When multiple candidates report {@code supports(...) == true}, the resolver
 * MUST return the candidate with the highest {@link HttpRequestBodyDecoder#priority()}
 * value. Ties resolve by registration order (first-registered wins). The resolver
 * returns {@code null} when no candidate supports the inputs — the generated handler
 * maps this to a server-side configuration error at the call site.
 *
 * @since 0.8
 */
@FunctionalInterface
public interface HttpRequestBodyDecoderRegistry {

    /**
     * Resolves a decoder for the given target type and request content type.
     *
     * @param targetType  desired payload runtime class; never null
     * @param contentType request {@code content-type} header value, or {@code null}/empty
     * @return matching decoder when available, otherwise {@code null}
     */
    HttpRequestBodyDecoder resolve(Class<?> targetType, String contentType);

    /**
     * Returns a registry that resolves no decoders.
     *
     * @return empty registry
     */
    static HttpRequestBodyDecoderRegistry empty() {
        return (targetType, contentType) -> null;
    }

    /**
     * Returns a registry that resolves over the given decoders honouring the
     * priority-ordering contract:
     * <ul>
     *   <li>Higher {@link HttpRequestBodyDecoder#priority()} wins.</li>
     *   <li>Ties resolve by registration order (first occurrence in {@code decoders} wins).</li>
     *   <li>{@code null} returned when no candidate supports the {@code (targetType, contentType)} pair.</li>
     * </ul>
     *
     * @param decoders ordered list of candidates; non-null, may be empty
     * @return registry over the given decoders
     */
    @SuppressWarnings("PMD.ShortMethodName") // 'of' is a standard Java factory idiom (cf. List.of, Map.of)
    static HttpRequestBodyDecoderRegistry of(List<HttpRequestBodyDecoder> decoders) {
        Objects.requireNonNull(decoders, "decoders must not be null");
        // Snapshot + stable-sort by descending priority; ties preserve insertion order.
        List<HttpRequestBodyDecoder> ordered = new ArrayList<>(decoders);
        ordered.sort(Comparator.comparingInt(HttpRequestBodyDecoder::priority).reversed());
        List<HttpRequestBodyDecoder> snapshot = List.copyOf(ordered);
        return (targetType, contentType) -> {
            Objects.requireNonNull(targetType, "targetType must not be null");
            for (HttpRequestBodyDecoder decoder : snapshot) {
                if (decoder.supports(targetType, contentType)) {
                    return decoder;
                }
            }
            return null;
        };
    }
}
