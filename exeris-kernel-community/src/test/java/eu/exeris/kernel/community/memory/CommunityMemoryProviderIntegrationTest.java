/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.exceptions.memory.MemoryBootstrapException;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2 Integration: {@link CommunityMemoryProvider} — ServiceLoader discovery,
 * Open-Core tier identity, and allocator creation.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>ServiceLoader discovers at least one MemoryProvider on the classpath</li>
 *   <li>providerName() identifies Community tier</li>
 *   <li>priority() == 0 (Community Open-Core slot; Enterprise wins with higher priority)</li>
 *   <li>createAllocator(defaults) returns a non-null, functional allocator</li>
 *   <li>Two sequential allocators are independent (separate arenas)</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L2 Integration: CommunityMemoryProvider")
class CommunityMemoryProviderIntegrationTest {

    // =========================================================================
    // Provider identity
    // =========================================================================

    @Nested
    @DisplayName("Provider identity")
    class Identity {

        @Test
        @DisplayName("providerName() identifies Community tier (non-blank)")
        void providerNameIsNonBlank() {
            MemoryProvider provider = new CommunityMemoryProvider();
            assertThat(provider.providerName()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("priority() == 0 — Community Open-Core slot")
        void priorityIsZero() {
            assertThat(new CommunityMemoryProvider().priority()).isZero();
        }
    }

    // =========================================================================
    // ServiceLoader discovery
    // =========================================================================

    @Nested
    @DisplayName("ServiceLoader discovery")
    class ServiceLoaderContract {

        @Test
        @DisplayName("MemoryProvider is discoverable via ServiceLoader")
        void discoverable() {
            long count = ServiceLoader.load(MemoryProvider.class).stream().count();
            assertThat(count).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Highest-priority discovered MemoryProvider has priority >= 0")
        void highestPriorityWins() {
            MemoryProvider selected = ServiceLoader.load(MemoryProvider.class)
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .max(Comparator.comparingInt(MemoryProvider::priority))
                    .orElseThrow(() -> new AssertionError("No MemoryProvider found via ServiceLoader"));
            assertThat(selected.priority()).isGreaterThanOrEqualTo(0);
        }
    }

    // =========================================================================
    // Allocator creation
    // =========================================================================

    @Nested
    @DisplayName("createAllocator()")
    class AllocatorCreation {

        @Test
        @DisplayName("createAllocator(defaults) returns non-null MemoryAllocator")
        void returnsNonNullAllocator() {
            MemoryProvider provider = new CommunityMemoryProvider();
            try (MemoryAllocator allocator = provider.createAllocator(MemoryProviderConfig.defaults())) {
                assertThat(allocator).isNotNull();
            }
        }

        @Test
        @DisplayName("Two allocators from same provider are independent")
        void twoAllocatorsAreIndependent() {
            MemoryProvider provider = new CommunityMemoryProvider();
            try (MemoryAllocator a1 = provider.createAllocator(MemoryProviderConfig.defaults());
                 MemoryAllocator a2 = provider.createAllocator(MemoryProviderConfig.defaults())) {
                assertThat(a1).isNotSameAs(a2);
            }
        }

        @Test
        @DisplayName("close() of allocator does not affect the provider")
        void closingAllocatorDoesNotBreakProvider() {
            MemoryProvider provider = new CommunityMemoryProvider();
            MemoryAllocator first = provider.createAllocator(MemoryProviderConfig.defaults());
            first.close();
            // Provider must still be able to create another allocator
            assertThatCode(() -> {
                try (MemoryAllocator second = provider.createAllocator(MemoryProviderConfig.defaults())) {
                    assertThat(second).isNotNull();
                }
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("createAllocator(null) wraps into MemoryBootstrapException")
        void nullConfigIsWrapped() {
            MemoryProvider provider = new CommunityMemoryProvider();
            assertThatThrownBy(() -> provider.createAllocator(null))
                    .isInstanceOf(MemoryBootstrapException.class)
                    .hasCauseInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("invalid config is wrapped into MemoryBootstrapException")
        void invalidConfigIsWrapped() {
            MemoryProvider provider = new CommunityMemoryProvider();
            MemoryProviderConfig invalid = new MemoryProviderConfig(
                    1024L,
                    MemoryProviderConfig.defaults().networkOffHeapThreshold(),
                    MemoryProviderConfig.defaults().carrierCount(),
                    MemoryProviderConfig.defaults().leakDetection(),
                    MemoryProviderConfig.defaults().jfrEnabled());

            assertThatThrownBy(() -> provider.createAllocator(invalid))
                    .isInstanceOf(MemoryBootstrapException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }
}
