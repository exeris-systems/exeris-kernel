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
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslRuntime;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.core.crypto.tls.OffHeapTlsEngine;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Community {@link KernelCryptoProvider} for TCP TLS over OpenSSL 3.x.
 *
 * @since 0.5.0
 */
@SuppressWarnings({
	"PMD.CyclomaticComplexity"
})
public final class CommunityKernelCryptoProvider implements KernelCryptoProvider, AutoCloseable {

	private static final String PROVIDER_NAME = "ExerisCommunity/OpenSSL3-TCP";
	private static final int SSL_SUCCESS = 1;
	private static final long NULL_PTR = 0L;
	private final CoreOpenSslRuntime runtime;
	private final java.lang.invoke.MethodHandle sslSetFd;

	/** ServiceLoader zero-arg constructor. */
	public CommunityKernelCryptoProvider() {
		runtime = CoreOpenSslLoader.load(Arena.global());
		FunctionDescriptor setFdDescriptor =
				FunctionDescriptor.of(
						ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG,
						ValueLayout.JAVA_INT);
		sslSetFd = runtime.optionalSslHandle(
				"SSL_set_fd",
				setFdDescriptor);
		if (sslSetFd == null) {
			throw new CryptoBootstrapException(
					PROVIDER_NAME,
					"Required OpenSSL symbol SSL_set_fd not found");
		}
	}

	@Override
	@SuppressWarnings({
		"PMD.CloseResource",      // allocator/delegate ownership is transferred into CommunityTlsEngine
		"PMD.UseTryWithResources", // lifecycle uses explicit ownership transfer across success/failure paths
		"java:S2093"             // false positive: no heap resources are used, so no risk of GC finalizer delay
	})
	public TlsEngine createTlsEngine(CryptoProviderConfig config) {
		CryptoProviderConfig safeConfig = config != null ? config : CryptoProviderConfig.tcpClient();
		if (safeConfig.protocol() == CryptoProviderConfig.Protocol.QUIC) {
			throw new CryptoBootstrapException(PROVIDER_NAME,
					"Community does not support QUIC. Upgrade to Enterprise tier.");
		}
		boolean hasCert = safeConfig.certChainPath() != null;
		boolean hasKey = safeConfig.privateKeyPath() != null;
		if (hasCert ^ hasKey) {
			throw new CryptoBootstrapException(
					PROVIDER_NAME,
					"Invalid TLS server configuration: "
							+ "both certChainPath and privateKeyPath must be set together");
		}

		long startedAt = System.nanoTime();
		boolean ownsAllocator = !KernelProviders.MEMORY_ALLOCATOR.isBound();
		MemoryAllocator allocator = ownsAllocator
			? new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults())
			: KernelProviders.MEMORY_ALLOCATOR.get();
		boolean serverMode = hasCert && hasKey;

		long sslCtxPtr = NULL_PTR;
		boolean engineCreated = false;
		try {
			sslCtxPtr = createSslCtx(safeConfig, allocator, serverMode);
			OffHeapTlsEngine delegate =
					new OffHeapTlsEngine(runtime.handles(), sslCtxPtr, serverMode, allocator);

			CommunityProviderBootstrapEvent.emit(PROVIDER_NAME, System.nanoTime() - startedAt);

			CommunityTlsEngine engine = new CommunityTlsEngine(
					delegate,
					sslSetFd,
					runtime.handles().ctx(),
					sslCtxPtr,
					ownsAllocator ? allocator : null,
					safeConfig.jfrEnabled());
			engineCreated = true;
			return engine;
		} finally {
			if (!engineCreated) {
				if (sslCtxPtr != NULL_PTR) {
					runtime.handles().ctx().invokeCtxFree(sslCtxPtr);
				}
				if (ownsAllocator) {
					allocator.close();
				}
			}
		}
	}

	@Override
	public boolean supportsQuic() {
		return false;
	}

	@Override
	public String providerName() {
		return PROVIDER_NAME;
	}

	@Override
	public int priority() {
		return 0;
	}

	@Override
	public void close() {
		// Provider keeps only shared runtime handles bound to Arena.global().
	}

	// explicit cert/key cleanup keeps native failure path deterministic
	private long createSslCtx(
			CryptoProviderConfig config,
			MemoryAllocator allocator,
			boolean serverMode) {
		CoreSslHandles.CtxHandles ctx = runtime.handles().ctx();
		long methodPtr = serverMode ? ctx.invokeServerMethod() : ctx.invokeClientMethod();
		long sslCtxPtr = ctx.invokeCtxNew(methodPtr);
		if (sslCtxPtr == NULL_PTR) {
			throw new CryptoBootstrapException(PROVIDER_NAME, "SSL_CTX_new returned NULL");
		}
		boolean contextReady = false;
		try {
			ctx.invokeCtxSetVerify(sslCtxPtr, CoreOpenSslLoader.SSL_VERIFY_NONE);

			if (!serverMode) {
				contextReady = true;
				return sslCtxPtr;
			}

			PathCString certCString = toCString(config.certChainPath(), allocator);
			PathCString keyCString = toCString(config.privateKeyPath(), allocator);
			try (LoanedBuffer cert = certCString.buffer();
				 LoanedBuffer key = keyCString.buffer()) {
				long certAddress = cert.segment().address();
				long keyAddress = key.segment().address();
				int certResult = ctx.invokeCtxUseCertFile(
						sslCtxPtr,
						certAddress,
						CoreOpenSslLoader.SSL_FILETYPE_PEM);
				if (certResult != SSL_SUCCESS) {
					throw new CryptoBootstrapException(PROVIDER_NAME,
							"SSL_CTX_use_certificate_file failed", certResult);
				}

				int keyResult = ctx.invokeCtxUseKeyFile(
						sslCtxPtr,
						keyAddress,
						CoreOpenSslLoader.SSL_FILETYPE_PEM);
				if (keyResult != SSL_SUCCESS) {
					throw new CryptoBootstrapException(PROVIDER_NAME,
							"SSL_CTX_use_PrivateKey_file failed", keyResult);
				}

				int checkResult = ctx.invokeCtxCheckKey(sslCtxPtr);
				if (checkResult != SSL_SUCCESS) {
					throw new CryptoBootstrapException(
							PROVIDER_NAME,
							"SSL_CTX_check_private_key mismatch");
				}
				if (CommunityAlpnSelector.STUB != null) {
					ctx.invokeCtxSetAlpnSelectCb(
							sslCtxPtr, CommunityAlpnSelector.STUB.address(), NULL_PTR);
				}
			}

			contextReady = true;
			return sslCtxPtr;
		} finally {
			if (!contextReady) {
				ctx.invokeCtxFree(sslCtxPtr);
			}
		}
	}

	private static PathCString toCString(java.nio.file.Path path, MemoryAllocator allocator) {
		Objects.requireNonNull(path, "path must not be null");
		byte[] utf8 = path.toString().getBytes(StandardCharsets.UTF_8);
		LoanedBuffer buffer = allocator.allocateInfrastructure(utf8.length + 1L);
		MemorySegment.copy(MemorySegment.ofArray(utf8), 0L, buffer.segment(), 0L, utf8.length);
		buffer.segment().set(ValueLayout.JAVA_BYTE, utf8.length, (byte) 0);
		return new PathCString(buffer, buffer.segment().address());
	}

	private value record PathCString(LoanedBuffer buffer, long address) {
	}
}
