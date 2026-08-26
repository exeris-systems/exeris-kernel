/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit.http;

/**
 * Factory for deterministic embedded HTTP engine fixtures.
 */
public final class EmbeddedHttpEngineFixtures {

    private EmbeddedHttpEngineFixtures() {
    }

    public static EmbeddedHttpEngineFixture kernelBootstrapFixture() {
        return new KernelBootstrapHttpEngineFixture();
    }
}
