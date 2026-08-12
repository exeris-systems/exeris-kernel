/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.config.CommunityConfigProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Community-tier contract test for {@link CommunityConfigProvider}.
 *
 * <p>Verifies the invariants that every {@link ConfigProvider} implementation
 * must satisfy — as a complement to the TCK contract tests in
 * {@code exeris-kernel-core}.
 *
 * @since 0.5.0
 */
@DisplayName("CommunityConfigProvider — provider contract")
class CommunityConfigProviderContractTest {

    private final ConfigProvider provider = new CommunityConfigProvider();

    @Test
    @DisplayName("providerName() returns non-null, non-blank string")
    void providerNameIsValid() {
        assertThat(provider.providerName()).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("priority() returns a value >= 0")
    void priorityIsNonNegative() {
        assertThat(provider.priority()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("priority() equals 0 for Community tier")
    void priorityIsCommunityLevel() {
        assertThat(provider.priority()).isEqualTo(0);
    }

    @Test
    @DisplayName("kernelSettings() returns non-null Supplier")
    void kernelSettingsSupplierIsNonNull() {
        assertThat(provider.kernelSettings()).isNotNull();
    }

    @Test
    @DisplayName("kernelSettings().get() returns non-null KernelSettings with non-blank profile")
    void kernelSettingsReturnsValidSettings() {
        ConfigProvider.KernelSettings settings = provider.kernelSettings().get();
        assertThat(settings).isNotNull();
        assertThat(settings.profile()).isNotNull();
    }

    @Test
    @DisplayName("kernelSettings() Supplier is idempotent — repeated calls agree")
    void kernelSettingsIsIdempotent() {
        ConfigProvider.KernelSettings first  = provider.kernelSettings().get();
        ConfigProvider.KernelSettings second = provider.kernelSettings().get();
        // Equality, not identity: KernelSettings is a value class on this line, where isSameAs()
        // holds for any two structurally equal instances and so proves nothing about the supplier.
        // The resolve-once guarantee is pinned by CommunityConfigProviderTest instead, which
        // changes an input between calls.
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("getString() for absent key returns empty Optional")
    void getStringAbsentKeyReturnsEmpty() {
        assertThat(provider.getString("__nonexistent__.key.xyz")).isEmpty();
    }

    @Test
    @DisplayName("getInt() for absent key returns empty Optional")
    void getIntAbsentKeyReturnsEmpty() {
        assertThat(provider.getInt("__nonexistent__.key.xyz")).isEmpty();
    }

    @Test
    @DisplayName("watch() is a no-op — does not throw")
    void watchIsNoOp() {
        AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        assertThatCode(() -> provider.watch(null, "some.key", ignored -> callbackInvoked.set(true)))
            .as("Community watch() must accept the no-op contract without throwing")
            .doesNotThrowAnyException();
        assertThat(callbackInvoked.get())
            .as("Community watch() is a no-op and must not invoke the callback without a reload trigger")
            .isFalse();
    }
}
