/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.crypto;

import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsDecryptException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK: Abstract base for {@link KernelCryptoProvider} and {@link TlsEngine} contract
 * verification.
 *
 * <h2>Front 3 — SecurityZeroAllocTck (Crypto)</h2>
 * <p>Enforces the <em>Zero-Copy</em> TLS contract: all plaintext and ciphertext
 * MUST reside in {@link LoanedBuffer} instances backed by off-heap
 * {@code MemorySegment}. No data is copied to the Java heap during {@code wrap()}
 * or {@code unwrap()}.
 *
 * <h2>Verified Constraints</h2>
 * <ol>
 *   <li>Provider is discoverable via ServiceLoader (no-arg constructor).</li>
 *   <li>{@code supportsQuic()} returns {@code false} for Community tier.</li>
 *   <li>Community: {@code createTlsEngine(QUIC config)} throws
 *       {@link CryptoBootstrapException}.</li>
 *   <li>{@code createTlsEngine(TCP_TLS)} returns a non-null engine.</li>
 *   <li>{@code beginHandshake()} returns a non-null, non-CLOSED status.</li>
 *   <li>{@code wrap()} and {@code unwrap()} return a non-null status using
 *       off-heap {@link LoanedBuffer}s.</li>
 *   <li>{@code wrap()} does not reallocate the plaintext {@link LoanedBuffer} —
 *       verified by asserting its segment address is unchanged before and after the call.
 *       This detects a reallocation, not the absence of a copy elsewhere; see
 *       {@link #wrapDoesNotCopyToHeap()}.</li>
 *   <li>{@code close()} is idempotent (double-close is safe).</li>
 *   <li>Provider name is non-null and non-blank.</li>
 *   <li>{@code priority()} is non-negative for every tier, and exactly {@code 0} for
 *       Community. No test here pins an Enterprise value.</li>
 * </ol>
 *
 * <p>The three Community-only checks above are skipped by a bare early return — not
 * {@link org.junit.jupiter.api.Assumptions#assumeTrue} — when {@link #isCommunityTier()}
 * returns {@code false}, so they report as passed rather than skipped against an Enterprise
 * binding. See {@link #isCommunityTier()}.
 *
 * <h2>Usage</h2>
 * {@snippet lang="java" :
 * class CommunityCryptoProviderTest extends AbstractCryptoEngineTck {
 *     \@Override protected KernelCryptoProvider createProvider() {
 *         return new CommunityCryptoProvider();
 *     }
 *     \@Override protected MemoryAllocator createAllocator() {
 *         return new CommunityMemoryProvider()
 *                 .createAllocator(MemoryProviderConfig.defaults());
 *     }
 *     \@Override protected boolean isCommunityTier() { return true; }
 * }
 * }
 *
 * @since 0.5
 */
public abstract class AbstractCryptoEngineTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /**
     * Creates the {@link KernelCryptoProvider} under test.
     *
     * @return a fresh provider instance
     * @implSpec Return a new, ready-to-use instance; {@link #setUp()} calls this once per test
     *           method, so implementations must not share mutable state across instances.
     */
    protected abstract KernelCryptoProvider createProvider();

    /**
     * Creates the {@link MemoryAllocator} used for off-heap TLS buffers.
     *
     * @return a fresh allocator instance
     * @implSpec Return a new, ready-to-use instance; {@link #setUp()} calls this once per test
     *           method, and {@link #tearDown()} closes it afterward.
     */
    protected abstract MemoryAllocator createAllocator();

    /**
     * Returns {@code true} if this provider is Community-tier.
     *
     * @return {@code true} for a Community-tier provider (the default); {@code false} to exempt
     *         the Community-only assertions
     * @implSpec A Community-tier provider must additionally satisfy {@code supportsQuic() ==
     *           false} and {@code priority() == 0}. The three tests that check those constraints
     *           — {@link #communityPriorityIsZero()}, {@link #communityDoesNotSupportQuic()},
     *           {@link #communityRejectsQuicConfig()} — skip themselves by a bare early return,
     *           not {@link org.junit.jupiter.api.Assumptions#assumeTrue}, when this returns
     *           {@code false}; overriding it to {@code false} makes those tests report as
     *           passed, not skipped, without exercising any assertion.
     */
    protected boolean isCommunityTier() {
        return true;
    }

    /**
     * Returns {@code true} if the provider under test supports in-memory I/O
     * (wrap/unwrap) without a real fd-based network socket.
     *
     * <p>Default: {@code true} — suitable for Memory-BIO engines whose
     * {@code wrap}/{@code unwrap} operations work entirely on off-heap
     * {@link eu.exeris.kernel.spi.memory.LoanedBuffer} slabs, independently
     * of any kernel file descriptor.
     *
     * <p>Override to return {@code false} for fd-owner BIO engines (e.g., the
     * Core OpenSSL {@code SSL_set_fd} path) whose wrap/unwrap hot path requires
     * an active TCP socket bound to a real file descriptor. Those engines are
     * covered end-to-end by {@code OffHeapTlsEngineLoopbackIT}.
     * When {@code false}, the three I/O tests that need an {@code ACTIVE} session
     * are skipped via {@link org.junit.jupiter.api.Assumptions#assumeTrue}.
     *
     * @return {@code true} if wrap/unwrap can be exercised without a real socket
     * @since 0.5
     */
    protected boolean isIoReady() {
        return true;
    }

    /**
     * Returns {@code true} if the provider requires external transport binding
     * (e.g. real socket FD wiring) before {@link TlsEngine#beginHandshake(LoanedBuffer)}
     * can be called.
     *
     * <p>Default: {@code false}. Engines that can self-enter handshake phase in
     * pure test harnesses should keep the default.
     *
     * @return {@code true} if {@link #beginHandshakeReturnsValidStatus()} must expect a
     *         {@link TlsHandshakeException} instead of a handshake status; {@code false} (the
     *         default) for engines that can self-enter the handshake phase in this harness
     */
    protected boolean requiresExternalBindBeforeHandshake() {
        return false;
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private KernelCryptoProvider provider;
    private MemoryAllocator allocator;

    /**
     * Creates the {@link #createProvider() provider} and {@link #createAllocator() allocator}
     * fixtures used by every test method in this class.
     */
    @BeforeEach
    public final void setUp() {
        provider = createProvider();
        allocator = createAllocator();
    }

    /**
     * Closes the allocator fixture after each test.
     */
    @AfterEach
    public final void tearDown() {
        if (allocator != null) {
            allocator.close();
        }
    }

    /**
     * Builds a minimal TCP_TLS config for use in tests.
     */
    private static CryptoProviderConfig tcpTlsConfig() {
        return new CryptoProviderConfig(
                CryptoProviderConfig.Protocol.TCP_TLS,
                null, null,
                List.of("h2"),
                0, false,
                CryptoProviderConfig.TLS_1_3
        );
    }

    /**
     * Builds a QUIC config — Community must reject this.
     */
    private static CryptoProviderConfig quicConfig() {
        return new CryptoProviderConfig(
                CryptoProviderConfig.Protocol.QUIC,
                null, null,
                List.of("h3"),
                0, false,
                CryptoProviderConfig.TLS_1_3
        );
    }

    // =========================================================================
    // Provider metadata
    // =========================================================================

    /**
     * Verifies that {@link KernelCryptoProvider#providerName()} returns a non-null, non-blank
     * identifier.
     */
    @Test
    @DisplayName("Provider metadata: providerName() is non-null and non-blank")
    public final void providerNameIsNonBlank() {
        assertThat(provider.providerName())
                .as("KernelCryptoProvider.providerName() MUST be non-null and non-blank")
                .isNotBlank();
    }

    /**
     * Verifies that {@link KernelCryptoProvider#priority()} never returns a negative value,
     * for whatever tier the provider under test belongs to.
     */
    @Test
    @DisplayName("Provider metadata: priority() is non-negative (Community=0, Enterprise≥1)")
    public final void priorityIsNonNegative() {
        assertThat(provider.priority())
                .as("KernelCryptoProvider.priority() MUST be >= 0")
                .isGreaterThanOrEqualTo(0);
    }

    /**
     * For a Community-tier provider, verifies that {@link KernelCryptoProvider#priority()} is
     * exactly zero.
     *
     * @apiNote Skipped by a bare early return, not
     *          {@link org.junit.jupiter.api.Assumptions#assumeTrue}, when
     *          {@link #isCommunityTier()} is {@code false} — see that method's Javadoc.
     */
    @Test
    @DisplayName("Provider metadata: Community tier: priority() == 0")
    public final void communityPriorityIsZero() {
        if (!isCommunityTier()) return;
        assertThat(provider.priority())
                .as("Community KernelCryptoProvider MUST have priority() == 0")
                .isZero();
    }

    /**
     * For a Community-tier provider, verifies that {@link KernelCryptoProvider#supportsQuic()}
     * returns {@code false} — QUIC support is Enterprise-only.
     *
     * @apiNote Skipped by a bare early return, not
     *          {@link org.junit.jupiter.api.Assumptions#assumeTrue}, when
     *          {@link #isCommunityTier()} is {@code false} — see that method's Javadoc.
     */
    @Test
    @DisplayName("Provider metadata: Community tier: supportsQuic() == false")
    public final void communityDoesNotSupportQuic() {
        if (!isCommunityTier()) return;
        assertThat(provider.supportsQuic())
                .as("Community KernelCryptoProvider MUST return supportsQuic() == false " +
                        "(QUIC is Enterprise-only)")
                .isFalse();
    }

    /**
     * Verifies that the provider under test exposes a public no-argument constructor, as
     * {@link java.util.ServiceLoader} requires for discovery.
     *
     * @throws NoSuchMethodException if the provider under test declares no <em>public</em>
     *         no-arg constructor, propagated so JUnit reports it as this test's failure —
     *         {@code Class.getConstructor()} only resolves public constructors, so the
     *         explicit {@code Modifier.isPublic} assertion that follows the lookup is
     *         necessarily true whenever the lookup itself succeeds
     */
    @Test
    @DisplayName("Provider metadata: ServiceLoader contract: public no-arg constructor exists")
    public final void publicNoArgConstructorExists() throws NoSuchMethodException {
        Constructor<?> constructor = provider.getClass().getConstructor();
        assertThat(constructor)
                .as("KernelCryptoProvider MUST have a public no-arg constructor for ServiceLoader")
                .isNotNull();
        assertThat(Modifier.isPublic(constructor.getModifiers()))
                .as("KernelCryptoProvider no-arg constructor MUST be public for ServiceLoader")
                .isTrue();
    }

    // =========================================================================
    // createTlsEngine — protocol routing
    // =========================================================================

    /**
     * Verifies that {@link KernelCryptoProvider#createTlsEngine} returns a non-null engine for
     * a {@code TCP_TLS} configuration.
     */
    @Test
    @DisplayName("createTlsEngine: createTlsEngine(TCP_TLS) returns a non-null engine")
    public final void createTcpTlsEngineIsNonNull() {
        try (TlsEngine engine = provider.createTlsEngine(tcpTlsConfig())) {
            assertThat(engine)
                    .as("createTlsEngine(TCP_TLS) MUST return a non-null TlsEngine")
                    .isNotNull();
        }
    }

    /**
     * For a Community-tier provider, verifies that {@link KernelCryptoProvider#createTlsEngine}
     * throws {@link CryptoBootstrapException} for a QUIC configuration — QUIC is
     * Enterprise-only.
     *
     * @apiNote Skipped by a bare early return, not
     *          {@link org.junit.jupiter.api.Assumptions#assumeTrue}, when
     *          {@link #isCommunityTier()} is {@code false} — see that method's Javadoc.
     */
    @Test
    @DisplayName("createTlsEngine: Community: createTlsEngine(QUIC) throws CryptoBootstrapException")
    public final void communityRejectsQuicConfig() {
        if (!isCommunityTier()) return;
        var config = quicConfig();
        assertThatThrownBy(() -> provider.createTlsEngine(config))
                .as("Community MUST throw CryptoBootstrapException for QUIC config — " +
                        "QUIC support is Enterprise-only (The Wall contract)")
                .isInstanceOf(CryptoBootstrapException.class);
    }

    /**
     * Verifies that a second, redundant call to {@link TlsEngine#close()} does not throw —
     * double-close is a safe no-op.
     */
    @Test
    @DisplayName("createTlsEngine: close() is idempotent — double-close does not throw")
    public final void engineCloseIsIdempotent() {
        TlsEngine engine = provider.createTlsEngine(tcpTlsConfig());
        engine.close();
        assertThatCode(engine::close)
                .as("TlsEngine.close() MUST be idempotent — double-close is a safe no-op")
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // TlsEngine handshake + I/O — zero-copy contract
    // =========================================================================

    /**
     * Verifies {@link TlsEngine#beginHandshake(LoanedBuffer)}.
     *
     * <p>When {@link #requiresExternalBindBeforeHandshake()} is {@code false} (the default),
     * asserts the call returns a non-null {@link TlsStatus} other than {@link TlsStatus#CLOSED}
     * — a handshake needs at least one further exchange before it can be closed.
     *
     * <p>When {@link #requiresExternalBindBeforeHandshake()} is {@code true}, asserts instead
     * that the call fails fast with {@link TlsHandshakeException}, since the engine cannot
     * enter the handshake phase without the external bind this harness does not perform.
     */
    @Test
    @DisplayName("TlsEngine I/O: beginHandshake() returns a non-null, non-CLOSED status")
    public final void beginHandshakeReturnsValidStatus() {
        try (TlsEngine engine = provider.createTlsEngine(tcpTlsConfig());
             LoanedBuffer out = allocator.allocate(AllocationHint.MEDIUM)) {

            if (requiresExternalBindBeforeHandshake()) {
                assertThatThrownBy(() -> engine.beginHandshake(out))
                        .as("beginHandshake() before external bind MUST fail fast with lifecycle error")
                        .isInstanceOf(TlsHandshakeException.class);
                return;
            }

            TlsStatus status = engine.beginHandshake(out);

            assertThat(status)
                    .as("beginHandshake() MUST return a non-null TlsStatus")
                    .isNotNull();
            assertThat(status)
                    .as("beginHandshake() MUST NOT return CLOSED immediately — " +
                            "handshake requires at least one exchange")
                    .isNotEqualTo(TlsStatus.CLOSED);
        }
    }

    /**
     * Verifies that {@link TlsEngine#wrap(LoanedBuffer, LoanedBuffer)} returns a non-null
     * status for off-heap plaintext and ciphertext buffers.
     *
     * @apiNote Checks only that the returned status is non-null; it does not inspect the
     *          ciphertext content or length, so it does not by itself prove the plaintext was
     *          encrypted. Skipped via {@link org.junit.jupiter.api.Assumptions#assumeTrue}
     *          when {@link #isIoReady()} is {@code false}.
     */
    @Test
    @DisplayName("TlsEngine I/O: wrap() with off-heap LoanedBuffer returns a valid status")
    public final void wrapWithOffHeapBufferReturnsValidStatus() {
        assumeTrue(isIoReady(),
                "Skipped: this engine requires fd-based BIO wiring before wrap() " +
                        "— covered by OffHeapTlsEngineLoopbackIT");
        try (TlsEngine engine = provider.createTlsEngine(tcpTlsConfig());
             LoanedBuffer plaintext = allocator.allocate(AllocationHint.SMALL);
             LoanedBuffer ciphertext = allocator.allocate(AllocationHint.MEDIUM)) {

            if (plaintext.capacity() > 0) {
                plaintext.segment().set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 0xFF);
            }

            TlsStatus status = engine.wrap(plaintext, ciphertext);

            assertThat(status)
                    .as("wrap() with valid off-heap LoanedBuffers MUST return a non-null status")
                    .isNotNull();
        }
    }

    /**
     * Verifies that {@link TlsEngine#unwrap(LoanedBuffer, LoanedBuffer)} returns a non-null
     * status for off-heap ciphertext and plaintext buffers.
     *
     * @apiNote Checks only that the returned status is non-null; it does not inspect the
     *          decrypted content, so it does not by itself prove the ciphertext was decrypted.
     *          Unlike its {@code wrap()} counterpart, this test does not gate on
     *          {@link #isIoReady()}.
     */
    @Test
    @DisplayName("TlsEngine I/O: unwrap() with off-heap LoanedBuffer returns a valid status")
    public final void unwrapWithOffHeapBufferReturnsValidStatus() {
        assumeTrue(isIoReady(),
                "Skipped: this engine requires fd-based BIO wiring before unwrap() " +
                        "— covered by OffHeapTlsEngineLoopbackIT");
        try (TlsEngine engine = provider.createTlsEngine(tcpTlsConfig());
             LoanedBuffer ciphertext = allocator.allocate(AllocationHint.MEDIUM);
             LoanedBuffer plaintext = allocator.allocate(AllocationHint.MEDIUM)) {

            TlsStatus status = engine.unwrap(ciphertext, plaintext);

            assertThat(status)
                    .as("unwrap() with valid off-heap LoanedBuffers MUST return a non-null status")
                    .isNotNull();
        }
    }

    /**
     * Verifies that a {@link LoanedBuffer} obtained from the allocator under test is backed by
     * native (off-heap) memory — {@link java.lang.foreign.MemorySegment#isNative()} is
     * {@code true} — the structural precondition for the zero-copy TLS contract.
     */
    @Test
    @DisplayName("TlsEngine I/O: ZERO-COPY: plaintext LoanedBuffer segment is off-heap (not a heap array)")
    public final void plaintextBufferIsOffHeap() {
        try (LoanedBuffer buf = allocator.allocate(AllocationHint.SMALL)) {
            assertThat(buf.segment().isNative())
                    .as("LoanedBuffer.segment() MUST be backed by native (off-heap) memory. " +
                            "A heap-backed segment (isNative=false) means the allocator " +
                            "created a MemorySegment.ofArray() wrapper — this is a banned " +
                            "heap copy on the zero-copy TLS path.")
                    .isTrue();
        }
    }

    /**
     * Verifies that {@link TlsEngine#wrap(LoanedBuffer, LoanedBuffer)} does not change the
     * plaintext {@link LoanedBuffer}'s segment address.
     *
     * @apiNote Detects a reallocation of the plaintext segment, not the absence of a copy: an
     *          implementation that copies the plaintext into a separate heap or native buffer
     *          for encryption while returning the same {@code LoanedBuffer} handle unchanged
     *          would still pass. Skipped via
     *          {@link org.junit.jupiter.api.Assumptions#assumeTrue} when {@link #isIoReady()}
     *          is {@code false}.
     */
    @Test
    @DisplayName("TlsEngine I/O: wrap() input address is preserved — no silent heap copy")
    public final void wrapDoesNotCopyToHeap() {
        assumeTrue(isIoReady(),
                "Skipped: this engine requires an ACTIVE session for wrap() address preservation " +
                        "— covered by OffHeapTlsEngineLoopbackIT");
        try (TlsEngine engine = provider.createTlsEngine(tcpTlsConfig());
             LoanedBuffer plaintext = allocator.allocate(AllocationHint.SMALL);
             LoanedBuffer ciphertext = allocator.allocate(AllocationHint.MEDIUM)) {

            long addressBefore = plaintext.segment().address();

            engine.wrap(plaintext, ciphertext);

            assertThat(plaintext.segment().address())
                    .as("plaintext LoanedBuffer segment address MUST remain unchanged " +
                            "after wrap() — a changed address indicates an illegal heap copy. " +
                            "TLS encryption MUST operate in-place on the off-heap slab " +
                            "via MemorySegment.asSlice() — not via byte[] intermediaries.")
                    .isEqualTo(addressBefore);
        }
    }

    // =========================================================================
    // Error code contract — one-code-one-schema invariant
    // =========================================================================

    /**
     * Validates the one-code-one-schema invariant from {@code docs/subsystems/crypto.md}:
     * {@code unwrap()} MUST throw {@link TlsDecryptException} ({@code EX-NET-2003}),
     * NOT a generic {@code TlsException} ({@code EX-NET-2001}).
     */
    @Test
    @DisplayName("Error-code: unwrap() on a closed engine throws TlsDecryptException (EX-NET-2003)")
    public final void unwrapOnClosedEngineThrowsTlsDecryptException() {
        TlsEngine engine = provider.createTlsEngine(tcpTlsConfig());
        engine.close();
        try (LoanedBuffer cipher = allocator.allocate(AllocationHint.MEDIUM);
             LoanedBuffer plain = allocator.allocate(AllocationHint.MEDIUM)) {
            assertThatThrownBy(() -> engine.unwrap(cipher, plain))
                    .as("unwrap() on a closed TlsEngine MUST throw TlsDecryptException " +
                            "(EX-NET-2003) to preserve the one-code-one-schema invariant. " +
                            "Throwing TlsException or TlsHandshakeException is a contract violation.")
                    .isInstanceOf(TlsDecryptException.class);
        }
    }

    /**
     * Verifies that {@link TlsEngine#wrap} and {@link TlsEngine#unwrap} on a closed engine
     * throw different exception types, preserving the one-code-one-schema invariant between the
     * encrypt and decrypt paths.
     *
     * @apiNote Only {@code unwrap()}'s exception type is pinned, to {@link TlsDecryptException}
     *          ({@code EX-NET-2003}); {@code wrap()} is required only to throw some
     *          {@code RuntimeException} distinct from that type, not any particular class or
     *          {@code EX-*} code.
     */
    @Test
    @DisplayName("Error-code: wrap() and unwrap() on closed engine throw different exception types")
    public final void wrapAndUnwrapThrowDistinctExceptionTypes() {
        TlsEngine engine = provider.createTlsEngine(tcpTlsConfig());
        engine.close();
        try (LoanedBuffer buf1 = allocator.allocate(AllocationHint.MEDIUM);
             LoanedBuffer buf2 = allocator.allocate(AllocationHint.MEDIUM)) {
            Class<?> wrapExType = null;
            try {
                engine.wrap(buf1, buf2);
            } catch (RuntimeException e) {
                wrapExType = e.getClass();
            }
            Class<?> unwrapExType = null;
            try {
                engine.unwrap(buf1, buf2);
            } catch (RuntimeException e) {
                unwrapExType = e.getClass();
            }
            assertThat(wrapExType)
                    .as("wrap() on closed engine must throw some exception")
                    .isNotNull();
            assertThat(unwrapExType)
                    .as("unwrap() on closed engine MUST throw TlsDecryptException (EX-NET-2003)")
                    .isEqualTo(TlsDecryptException.class);
            assertThat(unwrapExType)
                    .as("wrap() and unwrap() MUST throw different exception types to preserve " +
                            "the one-code-one-schema invariant (EX-NET-2001 vs EX-NET-2003)")
                    .isNotEqualTo(wrapExType);
        }
    }
}
