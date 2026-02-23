/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */

/**
 * Exeris Kernel SPI – Transport layer contracts (L0).
 *
 * <p>Defines the {@code Transport} SPI interface and transport-domain exception.
 * Protocol-specific implementations (HTTP/3, QUIC, raw TCP) reside in Community
 * or Enterprise modules and MUST NOT be referenced here.
 *
 * <h2>The Wall</h2>
 * <p>Implementation-blind: no references to Netty, io_uring, TLS cipher suites,
 * or QUIC frame types.
 */
package eu.exeris.kernel.spi.transport;

