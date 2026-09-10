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
 * <p>The {@code of(...)} contract mirrors the HTTP body-codec registries
 * (e.g. {@code HttpRequestBodyDecoderRegistry.of}) verbatim: snapshot, stable
 * descending sort on {@code priority()}, ties by insertion order, first
 * {@code supports(...) == true} wins.
 *
 * @implSpec When several candidates report {@code supports(...) == true}, the resolver returns
 *           the one with the highest {@link EventPayloadCodec#priority()}; equal priorities
 *           resolve by registration order, first-registered winning. When no candidate supports
 *           the inputs the resolver returns {@code null} rather than raising.
 * @apiNote A {@code null} resolution means "no codec configured", not "encoding failed" — the
 *          producer (the generated {@code *EventPublisher}) falls back to
 *          {@link eu.exeris.kernel.spi.events.EventPayload#empty()} on it.
 * @since 0.10
 */
@FunctionalInterface
public interface EventPayloadCodecRegistry {

    /**
     * Picks the codec that serves the given payload type and content type, or reports that none
     * is configured for the pair.
     *
     * @param payloadType desired payload runtime class; never null
     * @param contentType the requested content-type, or {@code null}/empty
     * @return the highest-priority supporting codec, or {@code null} when none supports the pair
     */
    EventPayloadCodec resolve(Class<?> payloadType, String contentType);

    /**
     * Supplies the registry a provider binds when it ships no codec at all — resolution always
     * comes back empty, so the producer falls back to
     * {@link eu.exeris.kernel.spi.events.EventPayload#empty()}.
     *
     * @return a registry whose {@link #resolve} returns {@code null} for every input
     */
    static EventPayloadCodecRegistry empty() {
        return (payloadType, contentType) -> null;
    }

    /**
     * Builds the standard registry over a fixed set of candidates, honouring the
     * priority-ordering contract:
     * <ul>
     *   <li>Higher {@link EventPayloadCodec#priority()} wins.</li>
     *   <li>Ties resolve by registration order (first occurrence in {@code codecs} wins).</li>
     *   <li>{@code null} returned when no candidate supports the {@code (payloadType, contentType)} pair.</li>
     * </ul>
     *
     * @param codecs ordered list of candidates; non-null, may be empty, elements non-null
     * @return a registry over a snapshot of {@code codecs}; later mutation of the caller's list
     *         does not change what it resolves
     * @throws NullPointerException if {@code codecs} is null or contains a null element, and from
     *         the returned registry if {@code payloadType} is null at resolution time
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
