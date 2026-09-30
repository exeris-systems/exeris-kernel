/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Opens a {@link CommunityTlsClientTrust}: loads its {@code X509_STORE} from a configured file or
 * from OpenSSL's default locations, and records which default locations those were.
 *
 * <p>Every failure empties the calling thread's OpenSSL error queue and frees the store before it is
 * thrown, so a refused trust leaves nothing native behind.
 */
final class CommunityTlsClientTrustLoader {

    private static final int SSL_SUCCESS = 1;
    private static final long NULL_PTR = 0L;
    /** Upper bound on the constant strings read from OpenSSL; each is a path or a variable name. */
    private static final long MAX_CONSTANT_STRING_BYTES = 4_096L;

    private CommunityTlsClientTrustLoader() {
    }

    /**
     * Opens a store: {@code trustFile} alone when it is given, OpenSSL's default locations
     * otherwise. Every failure empties the thread's OpenSSL error queue and frees the store.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // the store is freed on any failure, then rethrown
    /* default */ static CommunityTlsClientTrust open(CoreSslHandles handles, Path trustFile, String providerName) {
        if (trustFile != null) {
            CommunityTlsClientTrust.requireReadableTrustFile(trustFile);
        }
        CoreSslHandles.TrustStoreHandles trust = handles.trustStore();
        long store = trust.invokeStoreNew();
        if (store == NULL_PTR) {
            handles.errorQueue().invokeClearError();
            throw new CryptoBootstrapException(providerName, "X509_STORE_new returned NULL");
        }
        try {
            return trustFile == null
                    ? systemDefault(trust, store, providerName)
                    : configuredFile(trust, store, trustFile, providerName);
        } catch (RuntimeException | Error failure) {
            handles.errorQueue().invokeClearError();
            trust.invokeStoreFree(store);
            throw failure;
        }
    }

    private static CommunityTlsClientTrust configuredFile(CoreSslHandles.TrustStoreHandles trust, long store,
                                                          Path trustFile, String providerName) {
        int loaded = withAllocator(allocator -> {
            try (LoanedBuffer path = CommunityKernelCryptoProvider.toCString(trustFile.toAbsolutePath(), allocator)) {
                return trust.invokeStoreLoadFile(store, path.segment().address());
            }
        });
        if (loaded != SSL_SUCCESS) {
            throw new CryptoBootstrapException(providerName,
                    "trust file holds no certificate OpenSSL can load", trustFile.toString());
        }
        return new CommunityTlsClientTrust(trust, store,
                CommunityTlsClientTrust.TrustSource.CONFIGURED_FILE, null, null, false);
    }

    private static CommunityTlsClientTrust systemDefault(CoreSslHandles.TrustStoreHandles trust, long store,
                                                         String providerName) {
        if (trust.invokeStoreSetDefaultPaths(store) != SSL_SUCCESS) {
            throw new CryptoBootstrapException(providerName, "X509_STORE_set_default_paths failed");
        }
        String file = effectiveLocation(trust.invokeDefaultCertFileEnv(), trust.invokeDefaultCertFile());
        String dir = effectiveLocation(trust.invokeDefaultCertDirEnv(), trust.invokeDefaultCertDir());
        boolean present = exists(file) || exists(dir);
        return new CommunityTlsClientTrust(trust, store,
                CommunityTlsClientTrust.TrustSource.SYSTEM_DEFAULT, file, dir, present);
    }

    /**
     * The location OpenSSL's default trust reads: the environment variable it honours when set,
     * the compiled-in path otherwise.
     */
    private static String effectiveLocation(long environmentVariable, long compiledIn) {
        String variable = constantString(environmentVariable);
        String override = variable == null ? null : System.getenv(variable);
        return override == null || override.isEmpty() ? constantString(compiledIn) : override;
    }

    private static boolean exists(String location) {
        if (location == null) {
            return false;
        }
        try {
            return Files.exists(Path.of(location));
        } catch (InvalidPathException _) {
            return false;
        }
    }

    /** A NUL-terminated string OpenSSL owns and never frees, or {@code null} for a null pointer. */
    private static String constantString(long address) {
        if (address == NULL_PTR) {
            return null;
        }
        return MemorySegment.ofAddress(address).reinterpret(MAX_CONSTANT_STRING_BYTES).getString(0L);
    }

    /**
     * Runs {@code work} with the bound allocator, or with a Community allocator created and closed
     * around it when none is bound.
     */
    private static <T> T withAllocator(Function<MemoryAllocator, T> work) {
        if (KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            return work.apply(KernelProviders.MEMORY_ALLOCATOR.get());
        }
        try (MemoryAllocator own = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults())) {
            return work.apply(own);
        }
    }
}
