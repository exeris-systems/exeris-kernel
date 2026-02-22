/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 */

/**
 * Exeris Kernel SPI – Exception Foundation (L0).
 *
 * <p>This package defines the <strong>Black-Box exception hierarchy</strong> for the
 * Exeris Kernel. It contains:
 * <ul>
 *   <li>{@link eu.exeris.kernel.spi.exceptions.ExerisKernelException} – abstract base class</li>
 *   <li>{@link eu.exeris.kernel.spi.exceptions.KernelErrorCodes} – canonical {@code EX-} code registry</li>
 * </ul>
 *
 * <h2>The Wall</h2>
 * <p>This package is part of {@code exeris-kernel-spi} and is <em>implementation-blind</em>.
 * It MUST NOT import anything from {@code io_uring}, {@code Netty}, {@code JDBC},
 * {@code Spring}, or any Community/Enterprise module.
 *
 * <h2>Zero-Allocation Contract</h2>
 * <p>No class in this package calls {@code String.format()}, {@code String.formatted()},
 * or string concatenation ({@code +}) on the exception-construction hot-path.
 */
package eu.exeris.kernel.spi.exceptions;

