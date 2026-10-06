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
 * <p>The contract is deliberately narrow:
 * <ul>
 *   <li>Returns the number of bytes written (for COPY framing and buffer management)</li>
 *   <li>Accepts a pre-allocated {@link LoanedBuffer} target — zero heap byte[] copy</li>
 *   <li>Declares no checked exceptions — all failures are wrapped in unchecked
 *       {@link eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException}</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * {@snippet lang="java" :
 * EntityEncoder<Event> encoder = new EventEncoder();
 * try (LoanedBuffer buf = allocator.allocate(AllocationHint.MEDIUM)) {
 *     int bytes = encoder.encode(event, buf);
 *     transport.write(buf, bytes);
 * }
 * }
 *
 * <p><b>Allocation:</b> zero-alloc on hot path — the encoder writes into the caller's buffer and
 * allocates no heap object while encoding.
 * <p><b>Thread confinement:</b> any thread — a stateless encoder is shared across virtual threads
 * without synchronisation.
 * <p><b>Ownership:</b> the caller allocates and releases the {@link LoanedBuffer}; ownership does
 * not move across the call.
 *
 * @param <T> the entity type to encode (SHOULD be an immutable record)
 * @implSpec An implementation SHOULD be stateless — that is what makes one instance shareable
 *           across virtual threads and lets the JIT scalarise it — and MUST NOT reference JDBC,
 *           HikariCP, io_uring or any driver-specific class.
 * @since 0.5
 * @see EntityDecoder
 */
@FunctionalInterface
public interface EntityEncoder<T> {

    /**
     * Encodes {@code entity} into the off-heap {@code target} buffer, starting at offset 0.
     *
     * @param entity the entity to encode; never {@code null}
     * @param target the pre-allocated off-heap buffer to write into; never {@code null}
     * @return number of bytes written into {@code target} — the prefix of the buffer the caller
     *         may then hand to a transport or a COPY frame
     * @throws eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException
     *         on serialisation failure or buffer capacity overflow
     * @implSpec An implementation MUST write from offset 0, MUST return the exact byte count it
     *           wrote, and MUST NOT allocate heap objects while encoding.
     */
    int encode(T entity, LoanedBuffer target);
}
