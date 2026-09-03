/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community-tier integration test for {@link KernelBootstrap} with the real
 * {@link eu.exeris.kernel.community.config.CommunityConfigProvider}.
 *
 * <h2>What this test proves (Community-specific)</h2>
 * <ul>
 *   <li>{@code CommunityConfigProvider} is discoverable via {@link java.util.ServiceLoader}
 *       from the Community module classpath — the
 *       {@code META-INF/services/eu.exeris.kernel.spi.config.ConfigProvider} file
 *       is present and correct.</li>
 *   <li>{@link KernelProviders#CURRENT_CONFIG} is bound to the Community provider
 *       inside the {@code boot()} lambda.</li>
 *   <li>The ScopedValue barrier tears down cleanly after {@code boot()} returns.</li>
 * </ul>
 *
 * <h2>What this test does NOT do</h2>
 * <p>Kahn BFS ordering and {@link SubsystemOrchestrator} lifecycle are verified in
 * {@code exeris-kernel-core} tests — where the Brain lives.
 *
 * @since 0.5.0
 * @see eu.exeris.kernel.community.config.CommunityConfigProvider
 */
@DisplayName("Community: KernelBootstrap + CommunityConfigProvider integration")
class KernelBootstrapIntegrationTest {

    @Nested
    @DisplayName("CommunityConfigProvider ServiceLoader discovery")
    class ServiceLoaderDiscovery {

        @Test
        @DisplayName("CommunityConfigProvider is discovered via ServiceLoader")
        void communityProviderDiscovered() throws KernelBootstrap.BootstrapException {
            AtomicReference<String> name = new AtomicReference<>();

            KernelBootstrap.builder()
                    .selector(BootstrapSelector.none())
                    .build()
                    .boot(() -> name.set(KernelProviders.CURRENT_CONFIG.get().providerName()));

            assertThat(name.get())
                    .as("CommunityConfigProvider must be the active provider")
                    .isEqualTo("CommunityConfigProvider");
        }

        @Test
        @DisplayName("Community config resolves a non-blank profile")
        void communityProfileIsResolved() throws KernelBootstrap.BootstrapException {
            AtomicReference<String> profile = new AtomicReference<>();

            KernelBootstrap.builder()
                    .selector(BootstrapSelector.none())
                    .build()
                    .boot(() -> {
                        ConfigProvider cfg = KernelProviders.CURRENT_CONFIG.get();
                        profile.set(cfg.kernelSettings().get().profile().toString());
                    });

            assertThat(profile.get()).isNotNull().isNotBlank();
        }
    }

    @Nested
    @DisplayName("ScopedValue barrier")
    class ScopedValueBarrier {

        @Test
        @DisplayName("CURRENT_CONFIG is bound inside boot() and unbound after return")
        void scopedValueLifecycle() throws KernelBootstrap.BootstrapException {
            AtomicBoolean boundInside  = new AtomicBoolean(false);

            KernelBootstrap.builder()
                    .selector(BootstrapSelector.none())
                    .build()
                    .boot(() -> boundInside.set(KernelProviders.CURRENT_CONFIG.isBound()));

            assertThat(boundInside.get()).as("bound inside lambda").isTrue();
            assertThat(KernelProviders.CURRENT_CONFIG.isBound())
                    .as("unbound after boot() returns — The Wall holds")
                    .isFalse();
        }

        @Test
        @DisplayName("kernelMain runnable is executed with CommunityConfigProvider active")
        void kernelMainIsExecuted() throws KernelBootstrap.BootstrapException {
            AtomicBoolean ran = new AtomicBoolean(false);

            KernelBootstrap.builder()
                    .selector(BootstrapSelector.none())
                    .build()
                    .boot(() -> ran.set(true));

            assertThat(ran.get()).isTrue();
        }
    }
}
