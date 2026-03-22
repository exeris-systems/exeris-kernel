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
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsDecryptException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.perf.AbstractExerisBenchmark;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for Community TLS wrapper guards before explicit FD binding.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class CommunityTlsEngineGuardBenchmark extends AbstractExerisBenchmark {

	private CommunityKernelCryptoProvider provider;
	private TlsEngine engine;
	private CommunityTlsEngine serverEngine;
	private CommunityTlsEngine clientEngine;
	private SocketChannel clientChannel;
	private SocketChannel serverAcceptedChannel;
	private ServerSocketChannel serverListenChannel;
	private MemoryAllocator allocator;
	private LoanedBuffer plaintext;
	private LoanedBuffer ciphertext;
	private LoanedBuffer serverPlaintext;
	private LoanedBuffer serverCiphertext;
	private LoanedBuffer clientDecrypted;
	private LoanedBuffer serverHandshakeOutbound;
	private LoanedBuffer clientHandshakeOutbound;

	private static final byte[] ROUNDTRIP_PAYLOAD =
			"exeris-community-tls-roundtrip-payload".getBytes();
	private static final int HANDSHAKE_MAX_STEPS = 64;
	private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);

	@Setup(Level.Trial)
	public void setUpTrial() {
		try {
			provider = new CommunityKernelCryptoProvider();
		} catch (CryptoBootstrapException exception) {
			throw new IllegalStateException(
					"OpenSSL 3.x not available - CommunityTlsEngineGuardBenchmark cannot run",
					exception);
		}
		engine = provider.createTlsEngine(CryptoProviderConfig.tcpClient());
		allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
		plaintext = allocator.allocate(AllocationHint.MEDIUM);
		ciphertext = allocator.allocate(AllocationHint.MEDIUM);

		Path cwd = Path.of("").toAbsolutePath().normalize();
		Path moduleDir = "exeris-kernel-community".equals(String.valueOf(cwd.getFileName()))
				? cwd
				: cwd.resolve("exeris-kernel-community").normalize();
		Path certPath = moduleDir.resolve("../native-libs/certs/server.crt").normalize();
		Path keyPath = moduleDir.resolve("../native-libs/certs/server.key").normalize();

		serverEngine = (CommunityTlsEngine) provider.createTlsEngine(
				CryptoProviderConfig.httpsServer(certPath, keyPath));
		clientEngine = (CommunityTlsEngine) provider.createTlsEngine(CryptoProviderConfig.tcpClient());

		try {
			serverListenChannel = ServerSocketChannel.open();
			serverListenChannel.configureBlocking(true);
			serverListenChannel.bind(new InetSocketAddress("127.0.0.1", 0));
			int port = ((InetSocketAddress) serverListenChannel.getLocalAddress()).getPort();

			clientChannel = SocketChannel.open();
			clientChannel.configureBlocking(true);
			clientChannel.connect(new InetSocketAddress("127.0.0.1", port));
			serverAcceptedChannel = serverListenChannel.accept();

			int serverFd = SocketChannelFdAccess.requireFd(serverAcceptedChannel);
			int clientFd = SocketChannelFdAccess.requireFd(clientChannel);
			serverEngine.bindFileDescriptor(serverFd);
			clientEngine.bindFileDescriptor(clientFd);
		} catch (java.io.IOException ioException) {
			throw new IllegalStateException("Failed to create loopback channels for roundtrip benchmark", ioException);
		}

		serverHandshakeOutbound = allocator.allocate(AllocationHint.MEDIUM);
		clientHandshakeOutbound = allocator.allocate(AllocationHint.MEDIUM);
		driveHandshakeToActive();

		serverPlaintext = allocator.allocate(AllocationHint.MEDIUM);
		serverCiphertext = allocator.allocate(AllocationHint.MEDIUM);
		clientDecrypted = allocator.allocate(AllocationHint.MEDIUM);
		serverPlaintext.segment().asSlice(0, ROUNDTRIP_PAYLOAD.length)
				.copyFrom(java.lang.foreign.MemorySegment.ofArray(ROUNDTRIP_PAYLOAD));
		serverPlaintext.setSize(ROUNDTRIP_PAYLOAD.length);
	}

	@TearDown(Level.Trial)
	public void tearDownTrial() {
		if (serverEngine != null) {
			serverEngine.close();
		}
		if (clientEngine != null) {
			clientEngine.close();
		}
		closeQuietly(serverAcceptedChannel);
		closeQuietly(clientChannel);
		closeQuietly(serverListenChannel);
		if (serverPlaintext != null) {
			serverPlaintext.close();
		}
		if (serverCiphertext != null) {
			serverCiphertext.close();
		}
		if (clientDecrypted != null) {
			clientDecrypted.close();
		}
		if (serverHandshakeOutbound != null) {
			serverHandshakeOutbound.close();
		}
		if (clientHandshakeOutbound != null) {
			clientHandshakeOutbound.close();
		}
		if (engine != null) {
			engine.close();
		}
		if (plaintext != null) {
			plaintext.close();
		}
		if (ciphertext != null) {
			ciphertext.close();
		}
		if (allocator != null) {
			allocator.close();
		}
		if (provider != null) {
			provider.close();
		}
	}

	@Benchmark
	public void beginHandshakeWithoutBindGuardCost(Blackhole blackhole) {
		try {
			engine.beginHandshake(ciphertext);
		} catch (TlsHandshakeException expected) {
			blackhole.consume(expected);
		}
	}

	@Benchmark
	public void wrapWithoutBindGuardCost(Blackhole blackhole) {
		try {
			engine.wrap(plaintext, ciphertext);
		} catch (TlsHandshakeException expected) {
			blackhole.consume(expected);
		}
	}

	@Benchmark
	public void unwrapWithoutBindGuardCost(Blackhole blackhole) {
		try {
			engine.unwrap(ciphertext, plaintext);
		} catch (TlsDecryptException expected) {
			blackhole.consume(expected);
		}
	}

	@Benchmark
	public void notifyBoundWithoutBindGuardCost(Blackhole blackhole) {
		try {
			engine.notifyBound();
		} catch (TlsHandshakeException expected) {
			blackhole.consume(expected);
		}
	}

	@Benchmark
	public void fullRoundTripWrapUnwrapCost(Blackhole blackhole) {
		serverCiphertext.setSize(0);
		clientDecrypted.setSize(0);
		eu.exeris.kernel.spi.crypto.TlsStatus wrapStatus =
				serverEngine.wrap(serverPlaintext, serverCiphertext);
		eu.exeris.kernel.spi.crypto.TlsStatus unwrapStatus =
				clientEngine.unwrap(serverCiphertext, clientDecrypted);
		if (wrapStatus != eu.exeris.kernel.spi.crypto.TlsStatus.OK
				&& wrapStatus != eu.exeris.kernel.spi.crypto.TlsStatus.FINISHED) {
			throw new IllegalStateException("Round-trip wrap did not produce payload: " + wrapStatus);
		}
		if (unwrapStatus != eu.exeris.kernel.spi.crypto.TlsStatus.OK
				&& unwrapStatus != eu.exeris.kernel.spi.crypto.TlsStatus.FINISHED) {
			throw new IllegalStateException("Round-trip unwrap did not produce plaintext: " + unwrapStatus);
		}
		blackhole.consume(wrapStatus);
		blackhole.consume(unwrapStatus);
		blackhole.consume(clientDecrypted.size());
	}

	private void driveHandshakeToActive() {
		Instant deadline = Instant.now().plus(HANDSHAKE_TIMEOUT);
		try (var scope = StructuredTaskScope.open(
				StructuredTaskScope.Joiner.awaitAll(),
				config -> config
						.withThreadFactory(Thread.ofPlatform().daemon(true).factory())
						.withTimeout(HANDSHAKE_TIMEOUT))) {
			var serverTask = scope.fork(() -> {
				driveSingleHandshakeEngine(serverEngine, serverHandshakeOutbound, deadline);
				return null;
			});
			var clientTask = scope.fork(() -> {
				driveSingleHandshakeEngine(clientEngine, clientHandshakeOutbound, deadline);
				return null;
			});
			scope.join();

			if (Instant.now().isAfter(deadline) || scope.isCancelled()) {
				throw new IllegalStateException("TLS handshake timed out in benchmark setup");
			}
			if (serverTask.state() == StructuredTaskScope.Subtask.State.FAILED) {
				throw new IllegalStateException("Server handshake failed", serverTask.exception());
			}
			if (clientTask.state() == StructuredTaskScope.Subtask.State.FAILED) {
				throw new IllegalStateException("Client handshake failed", clientTask.exception());
			}
		} catch (InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Benchmark handshake interrupted", interruptedException);
		}

		if (!serverEngine.isHandshakeComplete() || !clientEngine.isHandshakeComplete()) {
			throw new IllegalStateException("Handshake did not reach ACTIVE state for both peers");
		}
	}

	private static void driveSingleHandshakeEngine(
			CommunityTlsEngine tlsEngine,
			LoanedBuffer outbound,
			Instant deadline) {
		for (int i = 0; i < HANDSHAKE_MAX_STEPS && !tlsEngine.isHandshakeComplete(); i++) {
			tlsEngine.beginHandshake(outbound);
			if (Instant.now().isAfter(deadline)) {
				throw new IllegalStateException("TLS handshake deadline exceeded");
			}
		}
		if (!tlsEngine.isHandshakeComplete()) {
			throw new IllegalStateException("TLS handshake did not complete within max steps");
		}
	}

	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch (Exception ignored) {
			// benchmark teardown should be best-effort
		}
	}
}
