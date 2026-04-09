/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import eu.exeris.kernel.tck.contract.telemetry.AbstractTelemetryProviderTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractTelemetryProviderTck} backed by
 * {@link CommunityTelemetryProvider}.
 *
 * <h2>What this proves for Community tier</h2>
 * <ul>
 *   <li>providerName() is non-blank and identifies the Community tier</li>
 *   <li>priority() == 0 — correct Open-Core slot</li>
 *   <li>createSinks() returns at least one active sink (ConsoleSink as fallback)</li>
 *   <li>All sinks accept INFO / WARN / ERROR events without throwing</li>
 *   <li>ServiceLoader discovers the provider on the classpath</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("Community: CommunityTelemetryProvider TCK")
class CommunityTelemetryProviderTckTest extends AbstractTelemetryProviderTck {

    @Override
    protected TelemetryProvider createProvider() {
        return new CommunityTelemetryProvider();
    }

    @Override
    protected boolean expectStandardJfrSinkWhenEnabled() {
        return true;
    }

    @Override
    protected boolean expectSlf4jFallbackWhenJfrDisabled() {
        return true;
    }
}

