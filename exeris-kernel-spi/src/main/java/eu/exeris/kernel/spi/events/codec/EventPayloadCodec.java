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
 * <h2>Ownership (RAII)</h2>
 * <p>{@link #encode} returns an {@link EventPayload} the caller owns
 * (refCount == 1); the caller transfers it to {@code EventBus.publish}, which then
 * manages broadcast lifetime per the existing {@link EventPayload} contract.
 * {@link #decode} MUST NOT close, retain, or otherwise extend the lifetime of the
 * supplied payload — the caller owns that buffer's lifecycle.
 *
 * <h2>The Wall</h2>
 * <p>Implementations MUST wrap binding-specific exceptions (e.g. a Jackson
 * {@code JacksonException}) into a JDK-standard {@code java.*}
 * {@link RuntimeException} before returning; no driver-package type — not even a
 * driver-defined {@code RuntimeException} subclass — may cross this SPI boundary
 * (testable-as-written; the TCK asserts the thrown type's package
 * {@code startsWith("java.")}).
 *
 * @since 0.10
 * @see EventPayloadCodecRegistry
 * @see EventCodecContext
 * @see EventPayload
 */
public interface EventPayloadCodec {

    /**
     * Returns {@code true} when this codec can handle the given payload type and
     * content type. Implementations MUST tolerate a {@code null}/empty
     * {@code contentType} (the producer may rely on a default) without throwing.
     *
     * @param payloadType the runtime payload class; never null
     * @param contentType the requested content-type, or {@code null}/empty
     * @return {@code true} when this codec can encode/decode the pair
     */
    boolean supports(Class<?> payloadType, String contentType);

    /**
     * Encodes a typed/structured payload into a bytes-backed {@link EventPayload}.
     *
     * <p>The returned payload is owned by the caller (refCount == 1) and is the
     * value handed to {@code EventBus.publish}. Binding exceptions MUST be wrapped
     * into a {@code java.*} {@link RuntimeException} (The Wall).
     *
     * @param payload the payload object to serialize; never null
     * @param ctx     the codec context (requested content-type + event-type name); never null
     * @return a caller-owned, bytes-backed {@link EventPayload}; never null
     */
    EventPayload encode(Object payload, EventCodecContext ctx);

    /**
     * Decodes an {@link EventPayload} back into an instance of {@code targetType}.
     *
     * <p>Generics-free by design — returns {@code Object}; the single cast lives at
     * the consumer call-site. MUST NOT close or retain the supplied payload.
     * Returns {@code null} for an empty payload (length 0); binding exceptions MUST
     * be wrapped into a {@code java.*} {@link RuntimeException} (The Wall).
     *
     * @param payload    the wire payload to deserialize; never null
     * @param targetType the desired runtime class; never null
     * @param ctx        the codec context; never null
     * @return the decoded value, or {@code null} for an empty payload
     */
    Object decode(EventPayload payload, Class<?> targetType, EventCodecContext ctx);

    /**
     * Resolution priority; higher wins. Default {@code 0}.
     *
     * @return non-negative priority
     */
    default int priority() {
        return 0;
    }
}
