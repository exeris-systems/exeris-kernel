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
 * <h2>Module layout</h2>
 * <p>
 * In the current kernel module structure:
 * </p>
 * <ul>
 *     <li>The HTTP SPI contracts live in the {@code exeris-kernel-spi} module,
 *     under the {@code eu.exeris.kernel.spi.http} package (this package).</li>
 *     <li>The HTTP codecs and orchestration logic live in the
 *     {@code exeris-kernel-core} module, as described in
 *     {@code docs/subsystems/http.md}.</li>
 * </ul>
 *
 * <p>
 * Any previous references to separate reactor modules such as
 * {@code exeris-kernel-spi-http} or {@code exeris-kernel-http} are obsolete
 * and do not reflect the current repository layout.
 * </p>
 *
 * @since 0.5.0
 */
package eu.exeris.kernel.spi.http;

