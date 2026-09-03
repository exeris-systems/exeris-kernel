/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsDecryptException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("L2: CommunityTlsEngine lifecycle guards")
class CommunityTlsEngineLifecycleTest {

	@Test
	@DisplayName("notifyBound() before explicit bind fails")
	void notifyBoundBeforeBindFails() {
		try (CommunityKernelCryptoProvider provider = createProviderOrSkip()) {
			TlsEngine tlsEngine = provider.createTlsEngine(CryptoProviderConfig.tcpClient());
			assertThatThrownBy(tlsEngine::notifyBound)
					.isInstanceOfSatisfying(TlsHandshakeException.class, ex ->
							assertThat(ex.rawArgs()[1]).asString().contains("Explicit FD binding is required before notifyBound()"));
			tlsEngine.close();
		}
	}

	@Test
	@DisplayName("beginHandshake() before explicit bind fails")
	void beginHandshakeBeforeBindFails() {
		try (CommunityKernelCryptoProvider provider = createProviderOrSkip();
				 MemoryAllocator allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
				 LoanedBuffer outbound = allocator.allocate(AllocationHint.MEDIUM)) {
			TlsEngine tlsEngine = provider.createTlsEngine(CryptoProviderConfig.tcpClient());
			assertThatThrownBy(() -> tlsEngine.beginHandshake(outbound))
					.isInstanceOfSatisfying(TlsHandshakeException.class, ex ->
							assertThat(ex.rawArgs()[1]).asString().contains("TLS engine is not bound to socket FD"));
			tlsEngine.close();
		}
	}

	@Test
	@DisplayName("wrap() before explicit bind fails")
	void wrapBeforeBindFails() {
		try (CommunityKernelCryptoProvider provider = createProviderOrSkip();
				 MemoryAllocator allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
				 LoanedBuffer plaintext = allocator.allocate(AllocationHint.MEDIUM);
				 LoanedBuffer ciphertext = allocator.allocate(AllocationHint.MEDIUM)) {
			TlsEngine tlsEngine = provider.createTlsEngine(CryptoProviderConfig.tcpClient());
			assertThatThrownBy(() -> tlsEngine.wrap(plaintext, ciphertext))
					.isInstanceOfSatisfying(TlsHandshakeException.class, ex ->
							assertThat(ex.rawArgs()[1]).asString().contains("TLS engine is not bound to socket FD"));
			tlsEngine.close();
		}
	}

	@Test
	@DisplayName("unwrap() before explicit bind fails with TlsDecryptException")
	void unwrapBeforeBindFailsWithTlsDecryptException() {
		try (CommunityKernelCryptoProvider provider = createProviderOrSkip();
				 MemoryAllocator allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
				 LoanedBuffer ciphertext = allocator.allocate(AllocationHint.MEDIUM);
				 LoanedBuffer plaintext = allocator.allocate(AllocationHint.MEDIUM)) {
			TlsEngine tlsEngine = provider.createTlsEngine(CryptoProviderConfig.tcpClient());
			assertThatThrownBy(() -> tlsEngine.unwrap(ciphertext, plaintext))
					.isInstanceOfSatisfying(TlsDecryptException.class, ex ->
							assertThat(ex.rawArgs()[1]).asString().contains("TLS engine is not bound to socket FD"));
			tlsEngine.close();
		}
	}

	@Test
	@DisplayName("close() is idempotent")
	void closeIsIdempotent() {
		try (CommunityKernelCryptoProvider provider = createProviderOrSkip()) {
			TlsEngine tlsEngine = provider.createTlsEngine(CryptoProviderConfig.tcpClient());
			assertThatCode(tlsEngine::close).doesNotThrowAnyException();
			assertThatCode(tlsEngine::close).doesNotThrowAnyException();
		}
	}

	private static CommunityKernelCryptoProvider createProviderOrSkip() {
		try {
			return new CommunityKernelCryptoProvider();
		} catch (CryptoBootstrapException exception) {
			assumeTrue(false, "OpenSSL 3.x not available on this host - skipping test");
			throw new IllegalStateException("unreachable", exception);
		}
	}
}
