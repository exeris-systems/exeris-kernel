/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

/**
 * SPI: Neutral response body encoder contract for typed auto-binding responses.
 *
 * <p>Implementations are provider-side and transport-agnostic: they map domain payloads
 * to an off-heap encoded representation that can be wrapped in {@link HttpResponse}.
 *
 * <p><b>Allocation:</b> allocates (one off-heap body buffer per encode, taken from the encoding
 * context's {@code MemoryAllocator}, plus the headers the encoding establishes)
 * <p><b>Ownership:</b> the buffer the encoder allocates transfers with the returned
 * {@link HttpEncodedBody} to the {@link HttpResponse} that carries it, and the engine releases it
 * after the write completes
 *
 * @since 0.5
 */
public interface HttpResponseBodyEncoder {

    /**
     * Returns true when this encoder can encode the given payload type.
     *
     * @param payloadType payload runtime class; never null
     * @return true if this encoder supports the payload type
     */
    boolean supports(Class<?> payloadType);

    /**
     * Encodes payload into an off-heap response body and optional headers.
     *
     * @param payload payload object; may be null
     * @param context encoding context; never null
     * @return encoded response body descriptor; never null — {@link HttpEncodedBody#noBody()} is
     *         the answer for a payload that encodes to nothing
     * @implSpec Allocate the body through {@link HttpResponseEncodingContext#allocator()} and
     *           release it on every path that does not return it, or the segment never returns to
     *           the pool.
     */
    HttpEncodedBody encode(Object payload, HttpResponseEncodingContext context);

    /**
     * Encoder priority. Higher value wins when multiple encoders support a type.
     *
     * @return priority, default 0
     * @implSpec The default implementation returns {@code 0}. An encoder meant to displace a
     *           provider's shipped default for the same payload type returns a higher value.
     */
    default int priority() {
        return 0;
    }
}
