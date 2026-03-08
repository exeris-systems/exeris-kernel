/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
