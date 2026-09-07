/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
/**
 * Credential-management SPI contracts: password hashing.
 *
 * <p>Distinct from {@code eu.exeris.kernel.spi.security}, which authenticates a credential already
 * issued by an identity provider at the transport boundary; this package is what a deployment uses
 * to store and check one of its own. There is no {@code ServiceLoader} lifecycle here — an
 * implementation is constructed directly and held by whoever needs it.
 */
package eu.exeris.kernel.spi.security.credentials;
