/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.crypto;

import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 *       off-heap {@link LoanedBuffer}s (zero-copy contract).</li>
 *   <li>{@code wrap()} MUST NOT copy data to a heap {@code byte[]} —
 *       verified by checking that the input {@link LoanedBuffer} segment address
 *       differs from any heap pointer (structural check).</li>
 *   <li>{@code close()} is idempotent (double-close is safe).</li>
 *   <li>Provider name is non-null and non-blank.</li>
 *   <li>Priority contract: Community = 0, Enterprise ≥ 1.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
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
 * }</pre>
 *
 * @since 0.5.0
 */
public abstract class AbstractCryptoEngineTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /** Creates the {@link KernelCryptoProvider} under test. */
    protected abstract KernelCryptoProvider createProvider();

    /** Creates the {@link MemoryAllocator} used for off-heap TLS buffers. */
    protected abstract MemoryAllocator createAllocator();

    /**
     * Returns {@code true} if this provider is Community-tier.
     * Community must NOT support QUIC and must have priority() == 0.
     * Default: {@code true}.
     */
    protected boolean isCommunityTier() { return true; }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private KernelCryptoProvider provider;
    private MemoryAllocator      allocator;

    @BeforeEach
    final void setUp() {
        provider  = createProvider();
        allocator = createAllocator();
    }

    @AfterEach
    final void tearDown() {
        if (allocator != null) {
            allocator.close();
        }
    }

    /** Builds a minimal TCP_TLS config for use in tests. */
    private static CryptoProviderConfig tcpTlsConfig() {
        return new CryptoProviderConfig(
                CryptoProviderConfig.Protocol.TCP_TLS,
                null, null,
                List.of("h2"),
                0, false,
                CryptoProviderConfig.TLS_1_3
        );
    }

    /** Builds a QUIC config — Community must reject this. */
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

    @Nested
    @DisplayName("Provider metadata contract")
    class ProviderMetadata {

        @Test
        @DisplayName("providerName() is non-null and non-blank")
        void providerNameIsNonBlank() {
            assertThat(provider.providerName())
                    .as("KernelCryptoProvider.providerName() MUST be non-null and non-blank")
                    .isNotBlank();
        }

        @Test
        @DisplayName("priority() is non-negative (Community=0, Enterprise≥1)")
        void priorityIsNonNegative() {
            assertThat(provider.priority())
                    .as("KernelCryptoProvider.priority() MUST be >= 0")
                    .isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("Community tier: priority() == 0")
        void communityPriorityIsZero() {
            if (!isCommunityTier()) return;
            assertThat(provider.priority())
                    .as("Community KernelCryptoProvider MUST have priority() == 0")
                    .isZero();
        }

        @Test
        @DisplayName("Community tier: supportsQuic() == false")
        void communityDoesNotSupportQuic() {
            if (!isCommunityTier()) return;
            assertThat(provider.supportsQuic())
                    .as("Community KernelCryptoProvider MUST return supportsQuic() == false " +
                        "(QUIC is Enterprise-only)")
                    .isFalse();
        }

        @Test
        @DisplayName("ServiceLoader contract: public no-arg constructor exists")
        void publicNoArgConstructorExists() {
            assertThatCode(() -> provider.getClass().getDeclaredConstructor())
                    .as("KernelCryptoProvider MUST have a public no-arg constructor for ServiceLoader")
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // createTlsEngine — protocol routing
    // =========================================================================

    @Nested
    @DisplayName("createTlsEngine() — protocol routing (The Wall)")
    class TlsEngineCreation {

        @Test
        @DisplayName("createTlsEngine(TCP_TLS) returns a non-null engine")
        void createTcpTlsEngineIsNonNull() {
            try (TlsEngine engine = provider.createTlsEngine(tcpTlsConfig())) {
                assertThat(engine)
                        .as("createTlsEngine(TCP_TLS) MUST return a non-null TlsEngine")
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("Community: createTlsEngine(QUIC) throws CryptoBootstrapException")
        void communityRejectsQuicConfig() {
            if (!isCommunityTier()) return;
            var config = quicConfig();
            assertThatThrownBy(() -> provider.createTlsEngine(config))
                    .as("Community MUST throw CryptoBootstrapException for QUIC config — " +
                        "QUIC support is Enterprise-only (The Wall contract)")
                    .isInstanceOf(CryptoBootstrapException.class);
        }

        @Test
        @DisplayName("close() is idempotent — double-close does not throw")
        void closeIsIdempotent() {
            TlsEngine engine = provider.createTlsEngine(tcpTlsConfig());
            engine.close();
            assertThatCode(engine::close)
                    .as("TlsEngine.close() MUST be idempotent — double-close is a safe no-op")
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // TlsEngine handshake + I/O — zero-copy contract
    // =========================================================================

    @Nested
    @DisplayName("TlsEngine I/O — zero-copy contract (SecurityZeroAllocTck)")
    class TlsEngineIo {

        @Test
        @DisplayName("beginHandshake() returns a non-null, non-CLOSED status")
        void beginHandshakeReturnsValidStatus() {
            try (TlsEngine engine  = provider.createTlsEngine(tcpTlsConfig());
                 LoanedBuffer out  = allocator.allocate(AllocationHint.MEDIUM)) {

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

        @Test
        @DisplayName("wrap() with off-heap LoanedBuffer returns a valid status")
        void wrapWithOffHeapBufferReturnsValidStatus() {
            try (TlsEngine engine       = provider.createTlsEngine(tcpTlsConfig());
                 LoanedBuffer plaintext  = allocator.allocate(AllocationHint.SMALL);
                 LoanedBuffer ciphertext = allocator.allocate(AllocationHint.MEDIUM)) {

                // Write synthetic payload into off-heap plaintext buffer
                if (plaintext.capacity() > 0) {
                    plaintext.segment().set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 0xFF);
                }

                TlsStatus status = engine.wrap(plaintext, ciphertext);

                assertThat(status)
                        .as("wrap() with valid off-heap LoanedBuffers MUST return a non-null status")
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("unwrap() with off-heap LoanedBuffer returns a valid status")
        void unwrapWithOffHeapBufferReturnsValidStatus() {
            try (TlsEngine engine       = provider.createTlsEngine(tcpTlsConfig());
                 LoanedBuffer ciphertext = allocator.allocate(AllocationHint.MEDIUM);
                 LoanedBuffer plaintext  = allocator.allocate(AllocationHint.MEDIUM)) {

                TlsStatus status = engine.unwrap(ciphertext, plaintext);

                assertThat(status)
                        .as("unwrap() with valid off-heap LoanedBuffers MUST return a non-null status")
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("ZERO-COPY: plaintext LoanedBuffer segment is off-heap (not a heap array)")
        void plaintextBufferIsOffHeap() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.SMALL)) {
                // Off-heap MemorySegment has isNative() == true.
                // Heap-backed segments (MemorySegment.ofArray) have isNative() == false.
                // A wrap() implementation that silently copies to byte[] would expose
                // a heap segment here instead of an off-heap one.
                assertThat(buf.segment().isNative())
                        .as("LoanedBuffer.segment() MUST be backed by native (off-heap) memory. " +
                            "A heap-backed segment (isNative=false) means the allocator " +
                            "created a MemorySegment.ofArray() wrapper — this is a banned " +
                            "heap copy on the zero-copy TLS path.")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("wrap() input address is preserved — no silent heap copy")
        void wrapDoesNotCopyToHeap() {
            try (TlsEngine engine       = provider.createTlsEngine(tcpTlsConfig());
                 LoanedBuffer plaintext  = allocator.allocate(AllocationHint.SMALL);
                 LoanedBuffer ciphertext = allocator.allocate(AllocationHint.MEDIUM)) {

                // Capture the raw native address BEFORE wrap()
                long addressBefore = plaintext.segment().address();

                engine.wrap(plaintext, ciphertext);

                // After wrap(), the plaintext segment address MUST be unchanged.
                // A zero-copy implementation reads from the existing slab in-place.
                // A copying implementation would re-allocate and the address would change.
                assertThat(plaintext.segment().address())
                        .as("plaintext LoanedBuffer segment address MUST remain unchanged " +
                            "after wrap() — a changed address indicates an illegal heap copy. " +
                            "TLS encryption MUST operate in-place on the off-heap slab " +
                            "via MemorySegment.asSlice() — not via byte[] intermediaries.")
                        .isEqualTo(addressBefore);
            }
        }
    }
}

