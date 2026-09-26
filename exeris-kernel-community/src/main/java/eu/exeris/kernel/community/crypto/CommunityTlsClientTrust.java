/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@code X509_STORE} shared by the client TLS contexts of one carrier: the certificates a client
 * verifies its server's chain against.
 *
 * <p>The creator holds one reference and gives it up through {@link #close()}. Each context built
 * from the store holds its own native reference through {@code SSL_CTX_set1_cert_store}, so a context
 * outlives {@code close()}. A lease ({@link #retainStore()}/{@link #release()}) covers the window in
 * which a context is handed the store. A {@code close()} while a lease is out defers
 * {@code X509_STORE_free} to the last {@code release()}. A {@code retainStore()} after
 * {@code close()} throws {@link IllegalStateException}, so no context ever receives a store whose
 * last reference is gone. The reference count is updated by CAS, the same protocol as
 * {@code NativeCipherContext}.
 *
 * <p>The store's native memory belongs to OpenSSL and is not tracked by the kernel's memory budget.
 *
 * @since 0.12
 */
public final class CommunityTlsClientTrust implements AutoCloseable {

    /** Where the store's certificates came from. */
    public enum TrustSource {
        /** The file named by {@code crypto.tls.client.trustFile}, and nothing else. */
        CONFIGURED_FILE,
        /** OpenSSL's default locations, which {@code SSL_CERT_FILE} and {@code SSL_CERT_DIR} override. */
        SYSTEM_DEFAULT
    }

    private static final System.Logger LOG = System.getLogger(CommunityTlsClientTrust.class.getName());

    private static final VarHandle REF_COUNT;

    static {
        try {
            REF_COUNT = MethodHandles.lookup()
                    .findVarHandle(CommunityTlsClientTrust.class, "refCount", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final int BASE_REF_COUNT = 1;
    private static final String CRYPTO_PROVIDER_NAME = "ExerisCommunity/OpenSSL3-TCP";

    /** Mutated only through {@link #REF_COUNT}. */
    @SuppressWarnings("unused")
    private volatile int refCount = BASE_REF_COUNT;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final CoreSslHandles.TrustStoreHandles handles;
    private final long store;
    private final TrustSource source;
    private final String defaultCertFile;
    private final String defaultCertDir;
    private final boolean defaultTrustPresent;

    /* default */ CommunityTlsClientTrust(CoreSslHandles.TrustStoreHandles handles,
                                          long store,
                                          TrustSource source,
                                          String defaultCertFile,
                                          String defaultCertDir,
                                          boolean defaultTrustPresent) {
        this.handles = handles;
        this.store = store;
        this.source = source;
        this.defaultCertFile = defaultCertFile;
        this.defaultCertDir = defaultCertDir;
        this.defaultTrustPresent = defaultTrustPresent;
    }

    /**
     * Refuses a trust file Java can already tell is unusable, before any native call: one that is
     * missing, not a regular file, or not readable.
     *
     * @param trustFile the configured file
     * @throws CryptoBootstrapException ({@code EX-NET-2002}) if {@code trustFile} is not a readable
     *         regular file; {@code rawArgs} carry the path
     */
    public static void requireReadableTrustFile(Path trustFile) {
        if (!Files.isRegularFile(trustFile) || !Files.isReadable(trustFile)) {
            throw new CryptoBootstrapException(CRYPTO_PROVIDER_NAME,
                    "trust file is not a readable regular file", trustFile.toString());
        }
    }

    /**
     * {@link #requireReadableTrustFile(Path)} for a location given as text.
     *
     * @param location the configured location
     * @return the location as a path
     * @throws CryptoBootstrapException ({@code EX-NET-2002}) if {@code location} is not a path, with
     *         the parse failure as its cause, or not a readable regular file
     */
    public static Path readableTrustFile(String location) {
        Path trustFile;
        try {
            trustFile = Path.of(location);
        } catch (InvalidPathException notAPath) {
            throw new CryptoBootstrapException(CRYPTO_PROVIDER_NAME,
                    "trust file is not a readable regular file", notAPath);
        }
        requireReadableTrustFile(trustFile);
        return trustFile;
    }

    /**
     * Where the store's certificates came from.
     *
     * @return {@link TrustSource#CONFIGURED_FILE} or {@link TrustSource#SYSTEM_DEFAULT}
     */
    public TrustSource source() {
        return source;
    }

    /**
     * The default CA file OpenSSL read, after {@code SSL_CERT_FILE}.
     *
     * @return the path, or {@code null} for a store loaded from a configured file
     */
    public String defaultCertFile() {
        return defaultCertFile;
    }

    /**
     * The default CA directory OpenSSL consults, after {@code SSL_CERT_DIR}.
     *
     * @return the path, or {@code null} for a store loaded from a configured file
     */
    public String defaultCertDir() {
        return defaultCertDir;
    }

    /**
     * Whether the default file or directory exists. A system-default store without either trusts
     * nothing, and every verification against it fails.
     *
     * @return {@code true} if either default location exists; {@code false} for a configured file
     */
    public boolean defaultTrustPresent() {
        return defaultTrustPresent;
    }

    /**
     * Takes a lease on the store for the time it is being handed to a context.
     *
     * @return the {@code X509_STORE*} address, valid until the matching {@link #release()}
     * @throws IllegalStateException if this trust has been closed
     */
    /* default */ long retainStore() {
        if (closed.get()) {
            throw new IllegalStateException("client trust is closed");
        }
        int current;
        do {
            current = (int) REF_COUNT.getVolatile(this);
            if (current <= 0) {
                throw new IllegalStateException("client trust is closed");
            }
        } while (!REF_COUNT.compareAndSet(this, current, current + 1));
        return store;
    }

    /**
     * Gives up one lease, freeing the store if it was the last reference.
     *
     * @throws IllegalStateException if there is no reference left to give up
     */
    /* default */ void release() {
        int previous = (int) REF_COUNT.getAndAdd(this, -1);
        if (previous == BASE_REF_COUNT) {
            freeStore();
        } else if (previous <= 0) {
            REF_COUNT.setRelease(this, 0);
            throw new IllegalStateException("client trust released more often than retained");
        }
    }

    /**
     * Gives up the creator's reference. Idempotent. The store is freed now, or by the last
     * outstanding lease; every context already built from it keeps its own reference.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            release();
        }
    }

    private void freeStore() {
        try {
            handles.invokeStoreFree(store);
        } catch (TlsException failure) {
            LOG.log(System.Logger.Level.WARNING, "X509_STORE_free failed; the client trust store leaks", failure);
        }
    }
}
