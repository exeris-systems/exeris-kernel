/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.kernel.tck.contract.telemetry.AbstractTelemetrySinkTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractTelemetrySinkTck} backed by {@link JfrTelemetrySink}.
 *
 * @since 0.5.0
 */
@DisplayName("Community: JfrTelemetrySink TCK")
class JfrTelemetrySinkTckTest extends AbstractTelemetrySinkTck {

    @Override
    protected TelemetrySink createSink() {
        return new JfrTelemetrySink();
    }
}

