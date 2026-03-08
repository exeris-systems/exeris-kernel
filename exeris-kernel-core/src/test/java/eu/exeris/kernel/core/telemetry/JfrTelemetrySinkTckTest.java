/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
