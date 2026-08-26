/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.persistence.codec;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * SPI: Off-heap entity encoder — serialises a domain entity into a {@link LoanedBuffer}.
 *
 * <h2>Intent</h2>
 * <p>Replaces the legacy {@code EntitySerializer<T>} with a stricter contract that:
 * <ul>
 *   <li>Returns the number of bytes written (for COPY framing and buffer management)</li>
 *   <li>Accepts a pre-allocated {@link LoanedBuffer} target — zero heap byte[] copy</li>
 *   <li>Declares no checked exceptions — all failures are wrapped in unchecked
 *       {@link eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException}</li>
 * </ul>
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Implementations SHOULD be stateless. A stateless encoder can be shared across
 * Virtual Threads without synchronisation. Stateless functional implementations
 * will JIT-scalarise naturally.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * EntityEncoder<Event> encoder = new EventEncoder();
 * try (LoanedBuffer buf = allocator.allocate(AllocationHint.MEDIUM)) {
 *     int bytes = encoder.encode(event, buf);
 *     transport.write(buf, bytes);
 * }
 * }</pre>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>No reference to JDBC, HikariCP, io_uring, or any driver-specific class.
 *
 * @param <T> the entity type to encode (SHOULD be an immutable record)
 * @see EntityDecoder
 * @since 0.5.0
 */
@FunctionalInterface
public interface EntityEncoder<T> {

    /**
     * Encodes {@code entity} into the off-heap {@code target} buffer.
     *
     * <p>The encoder MUST write to {@code target} starting at offset 0 and
     * return the total bytes written. It MUST NOT allocate any heap objects
     * in the encoding hot-path.
     *
     * @param entity the entity to encode; never {@code null}
     * @param target the pre-allocated off-heap buffer to write into; never {@code null}
     * @return number of bytes written into {@code target}
     * @throws eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException
     *         on serialisation failure or buffer capacity overflow
     */
    int encode(T entity, LoanedBuffer target);
}
