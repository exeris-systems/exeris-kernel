/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.context;

import eu.exeris.kernel.spi.exceptions.security.PrincipalContextMissingException;
import eu.exeris.kernel.spi.exceptions.security.StorageContextMissingException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L0 Contract: {@link KernelProviders} ScopedValue slot semantics (JEP 506).
 *
 * <h2>What This Proves</h2>
 * <ol>
 *   <li>All provider slots are unbound outside a {@code ScopedValue.where()} scope.</li>
 *   <li>Slots are correctly bound inside a scope and automatically restored on exit.</li>
 *   <li>Nested scopes shadow outer bindings — critical for test isolation.</li>
 *   <li>Bindings propagate into {@link StructuredTaskScope} forks (JEP 525),
 *       matching the structured concurrency contract.</li>
 *   <li>Convenience accessors ({@link KernelProviders#principal()},
 *       {@link KernelProviders#storageContext()}) throw the correct SPI exceptions
 *       when called outside a security scope.</li>
 * </ol>
 * </ol>
 *
 * <h2>Zero-Magic Guard</h2>
 * <p>All provider instances are hand-written anonymous implementations —
 * no Mockito, no reflection, no DI.
 *
 * @since 0.5.0
 */
@DisplayName("L0: KernelProviders ScopedValue slot contracts (JEP 506)")
class KernelProvidersTest {

    // -----------------------------------------------------------------------
    // 1. Unbound slots — outside any ScopedValue.where()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("1. Unbound slots — outside ScopedValue.where()")
    class UnboundSlots {

        @Test
        @DisplayName("MEMORY_ALLOCATOR.isBound() == false outside kernel context")
        void memoryAllocatorUnbound() {
            assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isFalse();
        }

        @Test
        @DisplayName("MEMORY_PROVIDER.isBound() == false outside kernel context")
        void memoryProviderUnbound() {
            assertThat(KernelProviders.MEMORY_PROVIDER.isBound()).isFalse();
        }

        @Test
        @DisplayName("CRYPTO_PROVIDER.isBound() == false outside kernel context")
        void cryptoProviderUnbound() {
            assertThat(KernelProviders.CRYPTO_PROVIDER.isBound()).isFalse();
        }

        @Test
        @DisplayName("TELEMETRY_PROVIDER.isBound() == false outside kernel context")
        void telemetryProviderUnbound() {
            assertThat(KernelProviders.TELEMETRY_PROVIDER.isBound()).isFalse();
        }

        @Test
        @DisplayName("PERSISTENCE_ENGINE.isBound() == false outside kernel context")
        void persistenceEngineUnbound() {
            assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isFalse();
        }

        @Test
        @DisplayName("PRINCIPAL_CONTEXT.isBound() == false outside security scope")
        void principalContextUnbound() {
            assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound()).isFalse();
        }

        @Test
        @DisplayName("STORAGE_CONTEXT.isBound() == false outside security scope")
        void storageContextUnbound() {
            assertThat(KernelProviders.STORAGE_CONTEXT.isBound()).isFalse();
        }

        @Test
        @DisplayName("MEMORY_ALLOCATOR.get() throws NoSuchElementException when unbound")
        void memoryAllocatorGetThrowsWhenUnbound() {
            assertThatThrownBy(KernelProviders.MEMORY_ALLOCATOR::get)
                    .isInstanceOf(NoSuchElementException.class);
        }

        @Test
        @DisplayName("CARRIER_INDEX.orElse(0) returns 0 when unbound — graceful default")
        void carrierIndexDefaultsToZero() {
            assertThat(KernelProviders.carrierIndex()).isZero();
        }
    }

    // -----------------------------------------------------------------------
    // 2. Bound slots — inside ScopedValue.where()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("2. Bound slots — inside ScopedValue.where()")
    class BoundSlots {

        @Test
        @DisplayName("MEMORY_ALLOCATOR.isBound() == true inside ScopedValue.where()")
        void memoryAllocatorBound() {
            MemoryAllocator mock = stubAllocator();
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, mock).run(() ->
                    assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isTrue());
        }

        @Test
        @DisplayName("MEMORY_ALLOCATOR.get() returns exactly the bound instance")
        void memoryAllocatorGetReturnsBound() {
            MemoryAllocator mock = stubAllocator();
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, mock).run(() ->
                    assertThat(KernelProviders.MEMORY_ALLOCATOR.get()).isSameAs(mock));
        }

        @Test
        @DisplayName("ScopedValue is unbound after the lambda exits — scope cleanup")
        void unboundAfterScope() {
            MemoryAllocator mock = stubAllocator();
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, mock).run(() -> {});
            assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // 3. Nested scopes — shadowing contract
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("3. Nested scopes — inner binding shadows outer")
    class NestedScopes {

        @Test
        @DisplayName("Inner scope shadows outer; outer is restored on inner exit")
        void nestedScopeShadows() {
            MemoryAllocator outer = stubAllocator();
            MemoryAllocator inner = stubAllocator();

            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, outer).run(() -> {
                assertThat(KernelProviders.MEMORY_ALLOCATOR.get()).isSameAs(outer);

                ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, inner).run(() ->
                        assertThat(KernelProviders.MEMORY_ALLOCATOR.get()).isSameAs(inner));

                // Restored to outer after inner scope exits
                assertThat(KernelProviders.MEMORY_ALLOCATOR.get()).isSameAs(outer);
            });
        }
    }

    // -----------------------------------------------------------------------
    // 4. StructuredTaskScope propagation (JEP 525 + JEP 506 interaction)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("4. StructuredTaskScope propagation — ScopedValue inherited by forked tasks")
    class StructuredForkPropagation {

        @Test
        @DisplayName("ScopedValue propagates into StructuredTaskScope fork")
        void propagatesIntoStructuredFork() {
            MemoryAllocator mock = stubAllocator();
            AtomicBoolean childSaw = new AtomicBoolean(false);

            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, mock).run(() -> {
                try (var scope = StructuredTaskScope.open(
                        StructuredTaskScope.Joiner.<Boolean>awaitAllSuccessfulOrThrow())) {
                    scope.fork(() -> {
                        childSaw.set(KernelProviders.MEMORY_ALLOCATOR.isBound()
                                && KernelProviders.MEMORY_ALLOCATOR.get() == mock);
                        return null;
                    });
                    scope.join();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(childSaw.get()).isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // 5. Convenience accessor guard methods
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("5. Convenience accessor guard methods — throw SPI exceptions when unbound")
    class ConvenienceAccessorGuards {

        @Test
        @DisplayName("principal() throws PrincipalContextMissingException when unbound")
        void principalThrowsWhenUnbound() {
            assertThatThrownBy(KernelProviders::principal)
                    .isInstanceOf(PrincipalContextMissingException.class);
        }

        @Test
        @DisplayName("storageContext() throws StorageContextMissingException when unbound")
        void storageContextThrowsWhenUnbound() {
            assertThatThrownBy(KernelProviders::storageContext)
                    .isInstanceOf(StorageContextMissingException.class);
        }

        @Test
        @DisplayName("flowSnapshotStore() returns empty Optional when unbound")
        void flowSnapshotStoreEmptyWhenUnbound() {
            assertThat(KernelProviders.flowSnapshotStore()).isEmpty();
        }

        @Test
        @DisplayName("carrierIndex() returns 0 when CARRIER_INDEX is unbound")
        void carrierIndexZeroWhenUnbound() {
            assertThat(KernelProviders.carrierIndex()).isZero();
        }
    }

    // -----------------------------------------------------------------------
    // Test doubles — hand-written stubs, zero Mockito
    // -----------------------------------------------------------------------

    /**
     * Minimal hand-written {@link MemoryAllocator} stub.
     * All methods throw {@link UnsupportedOperationException} — these are never called
     * in slot-binding tests; only the identity of the instance matters.
     */
    private static MemoryAllocator stubAllocator() {
        return new MemoryAllocator() {
            @Override
            public LoanedBuffer allocate(AllocationHint hint) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public LoanedBuffer allocateNetwork(int bytes) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public LoanedBuffer allocateCarrierSlab(int idx) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public LoanedBuffer allocateInfrastructure(long bytes) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public MemoryStats stats() {
                return MemoryStats.zero();
            }

            @Override
            public void close() {
                // no-op
            }
        };
    }
}


