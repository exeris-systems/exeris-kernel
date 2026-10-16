/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;

/**
 * Executes minimal startup measurement step.
 */
@SuppressWarnings({"PMD.DoNotTerminateVM", "PMD.SignatureDeclareThrowsException"})
/* default */ final class StartupStep {

    private StartupStep() {
        // Utility step class
    }

    /* default */ static void run() throws Exception {
        Checks checks = Checks.declare("startup", "bootstrap");

        System.setProperty("exeris.http.port", "0");
        System.setProperty("exeris.persistence.jdbcUrl", "jdbc:h2:mem:startup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        System.setProperty("exeris.persistence.runMigrations", "true");

        long startTime = System.nanoTime();
        long[] bootTimeHolder = {0L};

        KernelBootstrap bootstrap = KernelBootstrap.builder()
                .selector(BootstrapSelector.forNames("http", "persistence"))
                .build();

        bootstrap.boot(() -> {
            bootTimeHolder[0] = (System.nanoTime() - startTime) / 1_000_000L;
            checks.check("bootstrap", true, "http + persistence booted");
        });

        boolean passed = checks.finish();
        Checks.printMeasure(bootTimeHolder[0]);
        if (!passed) {
            System.exit(1);
        }
    }
}
