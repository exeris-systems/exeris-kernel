/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit.runtime;

import eu.exeris.kernel.community.testkit.FixtureBootLock;
import eu.exeris.kernel.community.testkit.SystemPropertySnapshot;
import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link EmbeddedKernelFixture} backed by a real {@link KernelBootstrap} run.
 *
 * <p>Deliberately the same shape as {@code KernelBootstrapPersistenceEngineFixture}: the kernel scope
 * is a {@code ScopedValue} binding and cannot outlive the frame that opened it, so a dedicated thread
 * holds the boot open and the fixture talks to it by latch. Here that thread lives in
 * {@link RuntimeBootHarness} and the engine slots in {@link SelectedEngines}, leaving this class the
 * lifecycle and the configuration only.
 *
 * <p>The testkit never imports a Community type. Selection goes through {@link BootstrapSelector} and
 * the engines come out of {@link KernelProviders}, so this class compiles against SPI and Core alone
 * while booting whatever providers the consumer's classpath supplies.
 */
final class KernelBootstrapRuntimeFixture implements EmbeddedKernelFixture {

    private static final String JDBC_URL_PROPERTY = "exeris.persistence.jdbcUrl";
    private static final String RUN_MIGRATIONS_PROPERTY = "exeris.persistence.runMigrations";
    private static final long START_TIMEOUT_SECONDS = 30L;
    private static final long STOP_TIMEOUT_SECONDS = 15L;

    private final Set<String> subsystems;
    private final String jdbcUrl;
    private final boolean runMigrations;

    private final SelectedEngines engines;
    private final RuntimeBootHarness harness = new RuntimeBootHarness(
            "kernel-bootstrap-runtime-fixture", START_TIMEOUT_SECONDS, STOP_TIMEOUT_SECONDS);
    /** Claimed by the first {@link #start()}; {@code started} only flips once the boot succeeded. */
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);

    /* default */ KernelBootstrapRuntimeFixture(Set<String> subsystems,
                                                String jdbcUrl,
                                                boolean runMigrations) {
        this.subsystems = Set.copyOf(Objects.requireNonNull(subsystems, "subsystems must not be null"));
        this.engines = new SelectedEngines(this.subsystems);
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        this.runMigrations = runMigrations;
    }

    @Override
    public void start() {
        if (!starting.compareAndSet(false, true)) {
            throw new IllegalStateException("Fixture is already started");
        }
        boolean booted = false;
        try {
            FixtureBootLock.bootExclusively(this::bootAndAwaitStart);
            booted = true;
        } finally {
            // Release the claim on failure, so a fixture that could not boot is not wedged shut.
            starting.set(booted);
        }
        started.set(true);
    }

    @Override
    public EventEngine eventEngine() {
        requireStarted();
        return engines.eventEngine();
    }

    @Override
    public FlowEngine flowEngine() {
        requireStarted();
        return engines.flowEngine();
    }

    @Override
    public PersistenceEngine persistenceEngine() {
        requireStarted();
        return engines.persistenceEngine();
    }

    @Override
    public String jdbcUrl() {
        requireStarted();
        return jdbcUrl;
    }

    @Override
    public void runInKernelScope(Runnable body) {
        Objects.requireNonNull(body, "body must not be null");
        requireStarted();
        harness.submit(body);
    }

    @Override
    public boolean isRunning() {
        return started.get() && engines.isBound();
    }

    @Override
    public void close() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        harness.shutdown();
        engines.release();
        starting.set(false);
    }

    /**
     * Publishes configuration and boots, returning once the engines are bound or the attempt failed.
     *
     * <p>Runs under {@link FixtureBootLock}: the properties are JVM-global and the kernel reads them
     * uncached during subsystem initialisation, so no other fixture may boot while this is in flight.
     */
    private void bootAndAwaitStart() {
        SystemPropertySnapshot snapshot =
                SystemPropertySnapshot.capture(JDBC_URL_PROPERTY, RUN_MIGRATIONS_PROPERTY);
        harness.bootAndAwait(
                () -> runFixtureRuntime(snapshot),
                "Kernel runtime fixture failed to start — are the providers for " + subsystems
                        + " and a JDBC driver on the test classpath?");
    }

    private void runFixtureRuntime(SystemPropertySnapshot snapshot) {
        try {
            System.setProperty(JDBC_URL_PROPERTY, jdbcUrl);
            System.setProperty(RUN_MIGRATIONS_PROPERTY, Boolean.toString(runMigrations));

            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames(subsystems.toArray(new String[0])))
                    .build()
                    .boot(() -> {
                        engines.captureFromKernelScope();
                        harness.signalStarted();
                        harness.park();
                    });
        } catch (KernelBootstrap.BootstrapException bootstrapException) {
            harness.signalFailed(bootstrapException);
        } finally {
            snapshot.restore();
        }
    }

    private void requireStarted() {
        if (!started.get()) {
            throw new IllegalStateException("Fixture has not been started");
        }
    }
}
