/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.openssl;

import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.*;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CoreOpenSslRuntime} lookup semantics.
 *
 * <p>Uses synthetic {@link SymbolLookup} instances so the suite stays deterministic and
 * does not require a real OpenSSL installation.
 */
@DisplayName("L0: CoreOpenSslRuntime (shared lookup carrier)")
class CoreOpenSslRuntimeTest {

    private static final SymbolLookup EMPTY_LOOKUP =
            symbol -> (symbol == null || symbol.isEmpty()) ? Optional.empty() : Optional.empty();

    @Test
    @DisplayName("handles() returns the exact carrier injected at construction")
    void handlesReturnsInjectedCarrier() {
        CoreSslHandles handles = CoreSslHandlesTestFactory.build(null, null, null);

        assertThat(newRuntime(EMPTY_LOOKUP,
            EMPTY_LOOKUP, handles).handles()).isSameAs(handles);
    }

    @Test
    @DisplayName("find() prefers libssl when a symbol exists in both lookups")
    void mergedLookupPrefersSsl() {
        // CHECKSTYLE:OFF DirectArena — unit-test symbol stubs only
        try (Arena arena = Arena.ofConfined()) { //NOPMD
            MemorySegment sslSymbol = arena.allocate(8L, 8L);
            MemorySegment cryptoSymbol = arena.allocate(8L, 8L);

            CoreOpenSslRuntime runtime = newRuntime(
                    symbol -> "SSL_shared".equals(symbol)
                            ? java.util.Optional.of(sslSymbol)
                            : java.util.Optional.empty(),
                    symbol -> "SSL_shared".equals(symbol)
                            ? java.util.Optional.of(cryptoSymbol)
                            : java.util.Optional.empty());

            assertThat(runtime.find("SSL_shared")).contains(sslSymbol);
            assertThat(runtime.findSsl("SSL_shared")).contains(sslSymbol);
            assertThat(runtime.findCrypto("SSL_shared")).contains(cryptoSymbol);
        }
        // CHECKSTYLE:ON
    }

    @Test
    @DisplayName("findSsl() and findCrypto() stay scoped to their exact library")
    void scopedLookupsRemainIsolated() {
        // CHECKSTYLE:OFF DirectArena — unit-test symbol stubs only
        try (Arena arena = Arena.ofConfined()) { //NOPMD
            MemorySegment sslOnly = arena.allocate(8L, 8L);
            MemorySegment cryptoOnly = arena.allocate(8L, 8L);

            CoreOpenSslRuntime runtime = newRuntime(
                    symbol -> "SSL_only".equals(symbol)
                            ? java.util.Optional.of(sslOnly)
                            : java.util.Optional.empty(),
                    symbol -> "CRYPTO_only".equals(symbol)
                            ? java.util.Optional.of(cryptoOnly)
                            : java.util.Optional.empty());

            assertThat(runtime.find("SSL_only")).contains(sslOnly);
            assertThat(runtime.findSsl("SSL_only")).contains(sslOnly);
            assertThat(runtime.findCrypto("SSL_only")).isEmpty();

            assertThat(runtime.find("CRYPTO_only")).contains(cryptoOnly);
            assertThat(runtime.findSsl("CRYPTO_only")).isEmpty();
            assertThat(runtime.findCrypto("CRYPTO_only")).contains(cryptoOnly);
        }
        // CHECKSTYLE:ON
    }

    @Test
    @DisplayName("optionalSslHandle() returns null for an unresolved tier-specific symbol")
    void optionalSslHandleReturnsNullWhenMissing() {
        assertThat(newRuntime(EMPTY_LOOKUP,
            EMPTY_LOOKUP)
                .optionalSslHandle("SSL_set_fd", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT)))
                .isNull();
    }

    @Test
    @DisplayName("requiredCryptoHandle() fails fast when the symbol is absent")
    void requiredCryptoHandleFailsFast() {
        assertThatThrownBy(() -> newRuntime(EMPTY_LOOKUP,
            EMPTY_LOOKUP)
                .requiredCryptoHandle("OPENSSL_missing", FunctionDescriptor.of(JAVA_INT)))
                .isInstanceOfSatisfying(CryptoBootstrapException.class, exception -> {
                    assertThat(exception).hasMessage("Crypto provider bootstrap failed");
                    assertThat(exception.rawArgs()).containsExactly(
                            "CoreOpenSslRuntime",
                            "Required OpenSSL symbol not found: OPENSSL_missing");
                });
    }

    private static CoreOpenSslRuntime newRuntime(SymbolLookup sslLookup, SymbolLookup cryptoLookup) {
        return newRuntime(sslLookup, cryptoLookup, CoreSslHandlesTestFactory.build(null, null, null));
    }

    private static CoreOpenSslRuntime newRuntime(SymbolLookup sslLookup,
                                                 SymbolLookup cryptoLookup,
                                                 CoreSslHandles handles) {
        return new CoreOpenSslRuntime(Linker.nativeLinker(), sslLookup, cryptoLookup, handles);
    }
}
