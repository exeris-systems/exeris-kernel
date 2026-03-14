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

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable OpenSSL bootstrap runtime shared between Core and driver tiers.
 *
 * <p>Captures the exact {@code libssl} and {@code libcrypto} lookups selected by
 * {@link CoreOpenSslLoader} together with the canonical {@link CoreSslHandles} set.
 * Drivers may resolve tier-specific symbols (for example {@code SSL_set_fd}) from the
 * exact same library instance that produced the core handles, preventing ABI skew.
 *
 * <p>This class is bootstrap-only infrastructure. It owns no arena and imposes no
 * memory policy; the caller still controls native lifetime via the arena passed to
 * {@link CoreOpenSslLoader#load(java.lang.foreign.Arena)}.
 *
 * @since 0.5.0
 */
@SuppressWarnings("PMD.TooManyMethods")
public final class CoreOpenSslRuntime {

    private static final String PROVIDER = "CoreOpenSslRuntime";

    private final Linker linker;
    private final SymbolLookup sslLookup;
    private final SymbolLookup cryptoLookup;
    private final SymbolLookup mergedLookup;
    private final CoreSslHandles handles;

    /* package */ CoreOpenSslRuntime(Linker linker,
                                     SymbolLookup sslLookup,
                                     SymbolLookup cryptoLookup,
                                     CoreSslHandles handles) {
        this.linker = Objects.requireNonNull(linker, "linker must not be null");
        this.sslLookup = Objects.requireNonNull(sslLookup, "sslLookup must not be null");
        this.cryptoLookup = Objects.requireNonNull(cryptoLookup, "cryptoLookup must not be null");
        this.mergedLookup = sslLookup.or(cryptoLookup);
        this.handles = Objects.requireNonNull(handles, "handles must not be null");
    }

    /**
     * Returns the canonical Core TLS handle carrier resolved at bootstrap.
     */
    public CoreSslHandles handles() {
        return handles;
    }

    /**
     * Looks up a symbol in the merged {@code libssl.or(libcrypto)} view.
     */
    public Optional<MemorySegment> find(String symbol) {
        return mergedLookup.find(requireSymbol(symbol));
    }

    /**
     * Looks up a symbol strictly in {@code libssl}.
     */
    public Optional<MemorySegment> findSsl(String symbol) {
        return sslLookup.find(requireSymbol(symbol));
    }

    /**
     * Looks up a symbol strictly in {@code libcrypto}.
     */
    public Optional<MemorySegment> findCrypto(String symbol) {
        return cryptoLookup.find(requireSymbol(symbol));
    }

    /**
     * Resolves a required downcall handle from the merged runtime lookup.
     */
    public MethodHandle requiredHandle(String symbol, FunctionDescriptor descriptor) {
        return requiredDowncall(mergedLookup, symbol, descriptor);
    }

    /**
     * Resolves an optional downcall handle from the merged runtime lookup.
     */
    public MethodHandle optionalHandle(String symbol, FunctionDescriptor descriptor) {
        return optionalDowncall(mergedLookup, symbol, descriptor);
    }

    /**
     * Resolves a required downcall handle strictly from {@code libssl}.
     */
    public MethodHandle requiredSslHandle(String symbol, FunctionDescriptor descriptor) {
        return requiredDowncall(sslLookup, symbol, descriptor);
    }

    /**
     * Resolves an optional downcall handle strictly from {@code libssl}.
     */
    public MethodHandle optionalSslHandle(String symbol, FunctionDescriptor descriptor) {
        return optionalDowncall(sslLookup, symbol, descriptor);
    }

    /**
     * Resolves a required downcall handle strictly from {@code libcrypto}.
     */
    public MethodHandle requiredCryptoHandle(String symbol, FunctionDescriptor descriptor) {
        return requiredDowncall(cryptoLookup, symbol, descriptor);
    }

    /**
     * Resolves an optional downcall handle strictly from {@code libcrypto}.
     */
    public MethodHandle optionalCryptoHandle(String symbol, FunctionDescriptor descriptor) {
        return optionalDowncall(cryptoLookup, symbol, descriptor);
    }

    private MethodHandle requiredDowncall(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
        String requiredSymbol = requireSymbol(symbol);
        FunctionDescriptor requiredDescriptor = requireDescriptor(descriptor);
        Optional<MemorySegment> address = lookup.find(requiredSymbol);
        if (address.isEmpty()) {
            throw new CryptoBootstrapException(PROVIDER,
                    "Required OpenSSL symbol not found: " + requiredSymbol);
        }
        return linker.downcallHandle(address.get(), requiredDescriptor);
    }

    private MethodHandle optionalDowncall(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
        String requiredSymbol = requireSymbol(symbol);
        FunctionDescriptor requiredDescriptor = requireDescriptor(descriptor);
        return lookup.find(requiredSymbol)
                .map(address -> linker.downcallHandle(address, requiredDescriptor))
                .orElse(null);
    }

    private static String requireSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be null or blank");
        }
        return symbol;
    }

    private static FunctionDescriptor requireDescriptor(FunctionDescriptor descriptor) {
        return Objects.requireNonNull(descriptor, "descriptor must not be null");
    }
}

