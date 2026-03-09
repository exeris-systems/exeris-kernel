/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link InterceptorRegistry} — ordered registration, sealing, and safety.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>Registration order is preserved</li>
 *   <li>seal() returns immutable, snapshot list</li>
 *   <li>Register after seal → {@link IllegalStateException}</li>
 *   <li>Register null → {@link IllegalArgumentException}</li>
 *   <li>size() and isSealed() state predicates</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L1: InterceptorRegistry — registration, sealing, safety")
class InterceptorRegistryTest {

    private InterceptorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InterceptorRegistry();
    }

    // =========================================================================
    // Nested: Registration Contract
    // =========================================================================

    @Nested
    @DisplayName("Registration Contract")
    class RegistrationContract {

        @Test
        @DisplayName("Empty registry has size 0 and is not sealed")
        void emptyRegistry() {
            assertThat(registry.size()).isZero();
            assertThat(registry.isSealed()).isFalse();
        }

        @Test
        @DisplayName("Registered interceptor is visible via size()")
        void sizeIncreasesOnRegister() {
            registry.register(noopInterceptor("i1"));
            assertThat(registry.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("Multiple registrations preserve insertion order")
        void registrationOrderPreserved() {
            ConnectionInterceptor i1 = noopInterceptor("i1");
            ConnectionInterceptor i2 = noopInterceptor("i2");
            ConnectionInterceptor i3 = noopInterceptor("i3");

            registry.register(i1);
            registry.register(i2);
            registry.register(i3);

            List<ConnectionInterceptor> sealed = registry.seal();
            assertThat(sealed).containsExactly(i1, i2, i3);
        }

        @Test
        @DisplayName("register(null) throws IllegalArgumentException immediately")
        void nullRegistrationThrows() {
            assertThatThrownBy(() -> registry.register(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("interceptor must not be null");
        }
    }

    // =========================================================================
    // Nested: Sealing Contract
    // =========================================================================

    @Nested
    @DisplayName("Sealing Contract")
    class SealingContract {

        @Test
        @DisplayName("seal() returns immutable list — add() throws UnsupportedOperationException")
        void sealedListIsImmutable() {
            registry.register(noopInterceptor("i1"));
            List<ConnectionInterceptor> sealed = registry.seal();
            ConnectionInterceptor extra = noopInterceptor("extra");
            assertThatThrownBy(() -> sealed.add(extra))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("seal() marks registry as sealed — isSealed() returns true")
        void sealMarksAsSealed() {
            assertThat(registry.isSealed()).isFalse();
            registry.seal();
            assertThat(registry.isSealed()).isTrue();
        }

        @Test
        @DisplayName("register() after seal() throws IllegalStateException")
        void registerAfterSealThrows() {
            registry.seal();
            ConnectionInterceptor late = noopInterceptor("late");
            assertThatThrownBy(() -> registry.register(late))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sealed");
        }

        @Test
        @DisplayName("seal() called twice returns equal content (idempotent read)")
        void doubleSealReturnsSameContent() {
            registry.register(noopInterceptor("i1"));
            List<ConnectionInterceptor> first  = registry.seal();
            List<ConnectionInterceptor> second = registry.seal();
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("Empty registry seal() returns empty immutable list")
        void emptySeal() {
            List<ConnectionInterceptor> sealed = registry.seal();
            assertThat(sealed).isEmpty();
            ConnectionInterceptor x = noopInterceptor("x");
            assertThatThrownBy(() -> sealed.add(x))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a no-op {@link ConnectionInterceptor} stub.
     * Named anonymous class so identity-based equality works in containsExactly().
     */
    private static ConnectionInterceptor noopInterceptor(String name) {
        return new ConnectionInterceptor() {
            @Override
            public void onConnectionAcquired(PersistenceConnection c,
                                             StorageContext ctx) {
                // no-op — test stub: interceptor deliberately performs no action
            }
            @Override public String toString() { return "NoopInterceptor[" + name + "]"; }
        };
    }
}
