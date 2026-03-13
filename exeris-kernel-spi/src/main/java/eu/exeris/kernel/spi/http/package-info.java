/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */

/**
 * HTTP SPI — pure contracts for the Exeris HTTP layer.
 *
 * <h2>The Wall</h2>
 * <p>This package contains <strong>only</strong> interfaces, enums, records, and value
 * types that are meaningful to every HTTP tier (Community: HTTP/1.1 + HTTP/2; Enterprise:
 * HTTP/3 + QPACK). No transport primitives ({@code io_uring}, NIO channels, QUIC streams)
 * may appear in this package.
 *
 * <h2>Dependency direction</h2>
 * <pre>
 * exeris-kernel-spi          (LoanedBuffer, MemoryAllocator, ExerisKernelException)
 *   └─ exeris-kernel-spi-http  (this module — pure HTTP contracts)
 *        ├─ exeris-kernel-http   (HPACK, HTTP/2 framing, HTTP/1.1 wire codec)
 *        ├─ exeris-kernel-community  (HTTP/1.1 + HTTP/2 engine — implements HttpProvider)
 *        └─ exeris-kernel-enterprise (HTTP/3/QPACK engine — implements HttpProvider)
 * </pre>
 *
 * @since 0.5.0
 */
package eu.exeris.kernel.spi.http;

