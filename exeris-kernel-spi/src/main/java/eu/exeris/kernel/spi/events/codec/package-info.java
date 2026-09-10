/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Exeris Kernel Event-Payload Codec SPI (ADR-046) — the pluggable serialization
 * seam for domain-event payloads.
 *
 * <h2>Purpose</h2>
 * <p>The event-side analog of the HTTP body-codec matrix (ADR-009 / ADR-034 / ADR-036).
 * {@code EventBus.publish} carries an {@link eu.exeris.kernel.spi.events.EventPayload}
 * of already-serialized bytes; this package supplies the seam that turns a typed
 * payload into those bytes (and back), so payload serialization is client-selectable
 * (JSON default; gRPC / Avro / other pluggable) rather than baked into producer code.
 *
 * <h2>Surface</h2>
 * <ul>
 *   <li>{@link eu.exeris.kernel.spi.events.codec.EventPayloadCodec} — encode/decode contract.</li>
 *   <li>{@link eu.exeris.kernel.spi.events.codec.EventPayloadCodecRegistry} — priority resolution.</li>
 *   <li>{@link eu.exeris.kernel.spi.events.codec.EventCodecContext} — minimal, format-only carrier.</li>
 * </ul>
 *
 * <h2>Resolution &amp; propagation</h2>
 * <p>Resolved in the producer (the generated {@code *EventPublisher}) — ADR-036
 * "site B" — so {@code EventBus} / {@code EventEngine} stay unchanged. The registry is
 * exposed via the optional
 * {@link eu.exeris.kernel.spi.context.KernelProviders#EVENT_PAYLOAD_CODEC_REGISTRY}
 * {@link java.lang.ScopedValue} slot (the {@code EVENT_STREAM_READER} /
 * {@code EVENT_STREAM_APPENDER} precedent), inherited by every virtual thread in the
 * kernel scope.
 *
 * @since 0.10
 */
package eu.exeris.kernel.spi.events.codec;
