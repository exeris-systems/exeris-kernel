/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.testkit.http;

import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpServerEngine;

/**
 * Deterministic fixture for starting and stopping a kernel-owned HTTP engine in tests.
 */
public interface EmbeddedHttpEngineFixture extends AutoCloseable {

    void start(HttpHandler handler);

    HttpServerEngine engine();

    int boundPort();

    boolean isRunning();

    @Override
    void close();
}
