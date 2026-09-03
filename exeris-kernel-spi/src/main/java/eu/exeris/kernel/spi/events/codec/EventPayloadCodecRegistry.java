/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events.codec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * SPI: Registry contract for {@link EventPayloadCodec} resolution (ADR-046).
 *
 * <h2>Priority ordering</h2>
 * <p>When multiple candidates report {@code supports(...) == true}, the resolver
 * MUST return the candidate with the highest {@link EventPayloadCodec#priority()}
 * value. Ties resolve by registration order (first-registered wins). The resolver
 * returns {@code null} when no candidate supports the inputs — the producer (the
 * generated {@code *EventPublisher}) treats {@code null} as "no codec configured"
 * and falls back to {@link eu.exeris.kernel.spi.events.EventPayload#empty()}.
 *
 * <p>The {@code of(...)} contract mirrors the HTTP body-codec registries
 * (e.g. {@code HttpRequestBodyDecoderRegistry.of}) verbatim: snapshot, stable
 * descending sort on {@code priority()}, ties by insertion order, first
 * {@code supports(...) == true} wins.
 *
 * @since 0.10.0
 */
@FunctionalInterface
public interface EventPayloadCodecRegistry {

    /**
     * Resolves a codec for the given payload type and content type.
     *
     * @param payloadType desired payload runtime class; never null
     * @param contentType the requested content-type, or {@code null}/empty
     * @return matching codec when available, otherwise {@code null}
     */
    EventPayloadCodec resolve(Class<?> payloadType, String contentType);

    /**
     * Returns a registry that resolves no codecs.
     *
     * @return empty registry
     */
    static EventPayloadCodecRegistry empty() {
        return (payloadType, contentType) -> null;
    }

    /**
     * Returns a registry that resolves over the given codecs honouring the
     * priority-ordering contract:
     * <ul>
     *   <li>Higher {@link EventPayloadCodec#priority()} wins.</li>
     *   <li>Ties resolve by registration order (first occurrence in {@code codecs} wins).</li>
     *   <li>{@code null} returned when no candidate supports the {@code (payloadType, contentType)} pair.</li>
     * </ul>
     *
     * @param codecs ordered list of candidates; non-null, may be empty, elements non-null
     * @return registry over the given codecs
     * @throws NullPointerException if {@code codecs} is null or contains a null element
     */
    @SuppressWarnings("PMD.ShortMethodName") // 'of' is a standard Java factory idiom (cf. List.of, Map.of)
    static EventPayloadCodecRegistry of(List<EventPayloadCodec> codecs) {
        Objects.requireNonNull(codecs, "codecs must not be null");
        // Snapshot + stable-sort by descending priority; ties preserve insertion order.
        List<EventPayloadCodec> ordered = new ArrayList<>(codecs);
        ordered.sort(Comparator.comparingInt(EventPayloadCodec::priority).reversed());
        List<EventPayloadCodec> snapshot = List.copyOf(ordered);
        return (payloadType, contentType) -> {
            Objects.requireNonNull(payloadType, "payloadType must not be null");
            for (EventPayloadCodec codec : snapshot) {
                if (codec.supports(payloadType, contentType)) {
                    return codec;
                }
            }
            return null;
        };
    }
}
