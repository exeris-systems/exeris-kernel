/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import eu.exeris.kernel.core.security.GeneratedRoleRegistryLoader;
import eu.exeris.kernel.spi.security.RoleRegistry;

/**
 * Executes the role registry smoke verification step.
 */
@SuppressWarnings("PMD.DoNotTerminateVM")
/* default */ final class RolesStep {

    private static final int EXPECTED_METHOD_COUNT = 2;

    private RolesStep() {
        // Utility step class
    }

    /* default */ static void run() {
        Checks checks = Checks.declare("roles", "registry-present", "method-count");
        long startTime = System.nanoTime();

        RoleRegistry registry = GeneratedRoleRegistryLoader.load();
        boolean notEmpty = !registry.equals(GeneratedRoleRegistryLoader.empty());
        checks.check("registry-present", notEmpty, notEmpty ? registry.getClass().getName() : "empty fallback");

        int methodCount = registry.methodCount();
        boolean countMatches = methodCount == EXPECTED_METHOD_COUNT;
        checks.check("method-count", countMatches, "methods=" + methodCount);

        boolean passed = checks.finish();
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000L;
        Checks.printMeasure(elapsedMs);
        if (!passed) {
            System.exit(1);
        }
    }
}
