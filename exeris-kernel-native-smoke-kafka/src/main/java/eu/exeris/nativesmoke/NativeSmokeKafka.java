/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Main application entry point for Kafka native smoke verification.
 */
@SuppressWarnings({
        "PMD.SystemPrintln", "PMD.DoNotTerminateVM",
        "PMD.AvoidCatchingGenericException", "PMD.SignatureDeclareThrowsException"
})
public final class NativeSmokeKafka {

    private static final String STEP_KAFKA = "kafka";
    private static final String CHECK_ROUNDTRIP = "roundtrip";
    private static final int REQUIRED_ARGS = 2;
    private static final int PROBE_EVENT_CODE = 31_001;
    private static final int AWAIT_SECONDS = 45;

    private NativeSmokeKafka() {
        // Utility main class
    }

    /**
     * Executes the Kafka smoke verification step.
     *
     * @param arguments command-line arguments: step name followed by bootstrap servers
     */
    public static void main(String... arguments) {
        try {
            execute(arguments);
        } catch (Throwable unhandledThrowable) {
            System.out.println("UNHANDLED: " + unhandledThrowable);
            for (Throwable cause = unhandledThrowable.getCause(); cause != null; cause = cause.getCause()) {
                System.out.println("  caused by: " + cause);
            }
            unhandledThrowable.printStackTrace(System.out);
            System.exit(2);
        }
    }

    private static void execute(String... arguments) throws Exception {
        if (arguments.length < REQUIRED_ARGS) {
            System.out.println("ERROR: NativeSmokeKafka requires 'kafka' <bootstrapServers>");
            System.exit(1);
        }
        String stepName = arguments[0];
        if (!STEP_KAFKA.equals(stepName)) {
            System.out.println("ERROR: Expected step 'kafka' but got " + stepName);
            System.exit(1);
        }
        String bootstrapServers = arguments[1];
        Checks checks = Checks.declare(STEP_KAFKA, "provider", CHECK_ROUNDTRIP);

        System.setProperty("exeris.events.kafka.bootstrap-servers", bootstrapServers);
        System.setProperty("exeris.persistence.jdbcUrl", "jdbc:h2:mem:kfk;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        System.setProperty("exeris.persistence.runMigrations", "true");
        System.setProperty("exeris.events.kafka.group-id", "native-smoke-" + UUID.randomUUID());

        long[] bootTimeHolder = {0L};
        long startTime = System.nanoTime();
        try {
            KernelBootstrap bootstrap = KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("events"))
                    .build();
            bootstrap.boot(() -> {
                bootTimeHolder[0] = (System.nanoTime() - startTime) / 1_000_000L;
                EventEngine eventEngine = KernelProviders.eventEngine();
                String engineClassName = eventEngine.getClass().getName();
                boolean isKafkaEngine = engineClassName.startsWith("eu.exeris.kernel.community.kafka");
                checks.check("provider", isKafkaEngine, "engine=" + engineClassName);

                eventEngine.registry().register(EventTypeSpec.ofPersistent("NativeSmokeProbe", PROBE_EVENT_CODE));

                UUID streamIdentifier = UUID.randomUUID();
                CountDownLatch eventDeliveredLatch = new CountDownLatch(1);
                eventEngine.bus().subscribe("NativeSmokeProbe", (descriptor, payload) -> {
                    try (payload) {
                        if (descriptor.streamIdHigh() == streamIdentifier.getMostSignificantBits()
                                && descriptor.streamIdLow() == streamIdentifier.getLeastSignificantBits()) {
                            eventDeliveredLatch.countDown();
                        }
                    }
                });

                UUID eventIdentifier = UUID.randomUUID();
                eventEngine.bus().publish(
                        EventDescriptor.of(
                                eventIdentifier.getMostSignificantBits(),
                                eventIdentifier.getLeastSignificantBits(),
                                streamIdentifier.getMostSignificantBits(),
                                streamIdentifier.getLeastSignificantBits(),
                                PROBE_EVENT_CODE,
                                EventDescriptor.FLAG_PERSISTENT,
                                System.currentTimeMillis()),
                        EventPayload.empty());

                try {
                    boolean delivered = eventDeliveredLatch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                    checks.check(CHECK_ROUNDTRIP, delivered, delivered ? "event received" : "timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    checks.check(CHECK_ROUNDTRIP, false, "interrupted: " + e.getMessage());
                }
            });
        } catch (Throwable e) {
            printException("Kafka execution error", e);
            checks.check("provider", false, e.toString());
            checks.check(CHECK_ROUNDTRIP, false, e.toString());
        }

        boolean passed = checks.finish();
        Checks.printMeasure(bootTimeHolder[0]);
        if (!passed) {
            System.exit(1);
        }
    }

    private static void printException(String prefix, Throwable throwable) {
        System.out.println(prefix + ": " + throwable);
        for (Throwable cause = throwable.getCause(); cause != null; cause = cause.getCause()) {
            System.out.println("  caused by: " + cause);
        }
    }
}
