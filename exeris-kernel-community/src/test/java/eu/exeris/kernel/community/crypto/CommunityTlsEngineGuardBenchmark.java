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
import java.nio.file.Files;
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
	private Path tempCertDir;

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

		Path[] certAndKey = resolveCertPaths();
		Path certPath = certAndKey[0];
		Path keyPath = certAndKey[1];
		if (!Files.exists(certPath) || !Files.exists(keyPath)) {
			throw new IllegalStateException(
					"No usable TLS cert/key found for benchmark. cert=" + certPath + " key=" + keyPath);
		}

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
		if (tempCertDir != null) {
			try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(tempCertDir)) {
				for (Path entry : stream) {
					Files.deleteIfExists(entry);
				}
			} catch (java.io.IOException ignored) {
				// best-effort cleanup
			}
			try {
				Files.deleteIfExists(tempCertDir);
			} catch (java.io.IOException ignored) {
				// best-effort cleanup
			}
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

	/**
	 * Resolves the TLS cert and key to use for the benchmark server engine.
	 *
	 * <p>Fallback sequence (first match wins):
	 * <ol>
	 *   <li>System properties {@code benchmark.tls.cert.path} and {@code benchmark.tls.key.path}
	 *       when both point to existing files.</li>
	 *   <li>Temporary self-signed cert/key generated via {@code openssl} CLI if available.
	 *       The temp directory is stored in {@link #tempCertDir} for teardown cleanup.</li>
	 *   <li>Certificates bundled under {@code native-libs/certs/} discovered by walking
	 *       up the ancestor directories from the JVM working directory.</li>
	 * </ol>
	 *
	 * @return two-element array: {@code [certPath, keyPath]}
	 */
	private Path[] resolveCertPaths() {
		// 1) System-property override — takes precedence in CI/fork environments.
		String propCert = System.getProperty("benchmark.tls.cert.path", "");
		String propKey = System.getProperty("benchmark.tls.key.path", "");
		if (!propCert.isBlank() && !propKey.isBlank()) {
			Path certFile = Path.of(propCert);
			Path keyFile = Path.of(propKey);
			if (Files.exists(certFile) && Files.exists(keyFile)) {
				return new Path[]{certFile, keyFile};
			}
		}

		// 2) Generate a temporary self-signed cert via the openssl CLI if available.
		Path tmpDir = Files.createTempDirectory("exeris-bench-tls-");
		Path certFile = tmpDir.resolve("server.crt");
		Path keyFile = tmpDir.resolve("server.key");
		try {
			String opensslPath = findOnPath("openssl");
			if (opensslPath == null) {
				throw new java.io.IOException("openssl not found on PATH");
			}
			ProcessBuilder pb = new ProcessBuilder(
					opensslPath, "req", "-x509", "-newkey", "rsa:2048",
					"-keyout", keyFile.toString(),
					"-out", certFile.toString(),
					"-days", "1", "-nodes",
					"-subj", "/CN=exeris-benchmark");
			pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			pb.redirectError(ProcessBuilder.Redirect.DISCARD);
			Process proc = pb.start();
			int exit = proc.waitFor();
			if (exit == 0 && Files.exists(certFile) && Files.exists(keyFile)) {
				tempCertDir = tmpDir;
				return new Path[]{certFile, keyFile};
			}
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		} catch (java.io.IOException ignored) {
			// openssl not available or failed — clean up and fall through
		}
		try { Files.deleteIfExists(certFile); } catch (java.io.IOException ignored) {}
		try { Files.deleteIfExists(keyFile); } catch (java.io.IOException ignored) {}
		try { Files.deleteIfExists(tmpDir); } catch (java.io.IOException ignored) {}

		// 3) Locate certs bundled in the repository (native-libs/certs).
		Path certsDir = locateCertsDir();
		return new Path[]{certsDir.resolve("server.crt"), certsDir.resolve("server.key")};
	}

	/**
	 * Walks up from the JVM working directory until a directory containing
	 * {@code native-libs/certs/server.crt} is found.
	 */
	private static Path locateCertsDir() {
		Path dir = Path.of("").toAbsolutePath().normalize();
		while (dir != null) {
			Path candidate = dir.resolve("native-libs/certs/server.crt");
			if (Files.exists(candidate)) {
				return candidate.getParent();
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException(
				"native-libs/certs/server.crt not found in any ancestor of: "
						+ Path.of("").toAbsolutePath());
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

	private static String findOnPath(String executable) {
		String pathEnv = System.getenv("PATH");
		if (pathEnv == null) return null;
		for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
			java.io.File candidate = new java.io.File(dir, executable);
			if (candidate.isFile() && candidate.canExecute()) {
				return candidate.getAbsolutePath();
			}
		}
		return null;
	}
}
