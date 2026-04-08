/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
/**
 * Credential-management SPI contracts: password hashing.
 *
 * <p>Distinct from {@code eu.exeris.kernel.spi.security} (transport-boundary auth).
 * No ServiceLoader lifecycle. Utilities for direct instantiation only.
 */
package eu.exeris.kernel.spi.security.credentials;
