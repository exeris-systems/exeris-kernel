/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("L2: CommunityKernelCryptoProvider")
class CommunityKernelCryptoProviderTest {

	@TempDir
	Path tempDir;

	private KernelCryptoProvider provider;

	@BeforeEach
	void setUp() {
		try {
			provider = new CommunityKernelCryptoProvider();
		} catch (CryptoBootstrapException _) {
			provider = null;
		}
	}

	private void assumeOpenSslAvailable() {
		assumeTrue(provider != null,
				"OpenSSL 3.x not available on this host — skipping test");
	}

	@Nested
	@DisplayName("Tier Identity")
	class TierIdentity {

		@Test
		@DisplayName("providerName() contains 'Community'")
		void providerNameContainsCommunity() {
			assumeOpenSslAvailable();
			assertThat(provider.providerName()).containsIgnoringCase("Community");
		}

		@Test
		@DisplayName("priority() == 0 (Community tier — lowest priority)")
		void priorityIsZero() {
			assumeOpenSslAvailable();
			assertThat(provider.priority()).isZero();
		}

		@Test
		@DisplayName("supportsQuic() == false — QUIC is Enterprise-only")
		void doesNotSupportQuic() {
			assumeOpenSslAvailable();
			assertThat(provider.supportsQuic()).isFalse();
		}

	}

	@Nested
	@DisplayName("TlsEngine lifecycle")
	class TlsEngineLifecycle {

		@Test
		@DisplayName("createTlsEngine() with HTTPS config returns engine or throws CryptoBootstrapException")
		void createTlsEngineWithHttpsConfig() {
			assumeOpenSslAvailable();
			Path fakeCert = tempDir.resolve("cert.pem");
			Path fakeKey = tempDir.resolve("key.pem");
			CryptoProviderConfig config = CryptoProviderConfig.httpsServer(fakeCert, fakeKey);

			assertThatCode(() -> {
				try {
					TlsEngine engine = provider.createTlsEngine(config);
					assertThat(engine).isNotNull();
					engine.close();
				} catch (CryptoBootstrapException _) {
					// cert/key absent on tmp path: acceptable in this test
				}
			}).doesNotThrowAnyException();
		}
	}
}
