/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

/**
 * SPI: Neutral request body encoder contract for typed outbound HTTP request bodies.
 *
 * <p>Implementations are provider-side and transport-agnostic: they marshal a domain
 * payload to an off-heap encoded body that can be wrapped in {@link HttpRequest}.
 * The mirror of the server-side {@link HttpResponseBodyEncoder} (since 0.5.0); both
 * share the {@link HttpEncodedBody} carrier shape.
 *
 * <p><b>Allocation:</b> allocates (one off-heap body buffer per encode, taken from the encoding
 * context's {@code MemoryAllocator}, plus the headers the encoding establishes)
 * <p><b>Ownership:</b> the buffer the encoder allocates transfers with the returned
 * {@link HttpEncodedBody} to the {@link HttpRequest} that carries it; this interface does not
 * establish who releases that buffer after the write. The encoder releases it itself only on a
 * path where it returns nothing
 *
 * @implSpec An implementation:
 *           <ul>
 *             <li>MUST NOT depend on request headers. {@link HttpRequestEncodingContext} carries
 *                 only {@code (method, path, allocator)} because the encoder runs <em>before</em>
 *                 header finalisation; its job is body bytes plus the {@code content-type} it owns,
 *                 returned through {@link HttpEncodedBody#headers()} and merged with the façade's
 *                 own headers ({@code accept}, {@code content-length}) and any enricher's at the
 *                 call site;</li>
 *             <li>MUST wrap binding-specific exceptions (a Jackson {@code JacksonException}, say)
 *                 in a generic {@link RuntimeException} — typically {@link IllegalStateException} —
 *                 so no driver type crosses the SPI boundary.</li>
 *           </ul>
 * @implNote The Community client engine does not release the buffer after the write.
 * @since 0.8
 */
public interface HttpRequestBodyEncoder {

    /**
     * Returns true when this encoder can encode the given payload type.
     *
     * @param payloadType payload runtime class; never null
     * @return true if this encoder supports the payload type
     */
    boolean supports(Class<?> payloadType);

    /**
     * Encodes payload into an off-heap request body plus the {@code content-type}
     * header(s) the encoder owns.
     *
     * @param payload payload object; non-null (the registry filters non-supports cases out)
     * @param context encoding context; never null
     * @return encoded request body descriptor; never null
     * @implSpec Allocate the body through {@link HttpRequestEncodingContext#allocator()} and
     *           release it on every path that does not return it, or the segment never returns to
     *           the pool. Ownership of a returned buffer belongs to the {@link HttpRequest} that
     *           carries it.
     * @apiNote Do not close the buffer after handing the carrier to a request; this interface does
     *          not establish who releases it after the write.
     * @implNote The Community client engine does not release the buffer after the write.
     */
    HttpEncodedBody encode(Object payload, HttpRequestEncodingContext context);

    /**
     * Encoder priority. Higher value wins when multiple encoders support a type.
     *
     * @return priority, default 0
     * @implSpec The default implementation returns {@code 0}. An encoder meant to displace a
     *           provider's shipped default for the same payload type returns a higher value; equal
     *           values leave registration order to break the tie.
     */
    default int priority() {
        return 0;
    }
}
