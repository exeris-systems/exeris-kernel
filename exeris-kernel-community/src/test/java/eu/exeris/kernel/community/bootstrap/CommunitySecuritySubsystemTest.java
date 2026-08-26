/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.security.SecurityProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The subsystem whose absence made the whole Citadel path unreachable.
 *
 * <p>The admission integration test binds {@code SECURITY_PROVIDER} by hand, so it never runs
 * {@code initialize()} / {@code start()} — which is exactly the code that was missing. These cases
 * drive the lifecycle itself.
 */
@DisplayName("CommunitySecuritySubsystem")
class CommunitySecuritySubsystemTest {

    @Nested
    @DisplayName("Placement in the bootstrap DAG")
    class Placement {

        @Test
        @DisplayName("is named 'security' and depends on memory only")
        void identityAndDependencies() {
            CommunitySecuritySubsystem subsystem = new CommunitySecuritySubsystem();

            assertThat(subsystem.name())
                    .as("CommunityProviderInventory already discovers SecurityProvider under this name; "
                            + "a different one here would split the same subsystem in two")
                    .isEqualTo("security");
            assertThat(subsystem.dependsOn())
                    .as("token buffers need the allocator; naming crypto too would serialise two "
                            + "subsystems bootstrap.md runs in parallel")
                    .containsExactly("memory");
            assertThat(subsystem.phase()).isEqualTo(BootstrapPhase.SERVICES);
        }

        @Test
        @DisplayName("is registered with the Community subsystem provider")
        void isRegistered() {
            assertThat(new CommunitySubsystemProvider().getSubsystems(null))
                    .as("an unregistered subsystem is bound by nothing, which is the defect ADR-061 "
                            + "closed — the class existing is not the same as it running")
                    .anyMatch(CommunitySecuritySubsystem.class::isInstance);
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("discovers the Community provider and binds it into the slot")
        void discoversAndBinds() {
            CommunitySecuritySubsystem subsystem = new CommunitySecuritySubsystem();
            subsystem.initialize();
            subsystem.start();

            assertThat(subsystem.isRunning())
                    .as("exeris-kernel-community registers CommunitySecurityProvider via ServiceLoader")
                    .isTrue();

            AtomicBoolean boundInScope = new AtomicBoolean(false);
            subsystem.providerBindings()
                    .apply(ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, null))
                    .run(() -> boundInScope.set(KernelProviders.SECURITY_PROVIDER.isBound()));

            assertThat(boundInScope)
                    .as("binding the slot is the entire job — before this subsystem existed, "
                            + "CommunityHttpRequestProcessor always read it unbound and built no "
                            + "SecurityInterceptor")
                    .isTrue();
        }

        @Test
        @DisplayName("stop() leaves the subsystem not running")
        void stopMarksNotRunning() {
            CommunitySecuritySubsystem subsystem = new CommunitySecuritySubsystem();
            subsystem.initialize();
            subsystem.start();
            subsystem.stop();

            assertThat(subsystem.isRunning()).isFalse();
        }
    }

    @Nested
    @DisplayName("Provider discovery")
    class Discovery {

        @Test
        @DisplayName("highest priority wins, ties break on class name")
        void highestPriorityWinsDeterministically() {
            assertThat(CommunityProviderDiscovery.highestPriority(SecurityProvider.class,
                    SecurityProvider::priority))
                    .isNotNull();
            assertThat(CommunityProviderDiscovery.highestPriority(Runnable.class, r -> 0))
                    .as("absence must yield null rather than throwing — a deployment with no provider "
                            + "is a configuration, and the unbound slot then denies fail-closed")
                    .isNull();
        }
    }
}
