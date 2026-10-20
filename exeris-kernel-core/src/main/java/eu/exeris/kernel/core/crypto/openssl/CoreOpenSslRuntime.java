/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * @since 0.5
 */
@SuppressWarnings("PMD.TooManyMethods")
public final class CoreOpenSslRuntime {

    private static final String PROVIDER = "CoreOpenSslRuntime";

    private final Linker linker;
    private final SymbolLookup sslLookup;
    private final SymbolLookup cryptoLookup;
    private final SymbolLookup mergedLookup;
    private final CoreSslHandles handles;
    private final MethodHandle cleanse;

    /* package */ CoreOpenSslRuntime(Linker linker,
                                     SymbolLookup sslLookup,
                                     SymbolLookup cryptoLookup,
                                     CoreSslHandles handles,
                                     MethodHandle cleanse) {
        this.linker = Objects.requireNonNull(linker, "linker must not be null");
        this.sslLookup = Objects.requireNonNull(sslLookup, "sslLookup must not be null");
        this.cryptoLookup = Objects.requireNonNull(cryptoLookup, "cryptoLookup must not be null");
        this.mergedLookup = sslLookup.or(cryptoLookup);
        this.handles = Objects.requireNonNull(handles, "handles must not be null");
        this.cleanse = Objects.requireNonNull(cleanse, "cleanse must not be null");
    }

    /**
     * Returns the canonical Core TLS handle carrier resolved at bootstrap.
     *
     * @return the {@link CoreSslHandles} instance produced by {@link CoreOpenSslLoader#load}
     */
    public CoreSslHandles handles() {
        return handles;
    }

    /**
     * Overwrites the bytes of {@code segment} with zeros through {@code OPENSSL_cleanse}.
     *
     * <p>The native routine is used instead of a Java fill because the C compiler may not elide
     * it as a dead store before the memory is released, which is the property a zeroization
     * before release depends on. A {@code null} or zero-length segment is a no-op. The segment and
     * its arena stay owned by the caller; this method neither releases nor retains them.
     *
     * @param segment the native segment to zero, or {@code null}
     * @throws IllegalArgumentException if {@code segment} is a heap segment: its address is not a
     *                                  native pointer, so the native routine would write elsewhere
     * @throws IllegalStateException    if the native call fails for a reason other than an {@link Error}
     */
    public void cleanse(MemorySegment segment) {
        if (segment == null || segment.byteSize() == 0L) {
            return;
        }
        if (!segment.isNative()) {
            throw new IllegalArgumentException("OPENSSL_cleanse needs a native segment, not a heap segment");
        }
        try {
            cleanse.invokeExact(segment.address(), segment.byteSize());
        } catch (Throwable throwable) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
            FfmErrors.rethrowIfError(throwable);
            throw new IllegalStateException("OPENSSL_cleanse invocation failed", throwable);
        }
    }

    /**
     * Looks up a symbol in the merged {@code libssl.or(libcrypto)} view.
     *
     * @param symbol the exported native symbol name
     * @return the resolved address, or {@link Optional#empty()} if neither library exports it
     * @throws IllegalArgumentException if {@code symbol} is null or blank
     */
    public Optional<MemorySegment> find(String symbol) {
        return mergedLookup.find(requireSymbol(symbol));
    }

    /**
     * Looks up a symbol strictly in {@code libssl}.
     *
     * @param symbol the exported native symbol name
     * @return the resolved address, or {@link Optional#empty()} if {@code libssl} does not
     *         export it, regardless of whether {@code libcrypto} does
     * @throws IllegalArgumentException if {@code symbol} is null or blank
     */
    public Optional<MemorySegment> findSsl(String symbol) {
        return sslLookup.find(requireSymbol(symbol));
    }

    /**
     * Looks up a symbol strictly in {@code libcrypto}.
     *
     * @param symbol the exported native symbol name
     * @return the resolved address, or {@link Optional#empty()} if {@code libcrypto} does not
     *         export it, regardless of whether {@code libssl} does
     * @throws IllegalArgumentException if {@code symbol} is null or blank
     */
    public Optional<MemorySegment> findCrypto(String symbol) {
        return cryptoLookup.find(requireSymbol(symbol));
    }

    /**
     * Resolves a required downcall handle from the merged runtime lookup.
     *
     * @param symbol     the exported native symbol name
     * @param descriptor the native function signature to bind the downcall to
     * @return a downcall handle bound to {@code symbol}'s resolved address
     * @throws IllegalArgumentException if {@code symbol} is null or blank
     * @throws NullPointerException     if {@code descriptor} is null
     * @throws CryptoBootstrapException if {@code symbol} is not exported by either library
     *                                  ({@code EX-NET-2002})
     */
    public MethodHandle requiredHandle(String symbol, FunctionDescriptor descriptor) {
        return requiredDowncall(mergedLookup, symbol, descriptor);
    }

    /**
     * Resolves an optional downcall handle from the merged runtime lookup.
     *
     * @param symbol     the exported native symbol name
     * @param descriptor the native function signature to bind the downcall to
     * @return a downcall handle bound to {@code symbol}'s resolved address, or {@code null}
     *         if neither library exports it
     * @throws IllegalArgumentException if {@code symbol} is null or blank
     * @throws NullPointerException     if {@code descriptor} is null
     */
    public MethodHandle optionalHandle(String symbol, FunctionDescriptor descriptor) {
        return optionalDowncall(mergedLookup, symbol, descriptor);
    }

    /**
     * Resolves a required downcall handle strictly from {@code libssl}.
     *
     * @param symbol     the exported native symbol name
     * @param descriptor the native function signature to bind the downcall to
     * @return a downcall handle bound to {@code symbol}'s resolved address
     * @throws IllegalArgumentException if {@code symbol} is null or blank
     * @throws NullPointerException     if {@code descriptor} is null
     * @throws CryptoBootstrapException if {@code symbol} is not exported by {@code libssl}
     *                                  ({@code EX-NET-2002})
     */
    public MethodHandle requiredSslHandle(String symbol, FunctionDescriptor descriptor) {
        return requiredDowncall(sslLookup, symbol, descriptor);
    }

    /**
     * Resolves an optional downcall handle strictly from {@code libssl}.
     *
     * @param symbol     the exported native symbol name
     * @param descriptor the native function signature to bind the downcall to
     * @return a downcall handle bound to {@code symbol}'s resolved address, or {@code null}
     *         if {@code libssl} does not export it
     * @throws IllegalArgumentException if {@code symbol} is null or blank
     * @throws NullPointerException     if {@code descriptor} is null
     */
    public MethodHandle optionalSslHandle(String symbol, FunctionDescriptor descriptor) {
        return optionalDowncall(sslLookup, symbol, descriptor);
    }

    /**
     * Resolves a required downcall handle strictly from {@code libcrypto}.
     *
     * @param symbol     the exported native symbol name
     * @param descriptor the native function signature to bind the downcall to
     * @return a downcall handle bound to {@code symbol}'s resolved address
     * @throws IllegalArgumentException if {@code symbol} is null or blank
     * @throws NullPointerException     if {@code descriptor} is null
     * @throws CryptoBootstrapException if {@code symbol} is not exported by {@code libcrypto}
     *                                  ({@code EX-NET-2002})
     */
    public MethodHandle requiredCryptoHandle(String symbol, FunctionDescriptor descriptor) {
        return requiredDowncall(cryptoLookup, symbol, descriptor);
    }

    /**
     * Resolves an optional downcall handle strictly from {@code libcrypto}.
     *
     * @param symbol     the exported native symbol name
     * @param descriptor the native function signature to bind the downcall to
     * @return a downcall handle bound to {@code symbol}'s resolved address, or {@code null}
     *         if {@code libcrypto} does not export it
     * @throws IllegalArgumentException if {@code symbol} is null or blank
     * @throws NullPointerException     if {@code descriptor} is null
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
                    "Required OpenSSL symbol not found", requiredSymbol);
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

