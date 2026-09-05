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
 * SPI: Registry contract for typed response body decoder resolution.
 *
 * @implSpec When several candidates report {@code supports(...) == true}, return the one with the
 *           highest {@link HttpResponseBodyDecoder#priority()}, and let registration order break a
 *           tie (first registered wins). Return {@code null} when no candidate supports the inputs
 *           — the façade turns that into a {@code WebClientException} at the call site.
 * @since 0.8
 */
@FunctionalInterface
public interface HttpResponseBodyDecoderRegistry {

    /**
     * Resolves a decoder for the given target type and response content type.
     *
     * @param targetType  desired payload runtime class; never null
     * @param contentType response {@code content-type} header value, or {@code null}/empty
     * @return matching decoder when available, otherwise {@code null}
     */
    HttpResponseBodyDecoder resolve(Class<?> targetType, String contentType);

    /**
     * Returns a registry that resolves no decoders.
     *
     * @return empty registry
     */
    static HttpResponseBodyDecoderRegistry empty() {
        return (targetType, contentType) -> null;
    }

    /**
     * Returns a registry that resolves over the given decoders honouring the
     * priority-ordering contract:
     * <ul>
     *   <li>Higher {@link HttpResponseBodyDecoder#priority()} wins.</li>
     *   <li>Ties resolve by registration order (first occurrence in {@code decoders} wins).</li>
     *   <li>{@code null} returned when no candidate supports the {@code (targetType, contentType)} pair.</li>
     * </ul>
     *
     * @param decoders ordered list of candidates; non-null, may be empty
     * @return registry over the given decoders
     */
    @SuppressWarnings("PMD.ShortMethodName") // 'of' is a standard Java factory idiom (cf. List.of, Map.of)
    static HttpResponseBodyDecoderRegistry of(List<HttpResponseBodyDecoder> decoders) {
        Objects.requireNonNull(decoders, "decoders must not be null");
        // Snapshot + stable-sort by descending priority; ties preserve insertion order.
        List<HttpResponseBodyDecoder> ordered = new ArrayList<>(decoders);
        ordered.sort(Comparator.comparingInt(HttpResponseBodyDecoder::priority).reversed());
        List<HttpResponseBodyDecoder> snapshot = List.copyOf(ordered);
        return (targetType, contentType) -> {
            Objects.requireNonNull(targetType, "targetType must not be null");
            for (HttpResponseBodyDecoder decoder : snapshot) {
                if (decoder.supports(targetType, contentType)) {
                    return decoder;
                }
            }
            return null;
        };
    }
}
