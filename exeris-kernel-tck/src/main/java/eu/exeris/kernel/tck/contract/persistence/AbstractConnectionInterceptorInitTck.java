/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.persistence;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract contract for {@link ConnectionInterceptor} initialization semantics.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Interceptor is invoked exactly once per {@code openConnection(StorageContext)}</li>
 *   <li>{@code RuntimeException} from interceptor is mapped to {@link PersistenceProviderException}
 *       with error code {@code EX-PERS-5006}; isolation key lands in {@code rawArgs[1]}</li>
 *   <li>{@link PersistenceProviderException} thrown directly by interceptor is propagated</li>
 *   <li>Engine discards the connection on failure: {@code activeConnections()} returns zero</li>
 *   <li>Engine remains healthy after interceptor failure; next open succeeds</li>
 * </ul>
 *
 * @since 0.5
 */
public abstract class AbstractConnectionInterceptorInitTck {

    /** Creates a fully bootstrapped {@link PersistenceEngine} with no pre-registered interceptors. */
    protected abstract PersistenceEngine createEngine();

    private PersistenceEngine engine;

    @BeforeEach
    final void setUpEngine() {
        engine = createEngine();
    }

    @AfterEach
    final void tearDownEngine() {
        engine.close();
    }

    // =========================================================================
    // Invocation-count contract
    // =========================================================================

    @Test
    @DisplayName("interceptor invoked exactly once for SHARED context with isolation key")
    void interceptorInvokedOnceForSharedWithKey() {
        AtomicInteger calls = new AtomicInteger();
        engine.registerInterceptor((conn, ctx) -> calls.incrementAndGet());

        try (PersistenceConnection conn = engine.openConnection(ImmutableStorageContext.shared("tenant-tck"))) {
            assertThat(conn.isOpen()).isTrue();
        }

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("interceptor invoked exactly once for GLOBAL (SHARED without isolation key)")
    void interceptorInvokedOnceForGlobal() {
        AtomicInteger calls = new AtomicInteger();
        engine.registerInterceptor((conn, ctx) -> calls.incrementAndGet());

        try (PersistenceConnection conn = engine.openConnection(ImmutableStorageContext.GLOBAL)) {
            assertThat(conn.isOpen()).isTrue();
        }

        assertThat(calls.get()).isEqualTo(1);
    }

    // =========================================================================
    // Error mapping: RuntimeException → EX-PERS-5006
    // =========================================================================

    /**
     * Static nested class for deterministic class-name assertion in {@code rawArgs[0]}.
     */
    static final class BoomInterceptor implements ConnectionInterceptor {
        @Override
        public void onConnectionAcquired(PersistenceConnection connection, StorageContext storageContext) {
            throw new IllegalStateException("injected failure");
        }
    }

    @Test
    @DisplayName("RuntimeException from interceptor is mapped to EX-PERS-5006 with isolation key in rawArgs[1]")
    void runtimeExceptionMappedToInterceptorError() {
        engine.registerInterceptor(new BoomInterceptor());
        StorageContext ctx = ImmutableStorageContext.shared("tenant-err");

        assertThatThrownBy(() -> engine.openConnection(ctx))
                .isInstanceOf(PersistenceProviderException.class)
                .satisfies(ex -> {
                    PersistenceProviderException ppe = (PersistenceProviderException) ex;
                    assertThat(ppe.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5006);
                    assertThat(ppe.rawArgs()[0]).isEqualTo(BoomInterceptor.class.getSimpleName());
                    assertThat(ppe.rawArgs()[1]).isEqualTo("tenant-err");
                });
    }

    // =========================================================================
    // Error mapping: PersistenceProviderException → propagated
    // =========================================================================

    /**
     * Static nested class that throws {@link PersistenceProviderException} directly.
     */
    static final class DirectExceptionInterceptor implements ConnectionInterceptor {
        @Override
        public void onConnectionAcquired(PersistenceConnection connection, StorageContext storageContext) {
            throw PersistenceProviderException.interceptorInitFailed(
                    DirectExceptionInterceptor.class.getSimpleName(),
                    storageContext.isolationKey().orElse("[none]"),
                    null);
        }
    }

    @Test
    @DisplayName("PersistenceProviderException thrown by interceptor is propagated with EX-PERS-5006")
    void persistenceProviderExceptionPropagatedDirectly() {
        engine.registerInterceptor(new DirectExceptionInterceptor());

        assertThatThrownBy(() -> engine.openConnection(ImmutableStorageContext.shared("tenant-direct")))
                .isInstanceOf(PersistenceProviderException.class)
                .satisfies(ex -> {
                    PersistenceProviderException ppe = (PersistenceProviderException) ex;
                    assertThat(ppe.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5006);
                });
    }

    // =========================================================================
    // Recovery contract
    // =========================================================================

    /**
     * Static nested class that fails exactly once, then no-ops.
     */
    static final class FailOnceInterceptor implements ConnectionInterceptor {
        private volatile boolean failed = false;

        @Override
        public void onConnectionAcquired(PersistenceConnection connection, StorageContext storageContext) {
            if (!failed) {
                failed = true;
                throw new IllegalStateException("one-time injected failure");
            }
        }
    }

    @Test
    @DisplayName("activeConnections is zero after interceptor failure; subsequent SHARED open succeeds with SELECT 1 = 1")
    void engineRecoversAfterInterceptorFailure() {
        engine.registerInterceptor(new FailOnceInterceptor());
        StorageContext ctx = ImmutableStorageContext.shared("tenant-recovery");

        assertThatThrownBy(() -> engine.openConnection(ctx))
                .isInstanceOf(PersistenceProviderException.class)
                .satisfies(ex -> assertThat(((PersistenceProviderException) ex).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_PERS_5006));

        assertThat(engine.stats().activeConnections()).isZero();

        try (PersistenceConnection conn = engine.openConnection(ctx);
             QueryResult result = conn.executeQuery("SELECT 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.row().getInt(0)).isEqualTo(1);
        }
    }
}
