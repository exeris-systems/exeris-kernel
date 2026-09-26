/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events.codec;

import java.util.Objects;

/**
 * SPI: Minimal, immutable carrier for an {@link EventPayloadCodec} call (ADR-046).
 *
 * <p>Valhalla-ready (a {@code record}, no identity-sensitive operations). It carries
 * only what the codec needs to do its format work:
 * <ul>
 *   <li>{@code contentType} — the requested wire content-type (default
 *       {@code application/json});</li>
 *   <li>{@code eventTypeName} — the event-type name, for diagnostics / JFR only.</li>
 * </ul>
 *
 * <p><b>Format-only by design.</b> The context carries <b>no</b> redaction policy
 * (whitelist / sensitive fields), no domain types, and no framework types.
 * Redaction is the producer's job — the generated {@code *EventPublisher} builds the
 * already-redacted payload object from {@code DomainEventMetadata} <em>before</em>
 * calling {@link EventPayloadCodec#encode}. Pushing redaction into the codec would
 * couple a domain concern into the serialization seam and break the grep-symmetry
 * with the HTTP body-codec contexts.
 *
 * @param contentType   the requested content-type; never null (use {@link #JSON})
 * @param eventTypeName the event-type name for diagnostics; never null (may be empty)
 * @since 0.10
 */
public record EventCodecContext(String contentType, String eventTypeName) {

    /** The default content-type when a producer does not request a specific one. */
    public static final String JSON = "application/json";

    /**
     * Canonical constructor — requires both components, so a codec never has to distinguish
     * "absent" from "empty" when it reads them.
     *
     * @param contentType   the requested content-type; never null
     * @param eventTypeName the event-type name; never null (may be empty)
     * @throws NullPointerException if {@code contentType} or {@code eventTypeName} is {@code null}
     */
    public EventCodecContext {
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(eventTypeName, "eventTypeName must not be null");
    }

    /**
     * Creates a context for the {@link #JSON} default content-type — the shape a producer uses
     * when it has no reason to ask for a specific wire format.
     *
     * @param eventTypeName the event-type name; never null (may be empty)
     * @return a context whose {@code contentType} is {@link #JSON}
     * @throws NullPointerException if {@code eventTypeName} is {@code null}
     * @since 0.10
     */
    public static EventCodecContext json(String eventTypeName) {
        return new EventCodecContext(JSON, eventTypeName);
    }
}
