/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.persistence;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.EngineStats;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceHealthStatus;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link PersistenceBootstrap} — priority selection, interceptor
 * registration, and no-provider failure mode.
 *
 * @since 0.5.0
 */
@DisplayName("L1: PersistenceBootstrap — selection, interceptors, no-provider guard")
class PersistenceBootstrapTest {

    // =========================================================================
    // Stubs
    // =========================================================================

    private static PersistenceEngine noopEngine(String providerId) {
        return new PersistenceEngine() {
            private final AtomicInteger interceptorCount = new AtomicInteger();

            @Override public PersistenceConnection openConnection() { return null; }
            @Override public PersistenceConnection openConnection(StorageContext ctx) { return null; }
            @Override public void registerInterceptor(ConnectionInterceptor i) {
                interceptorCount.incrementAndGet();
            }
            @Override public PersistenceHealthStatus healthCheckDetailed() {
                return PersistenceHealthStatus.ok(0L);
            }
            @Override public boolean canServiceRequest() { return true; }
            @Override public EngineStats stats() { return null; }
            // close() is a lifecycle no-op for test stub — engine has no resources to release
            @Override public void close() { /* no resources to release in test stub */ }
        };
    }

    private static ConnectionInterceptor noopInterceptor() {
        return (_, _) -> { /* no-op — test stub */ };
    }

    // =========================================================================
    // Nested: No-Provider Guard
    // =========================================================================

    @Nested
    @DisplayName("No-Provider Guard")
    class NoProviderGuard {

        @Test
        @DisplayName("noProviderAvailable() factory returns EX_PERS_5007")
        void noProviderExceptionHasCorrectCode() {
            PersistenceProviderException ex =
                    PersistenceProviderException.noProviderAvailable("No jar on classpath");
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5007);
        }

        @Test
        @DisplayName("noProviderAvailable() rawArgs carry the human-readable message")
        void noProviderRawArgsCarryMessage() {
            PersistenceProviderException ex =
                    PersistenceProviderException.noProviderAvailable("diagnostic info");
            assertThat(ex.rawArgs()).contains("diagnostic info");
        }
    }

    // =========================================================================
    // Nested: InterceptorRegistry integration
    // =========================================================================

    @Nested
    @DisplayName("InterceptorRegistry integration with PersistenceBootstrap logic")
    class InterceptorRegistryIntegration {

        @Test
        @DisplayName("Registry sealed after engine receives interceptors")
        void registrySealedAfterForwarding() {
            InterceptorRegistry registry = new InterceptorRegistry();
            registry.register(noopInterceptor());
            registry.register(noopInterceptor());

            List<ConnectionInterceptor> interceptors = registry.seal();
            PersistenceEngine engine = noopEngine("test-provider");
            for (ConnectionInterceptor i : interceptors) {
                engine.registerInterceptor(i);
            }

            assertThat(registry.isSealed()).isTrue();
            assertThat(interceptors).hasSize(2);
        }

        @Test
        @DisplayName("Empty interceptor list: engine registers zero interceptors")
        void emptyInterceptorListRegistersNone() {
            InterceptorRegistry registry = new InterceptorRegistry();
            List<ConnectionInterceptor> interceptors = registry.seal();
            assertThat(interceptors).isEmpty();
            assertThat(registry.size()).isZero();
        }

        @Test
        @DisplayName("Interceptor ordering is preserved through seal → register cycle")
        void interceptorOrderPreservedThrough() {
            ConnectionInterceptor i1 = noopInterceptor();
            ConnectionInterceptor i2 = noopInterceptor();
            ConnectionInterceptor i3 = noopInterceptor();

            InterceptorRegistry registry = new InterceptorRegistry();
            registry.register(i1);
            registry.register(i2);
            registry.register(i3);

            List<ConnectionInterceptor> sealed = registry.seal();
            assertThat(sealed).containsExactly(i1, i2, i3);
        }
    }

    // =========================================================================
    // Nested: PersistenceProvider priority semantics
    // =========================================================================

    @Nested
    @DisplayName("PersistenceProvider priority semantics")
    class ProviderPrioritySemantics {

        @Test
        @DisplayName("PersistenceProvider default priority() returns 0 (Community contract)")
        void defaultPriorityIsZero() {
            PersistenceProvider community = new PersistenceProvider() {
                @Override public String providerId()   { return "community"; }
                @Override public String providerName() { return "Community/JDBC"; }
                @Override public PersistenceEngine createEngine(PersistenceConfig config) {
                    return noopEngine("community");
                }
            };
            assertThat(community.priority()).isZero();
        }

        @Test
        @DisplayName("Enterprise provider with priority=100 outranks Community with priority=0")
        void enterpriseOutranksCommunity() {
            PersistenceProvider community  = createProvider(0, "community");
            PersistenceProvider enterprise = createProvider(100, "enterprise");

            PersistenceProvider selected = Stream.of(community, enterprise)
                    .max(Comparator.comparingInt(PersistenceProvider::priority))
                    .orElseThrow();

            assertThat(selected.providerId()).isEqualTo("enterprise");
        }

        @Test
        @DisplayName("Single community provider is selected when enterprise is absent")
        void singleCommunitySelected() {
            PersistenceProvider community = createProvider(0, "community");
            PersistenceProvider selected  = Stream.of(community)
                    .max(Comparator.comparingInt(PersistenceProvider::priority))
                    .orElseThrow();
            assertThat(selected.providerId()).isEqualTo("community");
        }

        private static PersistenceProvider createProvider(int priority, String id) {
            return new PersistenceProvider() {
                @Override public String providerId()   { return id; }
                @Override public String providerName() { return id.toUpperCase(); }
                @Override public int priority()        { return priority; }
                @Override public PersistenceEngine createEngine(PersistenceConfig config) {
                    return noopEngine(id);
                }
            };
        }
    }

    // =========================================================================
    // Nested: RLS interceptor configuration validation
    // =========================================================================

    @Nested
    @DisplayName("RLS interceptor configuration validation")
    class RlsInterceptorValidation {

        @Test
        @DisplayName("rlsEnabled=true and no interceptors → throws EX_PERS_5001 with bootstrap message")
        void rlsEnabledWithNoInterceptorsThrows() {
            PersistenceConfig config = PersistenceConfig.defaults(
                    "jdbc:postgresql://localhost:5432/exeris", "u", "p");

            assertThatThrownBy(() -> PersistenceBootstrap.validateRlsInterceptorConfiguration(
                            config, List.of(), "test-provider"))
                    .isInstanceOf(PersistenceProviderException.class)
                    .satisfies(e -> {
                        PersistenceProviderException ex = (PersistenceProviderException) e;
                        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5001);
                        assertThat(ex.getMessage()).contains("bootstrap");
                    });
        }

        @Test
        @DisplayName("rlsEnabled=true with interceptors present → no exception")
        void rlsEnabledWithInterceptorsDoesNotThrow() {
            PersistenceConfig config = PersistenceConfig.defaults(
                    "jdbc:postgresql://localhost:5432/exeris", "u", "p");

            assertThatCode(() -> PersistenceBootstrap.validateRlsInterceptorConfiguration(
                            config, List.of(noopInterceptor()), "test-provider"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rlsEnabled=false and no interceptors → no exception")
        void rlsDisabledWithNoInterceptorsDoesNotThrow() {
            PersistenceConfig config = new PersistenceConfig(
                    "jdbc:postgresql://localhost:5432/exeris", "u", "p",
                    20, 2, 30_000L, 600_000L, 1_800_000L,
                    false, false, false, 100, Map.of());

            assertThatCode(() -> PersistenceBootstrap.validateRlsInterceptorConfiguration(
                            config, List.of(), "test-provider"))
                    .doesNotThrowAnyException();
        }
    }
}
