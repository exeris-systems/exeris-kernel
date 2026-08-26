/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testkit.persistence;

import eu.exeris.kernel.community.testkit.FixtureBootLock;
import eu.exeris.kernel.community.testkit.FixtureThreads;
import eu.exeris.kernel.community.testkit.SystemPropertySnapshot;
import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link EmbeddedPersistenceEngineFixture} backed by a real {@link KernelBootstrap} run.
 *
 * <p>Deliberately the same shape as {@code KernelBootstrapHttpEngineFixture}: the kernel scope is a
 * {@code ScopedValue} binding and therefore cannot outlive the frame that opened it, so a dedicated
 * thread holds the boot open and the fixture communicates with it by latch. That is also what makes
 * {@link #runInKernelScope(Runnable)} possible — the scope exists on exactly one thread, and work has
 * to be carried to it rather than the other way round (see {@link KernelScopePump}).
 *
 * <p>The testkit never imports a Community type. Selection goes through {@link BootstrapSelector} and
 * the engine comes out of {@link KernelProviders}, so this class compiles against SPI and Core alone
 * while booting whatever provider the consumer's classpath supplies.
 */
final class KernelBootstrapPersistenceEngineFixture implements EmbeddedPersistenceEngineFixture {

    private static final String JDBC_URL_PROPERTY = "exeris.persistence.jdbcUrl";
    private static final String RUN_MIGRATIONS_PROPERTY = "exeris.persistence.runMigrations";
    private static final long START_TIMEOUT_SECONDS = 30L;
    private static final long STOP_TIMEOUT_SECONDS = 15L;

    private final String jdbcUrl;
    private final boolean runMigrations;

    private final AtomicReference<PersistenceEngine> engine = new AtomicReference<>();
    /** Claimed by the first {@link #start()}; {@code started} only flips once the boot succeeded. */
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final KernelScopePump pump = new KernelScopePump();

    private Thread runtimeThread;

    /* default */ KernelBootstrapPersistenceEngineFixture(String jdbcUrl, boolean runMigrations) {
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

    /**
     * Publishes configuration, starts the runtime thread, and returns once it has booted or failed.
     *
     * <p>Runs under {@link FixtureBootLock}: the properties are JVM-global and the kernel reads them
     * uncached during subsystem initialisation, so no other fixture may boot while this is in flight.
     */
    private void bootAndAwaitStart() {
        SystemPropertySnapshot snapshot =
                SystemPropertySnapshot.capture(JDBC_URL_PROPERTY, RUN_MIGRATIONS_PROPERTY);
        CountDownLatch startedSignal = new CountDownLatch(1);
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();

        Thread thread = Thread.ofPlatform()
                .name("kernel-bootstrap-persistence-fixture")
                .uncaughtExceptionHandler((_, throwable) -> {
                    startupFailure.compareAndSet(null, throwable);
                    startedSignal.countDown();
                })
                .start(() -> runFixtureRuntime(snapshot, startedSignal, startupFailure));

        awaitStart(thread, startedSignal, startupFailure);
        runtimeThread = thread;
    }

    @Override
    public PersistenceEngine engine() {
        PersistenceEngine current = engine.get();
        if (!started.get() || current == null) {
            throw new IllegalStateException("Fixture has not been started");
        }
        return current;
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
        pump.submitAndWait(body, START_TIMEOUT_SECONDS);
    }

    @Override
    public boolean isRunning() {
        return started.get() && engine.get() != null;
    }

    @Override
    public void close() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        pump.requestStop();
        FixtureThreads.joinQuietly(runtimeThread, STOP_TIMEOUT_SECONDS);
        engine.set(null);
        starting.set(false);
    }

    private void requireStarted() {
        if (!started.get()) {
            throw new IllegalStateException("Fixture has not been started");
        }
    }

    private void runFixtureRuntime(SystemPropertySnapshot snapshot,
                                   CountDownLatch startedSignal,
                                   AtomicReference<Throwable> startupFailure) {
        try {
            System.setProperty(JDBC_URL_PROPERTY, jdbcUrl);
            System.setProperty(RUN_MIGRATIONS_PROPERTY, Boolean.toString(runMigrations));

            KernelBootstrap bootstrap = KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("persistence"))
                    .build();

            bootstrap.boot(() -> {
                engine.set(KernelProviders.persistenceEngine());
                startedSignal.countDown();
                pump.pumpUntilStopped();
            });
        } catch (KernelBootstrap.BootstrapException bootstrapException) {
            startupFailure.compareAndSet(null, bootstrapException);
            startedSignal.countDown();
        } finally {
            snapshot.restore();
        }
    }

    private void awaitStart(Thread thread,
                            CountDownLatch startedSignal,
                            AtomicReference<Throwable> startupFailure) {
        try {
            if (!startedSignal.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw abandon(thread, "Timed out while starting kernel persistence fixture", null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw abandon(thread, "Interrupted while starting kernel persistence fixture", interrupted);
        }

        Throwable failure = startupFailure.get();
        if (failure != null) {
            throw abandon(thread,
                    "Kernel persistence fixture failed to start — is a PersistenceProvider and a JDBC "
                            + "driver on the test classpath?", failure);
        }
    }

    /** Releases a fixture thread that will never be adopted, and describes why. */
    private IllegalStateException abandon(Thread thread, String message, Throwable cause) {
        pump.requestStop();
        FixtureThreads.joinQuietly(thread, STOP_TIMEOUT_SECONDS);
        return new IllegalStateException(message, cause);
    }
}
