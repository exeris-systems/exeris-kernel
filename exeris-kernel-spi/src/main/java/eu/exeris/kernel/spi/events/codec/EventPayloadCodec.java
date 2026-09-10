/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events.codec;

import eu.exeris.kernel.spi.events.EventPayload;

/**
 * SPI: Pluggable serialization seam for domain-event payloads (ADR-046).
 *
 * <p>The event side of the body-codec matrix that ADR-009 / ADR-034 / ADR-036
 * established for HTTP request/response bodies. {@code EventBus.publish} carries
 * an {@link EventPayload} of <b>already-serialized bytes</b>; this codec is the
 * seam that produces those bytes from a typed/structured payload (and decodes
 * back), so a producer hands the engine a typed payload and the chosen codec
 * yields the wire form. JSON is the default; gRPC / Avro / other drivers plug in
 * behind the same SPI without regenerating producer code.
 *
 * <h2>Resolution (ADR-036 "site B")</h2>
 * <p>The codec is resolved by the <b>producer</b> (the generated
 * {@code *EventPublisher}) via {@link EventPayloadCodecRegistry}, keyed by
 * {@code (payloadType, contentType)} — <b>not</b> inside {@code EventBus}, which
 * stays byte-for-byte unchanged. Higher {@link #priority()} wins; ties resolve by
 * registration order (see {@link EventPayloadCodecRegistry#of(java.util.List)}).
 *
 * <p><b>Allocation:</b> allocates ({@link #encode} produces the wire bytes and the
 * {@link EventPayload} that carries them, once per call; {@link #decode} produces the decoded
 * object)
 * <p><b>Ownership:</b> the payload {@link #encode} returns is the caller's, at reference count
 * {@code 1}, and the caller passes it on to {@code EventBus.publish}; the payload handed to
 * {@link #decode} stays the caller's throughout
 *
 * @implSpec {@link #decode} does not close, retain, or otherwise extend the lifetime of the
 *           payload it is given. Binding-specific exceptions (a Jackson
 *           {@code JacksonException}, say) are wrapped into a JDK-standard {@code java.*}
 *           {@link RuntimeException} before they leave: no driver-package type — not even a
 *           driver-defined {@code RuntimeException} subclass — crosses this SPI boundary. The
 *           TCK asserts the thrown type's package {@code startsWith("java.")}.
 * @since 0.10
 * @see EventPayloadCodecRegistry
 * @see EventCodecContext
 * @see EventPayload
 */
public interface EventPayloadCodec {

    /**
     * Declares whether this codec claims the given (payload type, content type) pair — the
     * predicate {@link EventPayloadCodecRegistry} resolves on.
     *
     * @param payloadType the runtime payload class; never null
     * @param contentType the requested content-type, or {@code null}/empty
     * @return {@code true} when this codec can encode and decode the pair
     * @implSpec Tolerates a {@code null} or empty {@code contentType} — the producer may be
     *           relying on a default — by answering the question rather than throwing.
     */
    boolean supports(Class<?> payloadType, String contentType);

    /**
     * Serializes a typed payload into the wire bytes the bus carries, wrapped in a payload the
     * caller owns.
     *
     * @param payload the payload object to serialize; never null
     * @param ctx     the codec context (requested content-type + event-type name); never null
     * @return a bytes-backed {@link EventPayload} at reference count {@code 1}, never null
     * @implSpec Binding exceptions are wrapped into a {@code java.*} {@link RuntimeException}
     *           before they leave (The Wall).
     * @apiNote The returned payload is the value handed to {@code EventBus.publish}, which then
     *          takes ownership of it — so a caller that encodes and does not publish still owes
     *          the {@link EventPayload#close()}.
     */
    EventPayload encode(Object payload, EventCodecContext ctx);

    /**
     * Reads wire bytes back into an instance of {@code targetType}, leaving the payload's
     * lifecycle entirely to the caller.
     *
     * <p>Generics-free by design — it returns {@code Object}, so the single cast lives at the
     * consumer call-site.
     *
     * @param payload    the wire payload to deserialize; never null
     * @param targetType the desired runtime class; never null
     * @param ctx        the codec context; never null
     * @return the decoded value, or {@code null} when the payload is empty (length 0)
     * @implSpec Does not close or retain the supplied payload. Binding exceptions are wrapped
     *           into a {@code java.*} {@link RuntimeException} before they leave (The Wall).
     */
    Object decode(EventPayload payload, Class<?> targetType, EventCodecContext ctx);

    /**
     * Ranks this codec against the others registered for the same pair; the registry picks the
     * highest.
     *
     * @return this codec's rank — non-negative, defaulting to {@code 0}; ties between equal ranks
     *         are broken by registration order
     */
    default int priority() {
        return 0;
    }
}
