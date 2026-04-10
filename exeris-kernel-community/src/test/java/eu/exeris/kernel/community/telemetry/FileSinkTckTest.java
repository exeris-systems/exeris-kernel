/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.kernel.tck.contract.telemetry.AbstractTelemetrySinkTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Community concrete TCK: {@link AbstractTelemetrySinkTck} backed by {@link FileSink}.
 *
 * @since 0.5.0
 */
@DisplayName("Community: FileSink TCK")
class FileSinkTckTest extends AbstractTelemetrySinkTck {

    @TempDir
    Path tempDir;

    @Override
    protected TelemetrySink createSink() {
        return new FileSink(tempDir.resolve("kernel-tck.log"), 64);
    }
}


