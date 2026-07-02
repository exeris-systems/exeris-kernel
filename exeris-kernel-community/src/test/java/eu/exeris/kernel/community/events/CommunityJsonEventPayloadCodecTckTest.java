/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.codec.EventCodecContext;
import eu.exeris.kernel.spi.events.codec.EventPayloadCodec;
import eu.exeris.kernel.spi.events.codec.EventPayloadCodecRegistry;
import eu.exeris.kernel.tck.contract.events.AbstractEventPayloadCodecTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community binding of {@link AbstractEventPayloadCodecTck} against
 * {@link CommunityJsonEventPayloadCodec} (Jackson 3) — ADR-046.
 *
 * <p>The abstract base carries the codec contract (encode/decode round-trip,
 * registry priority, ownership, driver-exception opacity). The
 * {@link KernelProviders#EVENT_PAYLOAD_CODEC_REGISTRY} {@code ScopedValue} slot read
 * path the generated {@code *EventPublisher} uses is a Community-tier wiring concern,
 * not a codec-contract concern, so it is folded in as a {@link Nested} class.
 *
 * @since 0.10.0
 */
@DisplayName("Community: EventPayloadCodec TCK (Jackson 3)")
class CommunityJsonEventPayloadCodecTckTest extends AbstractEventPayloadCodecTck {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Override
    protected EventPayloadCodec createCodec() {
        return new CommunityJsonEventPayloadCodec(MAPPER);
    }

    @Override
    protected String supportedContentType() {
        return "application/json";
    }

    @Override
    protected String structuredSuffixContentType() {
        // application/*+json structured-syntax suffix per RFC 6838 §4.2.8.
        return "application/vnd.exeris+json";
    }

    @Override
    protected Object validPayloadObject() {
        return Map.of("k", "v");
    }

    @Override
    protected Class<?> validTargetType() {
        return Map.class;
    }

    @Override
    protected byte[] malformedEncodedBytes() {
        // Truncated JSON object — Jackson surfaces JacksonException; driver MUST wrap.
        return "{ \"k\": ".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected Object unserializablePayloadObject() {
        // A self-referential map — Jackson trips its StreamWriteConstraints max-nesting-depth
        // and raises a JacksonException; the driver MUST wrap it into a java.* RuntimeException.
        Map<String, Object> cyclic = new java.util.HashMap<>();
        cyclic.put("self", cyclic);
        return cyclic;
    }

    // ------------------------------------------------------------------------
    // Community-tier wiring — the slot read path the generated publisher uses.
    // NOT a codec-contract concern, so it does not belong in the abstract TCK.
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("KernelProviders.EVENT_PAYLOAD_CODEC_REGISTRY slot wiring")
    class SlotWiring {

        @Test
        @DisplayName("registry is readable via the slot inside scope, empty outside (generated-publisher read path)")
        @SuppressWarnings("unchecked") // confined Map cast in the assertion; the SPI is generics-free by design
        void slotBoundDuringPublish() {
            // Source the registry exactly as CommunityEventsSubsystem.providerBindings() does —
            // from the provider — so this exercises the provider→slot wiring end to end.
            EventPayloadCodecRegistry registry = new CommunityEventProvider()
                    .eventPayloadCodecRegistry()
                    .orElseThrow();

            assertThat(KernelProviders.eventPayloadCodecRegistry())
                    .as("slot must be empty outside the kernel scope")
                    .isEmpty();

            ScopedValue.where(KernelProviders.EVENT_PAYLOAD_CODEC_REGISTRY, registry).run(() -> {
                var bound = KernelProviders.eventPayloadCodecRegistry();
                assertThat(bound).as("slot must be present inside the kernel scope").isPresent();

                EventPayloadCodec resolved = bound.orElseThrow().resolve(Map.class, "application/json");
                assertThat(resolved).as("registry must resolve the JSON codec").isNotNull();

                EventCodecContext ctx = EventCodecContext.json("WidgetCreated");
                try (EventPayload encoded = resolved.encode(Map.of("k", "v"), ctx)) {
                    assertThat((Map<Object, Object>) resolved.decode(encoded, Map.class, ctx))
                            .as("round-trip through the slot-resolved codec")
                            .containsEntry("k", "v");
                }
            });

            assertThat(KernelProviders.eventPayloadCodecRegistry())
                    .as("slot must be empty again after the scope closes")
                    .isEmpty();
        }
    }
}
