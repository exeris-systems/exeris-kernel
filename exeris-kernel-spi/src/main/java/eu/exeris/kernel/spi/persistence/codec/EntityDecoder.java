/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.persistence.codec;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * SPI: Off-heap entity decoder — deserialises a domain entity from a {@link LoanedBuffer}.
 *
 * <h2>Intent</h2>
 * <p>Replaces the legacy {@code EntityDeserializer<T>} with a stricter, zero-copy contract:
 * <ul>
 *   <li>Reads from a {@link LoanedBuffer} slice at a given {@code offset}</li>
 *   <li>Explicit {@code length} parameter prevents over-reads and enables bounds checking</li>
 *   <li>No checked exceptions — failures are wrapped in unchecked
 *       {@link eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException}</li>
 * </ul>
 *
 * <h2>Zero-Copy Contract (Enterprise Hot Path)</h2>
 * <p>Enterprise implementations of this interface MUST read directly from the
 * {@code source} {@link LoanedBuffer} using {@link java.lang.foreign.MemorySegment#get}
 * with the appropriate {@link java.lang.foreign.ValueLayout}. They MUST NOT copy
 * data into a heap {@code byte[]} in the hot path.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Implementations SHOULD be stateless to allow JIT scalarisation and
 * cross-VT sharing without synchronisation.
 *
 * <h2>Usage (Enterprise)</h2>
 * <pre>{@code
 * EntityDecoder<Event> decoder = new EventDecoder();
 * // cursor is the flyweight NativeRowCursor backed by the recv LoanedBuffer
 * MemorySegment segment = cursor.getSegment(0);   // zero-copy column slice
 * // decode directly from segment — no heap byte[] allocation
 * }</pre>
 *
 * <h2>Usage (Community)</h2>
 * <pre>{@code
 * EntityDecoder<Event> decoder = new EventDecoder();
 * byte[] bytes = resultSet.getBytes("payload");   // JDBC heap copy — acceptable in community
 * try (LoanedBuffer buf = allocator.allocate(AllocationHint.SMALL)) {
 *     buf.segment().copyFrom(MemorySegment.ofArray(bytes));
 *     Event event = decoder.decode(buf, 0, bytes.length);
 * }
 * }</pre>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>No reference to JDBC, HikariCP, io_uring, or any driver-specific class.
 *
 * @param <T> the entity type to decode (SHOULD be an immutable record)
 * @see EntityEncoder
 * @since 0.5.0
 */
@FunctionalInterface
public interface EntityDecoder<T> {

    /**
     * Decodes an entity from the off-heap {@code source} buffer.
     *
     * <p>The decoder MUST read from {@code source} starting at {@code offset}
     * and consuming at most {@code length} bytes. It MUST NOT allocate heap objects
     * in the hot path (Enterprise contract).
     *
     * @param source the off-heap buffer containing the serialised entity; never {@code null}
     * @param offset byte offset within {@code source} where the entity starts; must be &ge; 0
     * @param length number of bytes that represent the entity; must be &gt; 0
     * @return the decoded entity; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException
     *         on deserialisation failure or malformed data
     */
    T decode(LoanedBuffer source, int offset, int length);
}
