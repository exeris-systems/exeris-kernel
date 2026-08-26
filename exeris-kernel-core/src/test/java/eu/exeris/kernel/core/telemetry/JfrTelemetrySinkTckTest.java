/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry;

import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.kernel.tck.contract.telemetry.AbstractJfrTelemetrySinkTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Core concrete TCK: {@link AbstractJfrTelemetrySinkTck} backed by {@link JfrTelemetrySink}.
 *
 * @since 0.5.0
 */
@DisplayName("Core: JfrTelemetrySink TCK")
class JfrTelemetrySinkTckTest extends AbstractJfrTelemetrySinkTck {

    @Override
    protected TelemetrySink createSink() {
        return new JfrTelemetrySink();
    }
}
