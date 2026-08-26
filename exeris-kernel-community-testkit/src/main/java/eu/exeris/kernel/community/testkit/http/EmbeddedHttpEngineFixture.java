/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
