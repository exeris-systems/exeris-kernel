/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */

/**
 * Exeris Kernel SPI – Bootstrap / Subsystem lifecycle contracts (L0).
 *
 * <p>Defines the {@code Subsystem} SPI interface and lifecycle exception.
 * Bootstrap sequence is orchestrated by {@code KernelBootstrap} in the Core module
 * using {@code StructuredTaskScope} for parallel initialization.
 *
 * <h2>The Wall</h2>
 * <p>Implementation-blind: no references to specific subsystem implementations,
 * Spring lifecycle, or CDI.
 */
package eu.exeris.kernel.spi.bootstrap;

