/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslRuntime;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.core.crypto.tls.OffHeapTlsEngine;
import eu.exeris.kernel.core.crypto.tls.TlsPeerIdentity;
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
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Community {@link KernelCryptoProvider} that creates {@link TlsEngine} instances backed by
 * OpenSSL 3.x through the Panama foreign-function API.
 *
 * <p>{@link #createTlsEngine} supports {@code Protocol.TCP_TLS} only and throws
 * {@link CryptoBootstrapException} for {@code Protocol.QUIC}. Registers at {@link #priority()}
 * {@code 0}; per {@link KernelCryptoProvider}'s discovery contract, a higher-priority provider on
 * the classpath is selected instead.
 *
 * <h2>Protocol floor</h2>
 * <p>Every context, server and client, refuses a peer that negotiates below
 * {@link CryptoProviderConfig#minimumTlsVersion()}: {@code "TLSv1.2"} and {@code "TLSv1.3"} are
 * served, and any other value fails the engine's creation.
 *
 * <h2>Client verification</h2>
 * <p>A client engine verifies its server. {@link #createClientTlsEngine} builds one that expects a
 * given peer and verifies against a {@link CommunityTlsClientTrust}; {@link #createTlsEngine} with a
 * client configuration names no peer, so the engine it returns refuses its handshake with
 * {@code TlsHandshakeException} ({@code EX-NET-2001}) instead of completing unauthenticated.
 *
 * @since 0.5
 */
@SuppressWarnings({
	"PMD.CyclomaticComplexity",
	"PMD.TooManyMethods"      // the SPI surface, the client entry points and the ordered native set-up steps
})
public final class CommunityKernelCryptoProvider implements KernelCryptoProvider, AutoCloseable {

	private static final String PROVIDER_NAME = "ExerisCommunity/OpenSSL3-TCP";
	private static final int SSL_SUCCESS = 1;
	private static final long NULL_PTR = 0L;
	private final CoreOpenSslRuntime runtime;
	private final MethodHandle sslSetFd;
	private final ClientPeerBinder peerBinder;

	/**
	 * Loads the Community OpenSSL runtime and resolves the {@code SSL_set_fd} symbol.
	 *
	 * @throws CryptoBootstrapException ({@code EX-NET-2002}) if the OpenSSL runtime cannot be
	 *         loaded, or if it does not export {@code SSL_set_fd}
	 */
	public CommunityKernelCryptoProvider() {
		this(OffHeapTlsEngine::expectPeer);
	}

	/**
	 * As {@link #CommunityKernelCryptoProvider()}, with the step that gives a client engine its
	 * expected peer replaced, so a test can make that step fail.
	 *
	 * @param peerBinder applies the expected peer to a freshly built engine
	 */
	/* default */ CommunityKernelCryptoProvider(ClientPeerBinder peerBinder) {
		this.peerBinder = Objects.requireNonNull(peerBinder, "peerBinder must not be null");
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

	/**
	 * Creates a {@link TlsEngine} bound to a freshly created OpenSSL {@code SSL_CTX}.
	 *
	 * <p>A {@code null} config is treated as {@link CryptoProviderConfig#tcpClient()}. The engine
	 * is configured for server mode when both {@link CryptoProviderConfig#certChainPath()} and
	 * {@link CryptoProviderConfig#privateKeyPath()} are set, for client mode when neither is set;
	 * setting exactly one of the two is rejected. When {@link KernelProviders#MEMORY_ALLOCATOR} is
	 * not bound, this method creates a {@link CommunityMemoryProvider} allocator that the returned
	 * engine owns and closes; when it is bound, the returned engine uses it without taking
	 * ownership.
	 *
	 * <p>A client engine from this method has no expected peer. Its context verifies the server
	 * ({@code SSL_VERIFY_PEER}) against an empty store, and its {@link TlsEngine#beginHandshake}
	 * throws {@code TlsHandshakeException} ({@code EX-NET-2001}) once it is bound, so it never
	 * completes a handshake unauthenticated. It binds, reports its phase and closes like any other
	 * engine; {@link #createClientTlsEngine} is the entry point for a client that connects.
	 *
	 * @param config cryptographic configuration, or {@code null} for {@link CryptoProviderConfig#tcpClient()}
	 * @return a TLS engine ready for {@link TlsEngine#beginHandshake}
	 * @throws CryptoBootstrapException ({@code EX-NET-2002}) for {@code Protocol.QUIC}, for a
	 *         config with exactly one of {@code certChainPath}/{@code privateKeyPath} set, for a
	 *         {@code minimumTlsVersion} other than {@code TLSv1.2} or {@code TLSv1.3}, or if
	 *         the native SSL context cannot be created or configured
	 */
	@Override
	public TlsEngine createTlsEngine(CryptoProviderConfig config) {
		CryptoProviderConfig safeConfig = config != null ? config : CryptoProviderConfig.tcpClient();
		requireTcp(safeConfig);
		boolean hasCert = safeConfig.certChainPath() != null;
		boolean hasKey = safeConfig.privateKeyPath() != null;
		if (hasCert ^ hasKey) {
			throw new CryptoBootstrapException(
					PROVIDER_NAME,
					"Invalid TLS server configuration: "
							+ "both certChainPath and privateKeyPath must be set together");
		}
		boolean serverMode = hasCert && hasKey;
		return buildEngine(safeConfig, serverMode, null, !serverMode);
	}

	/**
	 * Creates a client engine that verifies its server: the certificate chain against
	 * {@code trust}, and the certificate's subject alternative names against {@code peer}. A DNS
	 * name is matched against DNS entries and an IP address against IP entries; the subject common
	 * name is never consulted, and a wildcard matches one whole leftmost label only. A DNS name is
	 * sent as the server name indication; an IP address is not. A server that fails verification
	 * fails the handshake, which the engine reports as {@code CLOSED} with the {@code X509_V_*} code
	 * on {@link eu.exeris.kernel.core.crypto.tls.TlsHandshakeFailureCodes}.
	 *
	 * <p>{@code trust} may be closed while the engine lives: the engine's context holds its own
	 * reference to the store. Allocator ownership follows {@link #createTlsEngine}.
	 *
	 * @param config a client configuration, or {@code null} for {@link CryptoProviderConfig#tcpClient()}
	 * @param trust  the store the server's chain is verified against
	 * @param peer   the identity the server's certificate must carry
	 * @return the engine, with its peer expected and ready to bind
	 * @throws CryptoBootstrapException ({@code EX-NET-2002}) for {@code Protocol.QUIC}, for a
	 *         configuration carrying a certificate or key, for a {@code minimumTlsVersion} other
	 *         than {@code TLSv1.2} or {@code TLSv1.3}, or if the native context cannot be created
	 * @throws IllegalStateException if {@code trust} has been closed
	 * @throws eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException ({@code EX-NET-2001})
	 *         if OpenSSL refuses {@code peer}
	 * @since 0.12
	 */
	@SuppressWarnings("PMD.AvoidCatchingGenericException") // the engine is closed on any failure, then rethrown
	public CommunityTlsEngine createClientTlsEngine(
			CryptoProviderConfig config, CommunityTlsClientTrust trust, TlsPeerIdentity peer) {
		Objects.requireNonNull(trust, "trust must not be null");
		Objects.requireNonNull(peer, "peer must not be null");
		CryptoProviderConfig safeConfig = config != null ? config : CryptoProviderConfig.tcpClient();
		requireTcp(safeConfig);
		if (safeConfig.certChainPath() != null || safeConfig.privateKeyPath() != null) {
			throw new CryptoBootstrapException(PROVIDER_NAME,
					"a client engine presents no certificate or key of its own");
		}
		CommunityTlsEngine engine = buildEngine(safeConfig, false, trust, false);
		try {
			peerBinder.bind(engine.delegate(), peer);
		} catch (RuntimeException | Error failure) {
			engine.close();
			throw failure;
		}
		return engine;
	}

	/**
	 * Opens the store client engines verify against: {@code trustFile} alone when it is given
	 * (the defaults are not added), OpenSSL's default locations otherwise.
	 *
	 * @param trustFile a PEM file of trusted certificates, or {@code null} for the defaults
	 * @return the trust; the caller closes it
	 * @throws CryptoBootstrapException ({@code EX-NET-2002}) if {@code trustFile} is not a readable
	 *         regular file, holds no certificate OpenSSL can load, or the store cannot be created
	 * @since 0.12
	 */
	public CommunityTlsClientTrust openClientTrust(Path trustFile) {
		return CommunityTlsClientTrustLoader.open(runtime.handles(), trustFile, PROVIDER_NAME);
	}

	/**
	 * Returns {@code false}; the Community tier does not support QUIC.
	 */
	@Override
	public boolean supportsQuic() {
		return false;
	}

	/**
	 * Returns this provider's display name, {@code "ExerisCommunity/OpenSSL3-TCP"}.
	 */
	@Override
	public String providerName() {
		return PROVIDER_NAME;
	}

	/**
	 * Returns {@code 0}, this provider's fixed selection priority.
	 */
	@Override
	public int priority() {
		return 0;
	}

	/**
	 * No-op; this provider holds no per-instance resources beyond the runtime handles shared via
	 * {@code Arena.global()}.
	 */
	@Override
	public void close() {
		// Provider keeps only shared runtime handles bound to Arena.global().
	}

	/**
	 * The OpenSSL protocol version that {@code minimumTlsVersion} names. Only the versions this
	 * provider serves are accepted, and a name that matches none is refused rather than mapped to a
	 * default.
	 */
	/* default */ static int resolveMinimumProtocol(String minimumTlsVersion) {
		return switch (minimumTlsVersion) {
			case "TLSv1.2" -> CoreOpenSslLoader.TLS1_2_VERSION;
			case "TLSv1.3" -> CoreOpenSslLoader.TLS1_3_VERSION;
			default -> throw new CryptoBootstrapException(PROVIDER_NAME,
					"Unsupported minimumTlsVersion: expected TLSv1.2 or TLSv1.3",
					minimumTlsVersion);
		};
	}

	private static void requireTcp(CryptoProviderConfig config) {
		if (config.protocol() == CryptoProviderConfig.Protocol.QUIC) {
			throw new CryptoBootstrapException(PROVIDER_NAME,
					"Community does not support QUIC. Upgrade to Enterprise tier.");
		}
	}

	/**
	 * Builds the context and the engine that owns it. On any failure before the engine exists, the
	 * context, the delegate and an allocator created here are released.
	 */
	@SuppressWarnings({
		"PMD.CloseResource",      // allocator/delegate ownership is transferred into CommunityTlsEngine
		"PMD.UseTryWithResources", // lifecycle uses explicit ownership transfer across success/failure paths
		"java:S2093"             // false positive: no heap resources are used, so no risk of GC finalizer delay
	})
	private CommunityTlsEngine buildEngine(CryptoProviderConfig config, boolean serverMode,
			CommunityTlsClientTrust trust, boolean noPeerIdentity) {
		int minimumProtocol = resolveMinimumProtocol(config.minimumTlsVersion());
		long startedAt = System.nanoTime();
		boolean ownsAllocator = !KernelProviders.MEMORY_ALLOCATOR.isBound();
		MemoryAllocator allocator = ownsAllocator
			? new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults())
			: KernelProviders.MEMORY_ALLOCATOR.get();

		long sslCtxPtr = NULL_PTR;
		OffHeapTlsEngine delegate = null;
		CommunityTlsEngine engine = null;
		try {
			sslCtxPtr = createSslCtx(config, allocator, serverMode, minimumProtocol);
			if (trust != null) {
				installTrust(sslCtxPtr, trust);
			}
			delegate = new OffHeapTlsEngine(runtime.handles(), sslCtxPtr, serverMode, allocator);

			CommunityProviderBootstrapEvent.emit(PROVIDER_NAME, System.nanoTime() - startedAt);

			engine = new CommunityTlsEngine(
					delegate,
					sslSetFd,
					runtime.handles().ctx(),
					sslCtxPtr,
					ownsAllocator ? allocator : null,
					config.jfrEnabled(),
					noPeerIdentity);
			return engine;
		} finally {
			if (engine == null) {
				if (delegate != null) {
					delegate.close();
				}
				if (sslCtxPtr != NULL_PTR) {
					runtime.handles().ctx().invokeCtxFree(sslCtxPtr);
				}
				if (ownsAllocator) {
					allocator.close();
				}
			}
		}
	}

	/**
	 * Hands {@code trust}'s store to the context under a lease, so a concurrent
	 * {@link CommunityTlsClientTrust#close()} cannot free it mid-call; the context keeps its own
	 * reference afterwards.
	 */
	private void installTrust(long sslCtxPtr, CommunityTlsClientTrust trust) {
		long store = trust.retainStore();
		try {
			runtime.handles().peerVerification().invokeCtxSet1CertStore(sslCtxPtr, store);
		} finally {
			trust.release();
		}
	}

	// explicit cert/key cleanup keeps native failure path deterministic
	private long createSslCtx(
			CryptoProviderConfig config,
			MemoryAllocator allocator,
			boolean serverMode,
			int minimumProtocol) {
		CoreSslHandles.CtxHandles ctx = runtime.handles().ctx();
		long methodPtr = serverMode ? ctx.invokeServerMethod() : ctx.invokeClientMethod();
		long sslCtxPtr = ctx.invokeCtxNew(methodPtr);
		if (sslCtxPtr == NULL_PTR) {
			clearErrorQueue();
			throw new CryptoBootstrapException(PROVIDER_NAME, "SSL_CTX_new returned NULL");
		}
		boolean contextReady = false;
		try {
			if (ctx.invokeCtxSetMinProtoVersion(sslCtxPtr, minimumProtocol) != SSL_SUCCESS) {
				throw new CryptoBootstrapException(PROVIDER_NAME,
						"SSL_CTX_set_min_proto_version refused " + config.minimumTlsVersion());
			}
			// A client verifies its server; the Community server asks no client certificate.
			int verifyMode = serverMode
					? CoreOpenSslLoader.SSL_VERIFY_NONE
					: CoreOpenSslLoader.SSL_VERIFY_PEER;
			ctx.invokeCtxSetVerify(sslCtxPtr, verifyMode);

			if (serverMode) {
				loadServerMaterial(ctx, sslCtxPtr, config, allocator);
			}
			contextReady = true;
			return sslCtxPtr;
		} finally {
			if (!contextReady) {
				// A failed load leaves entries on this thread's OpenSSL error queue, and the next
				// SSL_get_error this thread asks would read them as a fatal error of its own.
				clearErrorQueue();
				ctx.invokeCtxFree(sslCtxPtr);
			}
		}
	}

	private static void loadServerMaterial(CoreSslHandles.CtxHandles ctx, long sslCtxPtr,
			CryptoProviderConfig config, MemoryAllocator allocator) {
		int certResult;
		try (LoanedBuffer cert = toCString(config.certChainPath(), allocator)) {
			certResult = ctx.invokeCtxUseCertFile(
					sslCtxPtr,
					cert.segment().address(),
					CoreOpenSslLoader.SSL_FILETYPE_PEM);
		}
		if (certResult != SSL_SUCCESS) {
			throw new CryptoBootstrapException(PROVIDER_NAME,
					"SSL_CTX_use_certificate_file failed", certResult);
		}

		int keyResult;
		try (LoanedBuffer key = toCString(config.privateKeyPath(), allocator)) {
			keyResult = ctx.invokeCtxUseKeyFile(
					sslCtxPtr,
					key.segment().address(),
					CoreOpenSslLoader.SSL_FILETYPE_PEM);
		}
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

	private void clearErrorQueue() {
		runtime.handles().errorQueue().invokeClearError();
	}

	/**
	 * {@code path} as a NUL-terminated UTF-8 string in an infrastructure buffer the caller closes.
	 */
	@SuppressWarnings("PMD.AvoidCatchingGenericException") // the buffer is released on any failure, then rethrown
	/* default */ static LoanedBuffer toCString(Path path, MemoryAllocator allocator) {
		Objects.requireNonNull(path, "path must not be null");
		byte[] utf8 = path.toString().getBytes(StandardCharsets.UTF_8);
		LoanedBuffer buffer = allocator.allocateInfrastructure(utf8.length + 1L);
		try {
			MemorySegment.copy(MemorySegment.ofArray(utf8), 0L, buffer.segment(), 0L, utf8.length);
			buffer.segment().set(ValueLayout.JAVA_BYTE, utf8.length, (byte) 0);
			return buffer;
		} catch (RuntimeException failure) {
			buffer.close();
			throw failure;
		}
	}

	/**
	 * Applies the expected peer to a client engine that is built and not yet bound.
	 */
	// A test seam, implemented by a method reference in production.
	/* default */ @FunctionalInterface
	interface ClientPeerBinder {

		/**
		 * Makes {@code peer} the identity {@code engine}'s server must present.
		 *
		 * @param engine the engine
		 * @param peer   the identity its server must present
		 */
		void bind(OffHeapTlsEngine engine, TlsPeerIdentity peer);
	}
}
