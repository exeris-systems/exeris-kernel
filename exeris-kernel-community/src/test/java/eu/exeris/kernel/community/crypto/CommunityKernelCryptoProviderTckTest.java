/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.contract.crypto.AbstractCryptoEngineTck;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("Community: CommunityKernelCryptoProvider TCK")
class CommunityKernelCryptoProviderTckTest extends AbstractCryptoEngineTck {

	@BeforeAll
	static void requireOpenSsl() {
		boolean available;
		try {
			new CommunityKernelCryptoProvider();
			available = true;
		} catch (Exception | Error _) {
			available = false;
		}
		assumeTrue(available,
				"OpenSSL 3.x not available on this host — skipping CommunityKernelCryptoProvider TCK");
	}

	@Override
	protected KernelCryptoProvider createProvider() {
		return new CommunityKernelCryptoProvider();
	}

	@Override
	protected MemoryAllocator createAllocator() {
		return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
	}

	@Override
	protected boolean isCommunityTier() {
		return true;
	}

	@Override
	protected boolean isIoReady() {
		return false;
	}

	@Override
	protected boolean requiresExternalBindBeforeHandshake() {
		return true;
	}
}
