/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lease on a {@link CommunityTlsClientTrust}: {@code X509_STORE_free} runs exactly once, after
 * the creator's {@code close()} and every lease have been given up, and never while a context is
 * being handed the store.
 */
@DisplayName("CommunityTlsClientTrust — the store is freed once, by the last reference")
class CommunityTlsClientTrustTest {

    private static final long STORE = 0x5707EL;

    private final AtomicInteger frees = new AtomicInteger();

    @Test
    @DisplayName("close() with no lease out frees the store")
    void closeFreesTheStore() {
        CommunityTlsClientTrust trust = trust();

        trust.close();

        assertThat(frees).hasValue(1);
    }

    @Test
    @DisplayName("close() while a lease is out defers the free to the last release()")
    void closeDuringALeaseDefersTheFree() {
        CommunityTlsClientTrust trust = trust();

        assertThat(trust.retainStore()).isEqualTo(STORE);
        trust.close();
        assertThat(frees).as("a context is still being handed the store").hasValue(0);

        trust.release();
        assertThat(frees).hasValue(1);
    }

    @Test
    @DisplayName("two leases: the store is freed by whichever reference goes last")
    void lastOfSeveralReferencesFrees() {
        CommunityTlsClientTrust trust = trust();
        trust.retainStore();
        trust.retainStore();

        trust.release();
        trust.close();
        assertThat(frees).hasValue(0);

        trust.release();
        assertThat(frees).hasValue(1);
    }

    @Test
    @DisplayName("retainStore() after close() throws, even while another lease keeps the store alive")
    void retainAfterCloseThrows() {
        CommunityTlsClientTrust trust = trust();
        trust.retainStore();
        trust.close();

        assertThatThrownBy(trust::retainStore).isInstanceOf(IllegalStateException.class);

        trust.release();
        assertThatThrownBy(trust::retainStore).isInstanceOf(IllegalStateException.class);
        assertThat(frees).hasValue(1);
    }

    @Test
    @DisplayName("close() twice frees once")
    void closeIsIdempotent() {
        CommunityTlsClientTrust trust = trust();

        trust.close();
        trust.close();

        assertThat(frees).hasValue(1);
    }

    @Test
    @DisplayName("a release() with no reference left throws and frees nothing more")
    void doubleReleaseThrows() {
        CommunityTlsClientTrust trust = trust();
        trust.retainStore();
        trust.release();
        trust.close();

        assertThatThrownBy(trust::release).isInstanceOf(IllegalStateException.class);
        assertThat(frees).hasValue(1);
    }

    private CommunityTlsClientTrust trust() {
        return new CommunityTlsClientTrust(handles(), STORE, CommunityTlsClientTrust.TrustSource.CONFIGURED_FILE,
                null, null, false);
    }

    private CoreSslHandles.TrustStoreHandles handles() {
        MethodHandle free = bind("free", void.class, long.class);
        MethodHandle unused = MethodHandles.throwException(long.class, UnsupportedOperationException.class)
                .bindTo(new UnsupportedOperationException("not used by the lease"));
        return new CoreSslHandles.TrustStoreHandles(unused, free, null, null, unused, unused, unused, unused);
    }

    @SuppressWarnings("unused") // bound reflectively as X509_STORE_free
    private void free(long store) {
        assertThat(store).isEqualTo(STORE);
        frees.incrementAndGet();
    }

    private MethodHandle bind(String name, Class<?> returnType, Class<?>... parameters) {
        try {
            return MethodHandles.lookup()
                    .findVirtual(CommunityTlsClientTrustTest.class, name, MethodType.methodType(returnType, parameters))
                    .bindTo(this);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
