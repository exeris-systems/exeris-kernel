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
 * SPI: Registry contract for typed request body encoder resolution.
 *
 * <h2>Priority ordering</h2>
 * <p>When multiple candidates report {@code supports(...) == true}, the resolver
 * MUST return the candidate with the highest {@link HttpRequestBodyEncoder#priority()}
 * value. Ties resolve by registration order (first-registered wins). The resolver
 * returns {@code null} when no candidate supports the input — the façade maps this
 * to a {@code WebClientException} at the call site.
 *
 * @since 0.8
 */
@FunctionalInterface
public interface HttpRequestBodyEncoderRegistry {

    /**
     * Resolves an encoder for the given payload type.
     *
     * @param payloadType payload runtime class; never null
     * @return matching encoder when available, otherwise {@code null}
     */
    HttpRequestBodyEncoder resolve(Class<?> payloadType);

    /**
     * Returns a registry that resolves no encoders.
     *
     * @return empty registry
     */
    static HttpRequestBodyEncoderRegistry empty() {
        return payloadType -> null;
    }

    /**
     * Returns a registry that resolves over the given encoders honouring the
     * priority-ordering contract:
     * <ul>
     *   <li>Higher {@link HttpRequestBodyEncoder#priority()} wins.</li>
     *   <li>Ties resolve by registration order (first occurrence in {@code encoders} wins).</li>
     *   <li>{@code null} returned when no candidate supports the payload type.</li>
     * </ul>
     *
     * @param encoders ordered list of candidates; non-null, may be empty
     * @return registry over the given encoders
     */
    @SuppressWarnings("PMD.ShortMethodName") // 'of' is a standard Java factory idiom (cf. List.of, Map.of)
    static HttpRequestBodyEncoderRegistry of(List<HttpRequestBodyEncoder> encoders) {
        Objects.requireNonNull(encoders, "encoders must not be null");
        // Snapshot + stable-sort by descending priority. Java's sort is stable, so
        // ties preserve insertion order — which is the registration-order contract.
        List<HttpRequestBodyEncoder> ordered = new ArrayList<>(encoders);
        ordered.sort(Comparator.comparingInt(HttpRequestBodyEncoder::priority).reversed());
        List<HttpRequestBodyEncoder> snapshot = List.copyOf(ordered);
        return payloadType -> {
            Objects.requireNonNull(payloadType, "payloadType must not be null");
            for (HttpRequestBodyEncoder encoder : snapshot) {
                if (encoder.supports(payloadType)) {
                    return encoder;
                }
            }
            return null;
        };
    }
}
