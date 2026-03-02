/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
/**
 * Off-heap entity serialisation codec contracts for the Exeris Persistence SPI.
 *
 * <h2>Package Contents</h2>
 * <ul>
 *   <li>{@link eu.exeris.kernel.spi.persistence.codec.EntityEncoder} — encodes entity to
 *       off-heap {@code LoanedBuffer}; returns bytes written</li>
 *   <li>{@link eu.exeris.kernel.spi.persistence.codec.EntityDecoder} — decodes entity from
 *       off-heap {@code LoanedBuffer} at a given offset and length</li>
 * </ul>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This package contains ONLY pure SPI interfaces — no JDBC, HikariCP, io_uring,
 * or any driver-specific class references are permitted here.
 *
 * @since 0.5.0
 */
package eu.exeris.kernel.spi.persistence.codec;

