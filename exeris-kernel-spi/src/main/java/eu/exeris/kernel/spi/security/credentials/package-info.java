/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Credential-management SPI contracts: password hashing.
 *
 * <p>Distinct from {@code eu.exeris.kernel.spi.security} (transport-boundary auth).
 * No ServiceLoader lifecycle. Utilities for direct instantiation only.
 */
package eu.exeris.kernel.spi.security.credentials;
