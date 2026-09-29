/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.ArenaLoanedBuffer;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoaderTestHelper;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslRuntime;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.spi.crypto.TlsPhase;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OffHeapTlsEngine#expectPeer} against real OpenSSL: which certificates a client accepts,
 * which it refuses with which {@code X509_V_*} code, and what server name it sends.
 *
 * <p>Every certificate is minted per class by {@link TlsTestPki}; no leaf's common name equals the
 * identity a case expects, except the case that exists to prove the common name is never read.
 * Each case drives both sides of a blocking loopback pair on its own thread; the client's thread
 * also reads {@code ERR_peek_error} after its handshake. Fails, rather than skips, when OpenSSL
 * cannot be loaded.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("IT: OffHeapTlsEngine — client verification of the server against the expected peer")
class OffHeapTlsEnginePeerVerificationIT {

    private static final long X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT = 18L;
    private static final long X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY = 20L;
    private static final long X509_V_ERR_HOSTNAME_MISMATCH = 62L;
    private static final long X509_V_ERR_IP_ADDRESS_MISMATCH = 64L;

    private static final Arena ARENA = Arena.ofShared(); //NOPMD DirectArena — test harness only

    private static final MemoryAllocator ALLOC = new MemoryAllocator() {
        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            return ArenaLoanedBuffer.allocate(ARENA, hint, 16_384);
        }

        @Override public LoanedBuffer allocateNetwork(int bytes)         { return allocate(AllocationHint.MEDIUM); }
        @Override public LoanedBuffer allocateCarrierSlab(int index)     { return allocate(AllocationHint.MEDIUM); }
        @Override public LoanedBuffer allocateInfrastructure(long bytes) { return allocate(AllocationHint.MEDIUM); }
        @Override public MemoryStats stats()                             { return MemoryStats.zero(); }
        @Override public void close()                                    { /* arena lives for the class */ }
    };

    @TempDir
    static Path pki;

    private static CoreSslHandles handles;
    private static MethodHandle sslSetFd;
    private static MethodHandle errPeekError;
    private static MethodHandle sslGetServername;
    private static TlsTestPki.Issued root;
    private static TlsTestPki.Issued otherRoot;

    @BeforeAll
    static void loadOpenSsl() {
        CoreOpenSslRuntime runtime = CoreOpenSslLoader.load(Arena.global());
        handles = runtime.handles();
        sslSetFd = CoreOpenSslLoaderTestHelper.resolveSslSetFd(runtime);
        assertThat(sslSetFd).as("SSL_set_fd must resolve from the loaded libssl").isNotNull();
        errPeekError = runtime.requiredCryptoHandle("ERR_peek_error", FunctionDescriptor.of(JAVA_LONG));
        sslGetServername = runtime.requiredSslHandle("SSL_get_servername",
                FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT));
        root = TlsTestPki.root(pki, "trusted-root");
        otherRoot = TlsTestPki.root(pki, "other-root");
    }

    // ------------------------------------------------------------------ chain

    @Test
    @DisplayName("a self-signed server the client does not trust is refused with 18, and the client's queue is empty")
    void untrustedSelfSignedIsRefused() throws Exception {
        TlsTestPki.Issued leaf = TlsTestPki.leaf(pki, "self-signed", null, "exeris-core-leaf-1",
                TlsTestPki.dns("localhost"));

        Outcome outcome = handshake(leaf, root, CoreOpenSslLoader.SSL_VERIFY_PEER,
                TlsPeerIdentity.of("localhost"));

        assertThat(outcome.clientStatus()).isEqualTo(TlsStatus.CLOSED);
        assertThat(outcome.verifyResult()).isEqualTo(X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT);
        assertThat(outcome.sslError()).isEqualTo(CoreOpenSslLoader.SSL_ERROR_SSL);
        assertThat(outcome.clientPhase()).isEqualTo(TlsPhase.ERROR);
        assertThat(outcome.clientQueueAfter()).as("ERR_peek_error on the client's thread").isZero();
    }

    @Test
    @DisplayName("a server issued by a root the client does not trust is refused with 20")
    void untrustedIssuerIsRefused() throws Exception {
        TlsTestPki.Issued leaf = TlsTestPki.leaf(pki, "other-issued", otherRoot, "exeris-core-leaf-2",
                TlsTestPki.dns("localhost"));

        Outcome outcome = handshake(leaf, root, CoreOpenSslLoader.SSL_VERIFY_PEER,
                TlsPeerIdentity.of("localhost"));

        assertThat(outcome.clientStatus()).isEqualTo(TlsStatus.CLOSED);
        assertThat(outcome.verifyResult()).isEqualTo(X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY);
    }

    @Test
    @DisplayName("a trusted server for the expected name completes, and leaves the client's queue empty")
    void trustedNameCompletes() throws Exception {
        TlsTestPki.Issued leaf = TlsTestPki.leaf(pki, "localhost", root, "exeris-core-leaf-3",
                TlsTestPki.dns("localhost"));

        Outcome outcome = handshake(leaf, root, CoreOpenSslLoader.SSL_VERIFY_PEER,
                TlsPeerIdentity.of("localhost"));

        assertThat(outcome.clientStatus()).isEqualTo(TlsStatus.FINISHED);
        assertThat(outcome.clientPhase()).isEqualTo(TlsPhase.ACTIVE);
        assertThat(outcome.verifyResult()).isZero();
        assertThat(outcome.clientQueueAfter()).isZero();
    }

    @Test
    @DisplayName("on a VERIFY_NONE context the engine still refuses a server that failed verification")
    void verifyNoneContextStillRefuses() throws Exception {
        TlsTestPki.Issued leaf = TlsTestPki.leaf(pki, "self-signed-2", null, "exeris-core-leaf-4",
                TlsTestPki.dns("localhost"));

        Outcome outcome = handshake(leaf, root, CoreOpenSslLoader.SSL_VERIFY_NONE,
                TlsPeerIdentity.of("localhost"));

        assertThat(outcome.clientStatus()).isEqualTo(TlsStatus.CLOSED);
        assertThat(outcome.clientPhase()).isEqualTo(TlsPhase.ERROR);
        assertThat(outcome.verifyResult()).isEqualTo(X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT);
        assertThat(outcome.clientQueueAfter()).isZero();
    }

    // ------------------------------------------------------------------ names

    @Test
    @DisplayName("a partial wildcard does not match")
    void partialWildcardDoesNotMatch() throws Exception {
        TlsTestPki.Issued leaf = TlsTestPki.leaf(pki, "partial-wildcard", root, "exeris-core-leaf-5",
                TlsTestPki.dns("f*.example.test"));

        Outcome outcome = handshake(leaf, root, CoreOpenSslLoader.SSL_VERIFY_PEER,
                TlsPeerIdentity.of("foo.example.test"));

        assertThat(outcome.verifyResult()).isEqualTo(X509_V_ERR_HOSTNAME_MISMATCH);
    }

    @Test
    @DisplayName("a full-label wildcard matches")
    void fullWildcardMatches() throws Exception {
        TlsTestPki.Issued leaf = TlsTestPki.leaf(pki, "wildcard", root, "exeris-core-leaf-6",
                TlsTestPki.dns("*.example.test"));

        Outcome outcome = handshake(leaf, root, CoreOpenSslLoader.SSL_VERIFY_PEER,
                TlsPeerIdentity.of("foo.example.test"));

        assertThat(outcome.clientStatus()).isEqualTo(TlsStatus.FINISHED);
    }

    @Test
    @DisplayName("a certificate for another name is refused with 62")
    void otherNameIsRefused() throws Exception {
        TlsTestPki.Issued leaf = TlsTestPki.leaf(pki, "other-name", root, "exeris-core-leaf-7",
                TlsTestPki.dns("other.invalid"));

        Outcome outcome = handshake(leaf, root, CoreOpenSslLoader.SSL_VERIFY_PEER,
                TlsPeerIdentity.of("localhost"));

        assertThat(outcome.verifyResult()).isEqualTo(X509_V_ERR_HOSTNAME_MISMATCH);
    }

    @Test
    @DisplayName("a common name is never consulted: CN=localhost with no SAN is refused with 62")
    void commonNameIsNeverConsulted() throws Exception {
        TlsTestPki.Issued leaf = TlsTestPki.leaf(pki, "cn-only", root, "localhost");

        Outcome outcome = handshake(leaf, root, CoreOpenSslLoader.SSL_VERIFY_PEER,
                TlsPeerIdentity.of("localhost"));

        assertThat(outcome.verifyResult()).isEqualTo(X509_V_ERR_HOSTNAME_MISMATCH);
    }

    // ------------------------------------------------------------------ addresses

    @Test
    @DisplayName("an IP entry matches an address identity")
    void ipEntryMatchesAddress() throws Exception {
        TlsTestPki.Issued leaf = TlsTestPki.leaf(pki, "ip", root, "exeris-core-leaf-8",
                TlsTestPki.ip("127.0.0.1"));

        Outcome outcome = handshake(leaf, root, CoreOpenSslLoader.SSL_VERIFY_PEER,
                TlsPeerIdentity.of("127.0.0.1"));

        assertThat(outcome.clientStatus()).isEqualTo(TlsStatus.FINISHED);
    }

    @Test
    @DisplayName("a DNS entry spelling the address does not match an address identity: 64")
    void dnsEntryDoesNotMatchAddress() throws Exception {
        TlsTestPki.Issued leaf = TlsTestPki.leaf(pki, "dns-spelled-ip", root, "exeris-core-leaf-9",
                TlsTestPki.dns("127.0.0.1"));

        Outcome outcome = handshake(leaf, root, CoreOpenSslLoader.SSL_VERIFY_PEER,
                TlsPeerIdentity.of("127.0.0.1"));

        assertThat(outcome.verifyResult()).isEqualTo(X509_V_ERR_IP_ADDRESS_MISMATCH);
    }

    @Test
    @DisplayName("an IP entry does not match a DNS identity spelling the address: 62")
    void ipEntryDoesNotMatchDnsName() throws Exception {
        TlsTestPki.Issued leaf = TlsTestPki.leaf(pki, "ip-2", root, "exeris-core-leaf-10",
                TlsTestPki.ip("127.0.0.1"));

        Outcome outcome = handshake(leaf, root, CoreOpenSslLoader.SSL_VERIFY_PEER,
                new TlsPeerIdentity.DnsName("127.0.0.1"));

        assertThat(outcome.verifyResult()).isEqualTo(X509_V_ERR_HOSTNAME_MISMATCH);
    }

    // ------------------------------------------------------------------ server name indication

    @Test
    @DisplayName("the server sees the expected DNS name as SNI")
    void dnsIdentityIsSentAsSni() throws Exception {
        TlsTestPki.Issued leaf = TlsTestPki.leaf(pki, "sni", root, "exeris-core-leaf-11",
                TlsTestPki.dns("localhost"));

        Outcome outcome = handshake(leaf, root, CoreOpenSslLoader.SSL_VERIFY_PEER,
                TlsPeerIdentity.of("LOCALHOST"));

        assertThat(outcome.clientStatus()).isEqualTo(TlsStatus.FINISHED);
        assertThat(outcome.serverName()).isEqualTo("localhost");
    }

    @Test
    @DisplayName("the server sees no SNI for an address identity")
    void addressIdentitySendsNoSni() throws Exception {
        TlsTestPki.Issued leaf = TlsTestPki.leaf(pki, "sni-ip", root, "exeris-core-leaf-12",
                TlsTestPki.ip("127.0.0.1"));

        Outcome outcome = handshake(leaf, root, CoreOpenSslLoader.SSL_VERIFY_PEER,
                TlsPeerIdentity.of("127.0.0.1"));

        assertThat(outcome.clientStatus()).as("the handshake completed, so the server read a ClientHello")
                .isEqualTo(TlsStatus.FINISHED);
        assertThat(outcome.serverName()).isNull();
    }

    // ------------------------------------------------------------------ guards

    @Test
    @DisplayName("expectPeer is refused twice, on a server, and after bind")
    void expectPeerGuards() {
        long clientCtx = TlsTestSslContexts.client(handles, CoreOpenSslLoader.SSL_VERIFY_PEER);
        long serverCtx = TlsTestSslContexts.client(handles, CoreOpenSslLoader.SSL_VERIFY_NONE);
        try (OffHeapTlsEngine twice = new OffHeapTlsEngine(handles, clientCtx, false, ALLOC);
             OffHeapTlsEngine server = new OffHeapTlsEngine(handles, serverCtx, true, ALLOC);
             OffHeapTlsEngine bound = new OffHeapTlsEngine(handles, clientCtx, false, ALLOC)) {
            twice.expectPeer(TlsPeerIdentity.of("localhost"));
            assertThatThrownBy(() -> twice.expectPeer(TlsPeerIdentity.of("localhost")))
                    .isInstanceOf(TlsHandshakeException.class);
            assertThatThrownBy(() -> server.expectPeer(TlsPeerIdentity.of("localhost")))
                    .isInstanceOf(TlsHandshakeException.class);
            bound.notifyBound();
            assertThatThrownBy(() -> bound.expectPeer(TlsPeerIdentity.of("localhost")))
                    .isInstanceOf(TlsHandshakeException.class);
        } finally {
            handles.ctx().invokeCtxFree(clientCtx);
            handles.ctx().invokeCtxFree(serverCtx);
        }
    }

    // ------------------------------------------------------------------ harness

    /**
     * What one handshake produced.
     *
     * @param clientStatus     the client's final status
     * @param clientPhase      the client's phase afterwards
     * @param sslError         {@link OffHeapTlsEngine#handshakeFailureSslError()}
     * @param verifyResult     {@link OffHeapTlsEngine#peerVerificationResult()}
     * @param clientQueueAfter {@code ERR_peek_error} on the client's thread after its handshake
     * @param serverName       what {@code SSL_get_servername} reports on the server, or {@code null}
     */
    record Outcome(TlsStatus clientStatus, TlsPhase clientPhase, int sslError, long verifyResult,
                   long clientQueueAfter, String serverName) {
    }

    private static Outcome handshake(TlsTestPki.Issued serverMaterial, TlsTestPki.Issued trusted,
                                     int clientVerifyMode, TlsPeerIdentity expected) throws Exception {
        long serverCtx = TlsTestSslContexts.server(handles, serverMaterial);
        long clientCtx = TlsTestSslContexts.client(handles, clientVerifyMode);
        try {
            trust(clientCtx, trusted);
            try (OffHeapTlsEngineErrorQueueIT.SocketPair pair = OffHeapTlsEngineErrorQueueIT.SocketPair.open();
                 OffHeapTlsEngine server = new OffHeapTlsEngine(handles, serverCtx, true, ALLOC);
                 OffHeapTlsEngine client = new OffHeapTlsEngine(handles, clientCtx, false, ALLOC)) {
                client.expectPeer(expected);
                bind(server, pair.serverFd());
                bind(client, pair.clientFd());

                CompletableFuture<TlsStatus> serverDone = CompletableFuture.supplyAsync(
                        () -> drive(server), runnable -> Thread.ofPlatform().start(runnable));
                long[] queue = new long[1];
                CompletableFuture<TlsStatus> clientDone = CompletableFuture.supplyAsync(() -> {
                    handles.errorQueue().invokeClearError();
                    TlsStatus status = drive(client);
                    queue[0] = peekError();
                    return status;
                }, runnable -> Thread.ofPlatform().start(runnable));

                TlsStatus clientStatus = clientDone.get(20, TimeUnit.SECONDS);
                if (clientStatus != TlsStatus.FINISHED) {
                    pair.client().close();
                }
                serverDone.get(20, TimeUnit.SECONDS);
                return new Outcome(clientStatus, client.phase(), client.handshakeFailureSslError(),
                        client.peerVerificationResult(), queue[0], serverName(server));
            }
        } finally {
            handles.ctx().invokeCtxFree(serverCtx);
            handles.ctx().invokeCtxFree(clientCtx);
        }
    }

    private static TlsStatus drive(OffHeapTlsEngine engine) {
        try (LoanedBuffer out = ALLOC.allocate(AllocationHint.MEDIUM)) {
            TlsStatus status = engine.beginHandshake(out);
            for (int step = 0; step < 64 && status != TlsStatus.FINISHED && status != TlsStatus.CLOSED; step++) {
                status = engine.beginHandshake(out);
            }
            return status;
        }
    }

    /** Loads {@code anchor} into a fresh store the context takes a reference to, then drops ours. */
    private static void trust(long ctx, TlsTestPki.Issued anchor) {
        CoreSslHandles.TrustStoreHandles store = handles.trustStore();
        long storePtr = store.invokeStoreNew();
        assertThat(storePtr).isNotZero();
        try (Arena arena = Arena.ofConfined()) { //NOPMD DirectArena — one path string
            MemorySegment path = arena.allocateFrom(anchor.certificate().toString());
            assertThat(store.invokeStoreLoadFile(storePtr, path.address())).isEqualTo(1);
            handles.peerVerification().invokeCtxSet1CertStore(ctx, storePtr);
        } finally {
            store.invokeStoreFree(storePtr);
        }
    }

    private static void bind(OffHeapTlsEngine engine, int fd) {
        assertThat(engine.bindTransportFd(sslSetFd, fd)).isEqualTo(1);
        engine.notifyBound();
    }

    private static String serverName(OffHeapTlsEngine server) {
        try {
            long name = (long) sslGetServername.invokeExact(server.sslPointerForDiagnostics(),
                    CoreOpenSslLoader.TLSEXT_NAMETYPE_HOST_NAME);
            return name == 0L ? null : MemorySegment.ofAddress(name).reinterpret(256).getString(0L);
        } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
            throw new IllegalStateException("SSL_get_servername failed", t);
        }
    }

    private static long peekError() {
        try {
            return (long) errPeekError.invokeExact();
        } catch (Throwable t) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
            throw new IllegalStateException("ERR_peek_error failed", t);
        }
    }
}
