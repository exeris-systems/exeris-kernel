/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.persistence.codec;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * SPI: Off-heap entity decoder — deserialises a domain entity from a {@link LoanedBuffer}.
 *
 * <h2>Intent</h2>
 * <p>The contract is deliberately narrow, and zero-copy:
 * <ul>
 *   <li>Reads from a {@link LoanedBuffer} slice at a given {@code offset}</li>
 *   <li>Explicit {@code length} parameter prevents over-reads and enables bounds checking</li>
 *   <li>No checked exceptions — failures are wrapped in unchecked
 *       {@link eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException}</li>
 * </ul>
 *
 * <h2>Usage (Enterprise)</h2>
 * {@snippet lang="java" :
 * EntityDecoder<Event> decoder = new EventDecoder();
 * // cursor is the flyweight row cursor backed by the recv LoanedBuffer
 * MemorySegment segment = cursor.getSegment(0);   // zero-copy column slice
 * // decode directly from segment — no heap byte[] allocation
 * }
 *
 * <h2>Usage (Community)</h2>
 * {@snippet lang="java" :
 * EntityDecoder<Event> decoder = new EventDecoder();
 * byte[] bytes = resultSet.getBytes("payload");   // JDBC heap copy — acceptable in community
 * try (LoanedBuffer buf = allocator.allocate(AllocationHint.SMALL)) {
 *     buf.segment().copyFrom(MemorySegment.ofArray(bytes));
 *     Event event = decoder.decode(buf, 0, bytes.length);
 * }
 * }
 *
 * <p><b>Allocation:</b> allocates (the returned entity, one per call); the bytes themselves are
 * read in place, and no intermediate heap {@code byte[]} is created.
 * <p><b>Thread confinement:</b> any thread — a stateless decoder is shared across virtual threads
 * without synchronisation.
 * <p><b>Ownership:</b> the caller allocates and releases the {@link LoanedBuffer}; ownership does
 * not move across the call, and the decoded entity belongs to the caller.
 *
 * @param <T> the entity type to decode (SHOULD be an immutable record)
 * @implSpec An Enterprise-tier implementation MUST read directly from the {@code source}
 *           {@link LoanedBuffer} through {@link java.lang.foreign.MemorySegment#get} with the
 *           appropriate {@link java.lang.foreign.ValueLayout}, and MUST NOT stage the data in a
 *           heap {@code byte[]} first. An implementation SHOULD be stateless, which is what
 *           permits cross-thread sharing and JIT scalarisation, and MUST NOT reference JDBC,
 *           HikariCP, io_uring or any driver-specific class.
 * @since 0.5
 * @see EntityEncoder
 */
@FunctionalInterface
public interface EntityDecoder<T> {

    /**
     * Decodes one entity from the {@code length} bytes of {@code source} that begin at
     * {@code offset}.
     *
     * @param source the off-heap buffer containing the serialised entity; never {@code null}
     * @param offset byte offset within {@code source} where the entity starts; must be &ge; 0
     * @param length number of bytes that represent the entity; must be &gt; 0
     * @return the decoded entity; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException
     *         on deserialisation failure or malformed data
     * @implSpec An implementation MUST confine its reads to {@code [offset, offset + length)} —
     *           the bound is what turns a truncated or malformed payload into an exception rather
     *           than into a neighbouring row's bytes — and beyond the entity it returns MUST NOT
     *           allocate on the hot path.
     */
    T decode(LoanedBuffer source, int offset, int length);
}
