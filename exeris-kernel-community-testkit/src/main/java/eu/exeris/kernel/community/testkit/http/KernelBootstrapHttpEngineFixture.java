/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.testkit.http;

import eu.exeris.kernel.community.testkit.FixtureBootLock;
import eu.exeris.kernel.community.testkit.FixtureThreads;
import eu.exeris.kernel.community.testkit.SystemPropertySnapshot;
import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpServerEngine;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KernelBootstrap-based fixture that keeps HTTP running until explicitly closed.
 */
@SuppressWarnings("PMD.AvoidUsingHardCodedIP")
public final class KernelBootstrapHttpEngineFixture implements EmbeddedHttpEngineFixture {

    private static final long START_TIMEOUT_SECONDS = 10L;
    private static final long STOP_TIMEOUT_SECONDS = 10L;
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final int UNBOUND_PORT = -1;

    private final Object lifecycleLock = new Object();

    private final AtomicReference<HttpServerEngine> engine = new AtomicReference<>();
    private final AtomicInteger boundPort = new AtomicInteger(UNBOUND_PORT);
    private final AtomicBoolean started = new AtomicBoolean(false);

    private CountDownLatch stopSignal;
    private Thread runtimeThread;

    @Override
    public void start(HttpHandler handler) {
        Objects.requireNonNull(handler, "handler must not be null");

        synchronized (lifecycleLock) {
            if (started.get()) {
                throw new IllegalStateException("Fixture is already started");
            }

            FixtureBootLock.bootExclusively(() -> bootAndAwaitStart(handler));
        }
    }

    /**
     * Publishes configuration, starts the runtime thread, and returns once it has booted or failed.
     *
     * <p>Runs under {@link FixtureBootLock}: the properties are JVM-global and the kernel reads them
     * uncached during subsystem initialisation, so no other fixture may boot while this is in flight.
     */
    private void bootAndAwaitStart(HttpHandler handler) {
        int reservedPort = reserveLoopbackPort();
        SystemPropertySnapshot propertySnapshot = SystemPropertySnapshot.capture(
                "exeris.http.mode",
                "exeris.http.bindHost",
                "exeris.http.port",
                "http.mode",
                "http.bindHost",
                "http.port");

        CountDownLatch startedSignal = new CountDownLatch(1);
        CountDownLatch stop = new CountDownLatch(1);
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();

        Thread thread = Thread.ofPlatform()
                .name("kernel-bootstrap-http-fixture")
                .uncaughtExceptionHandler((_, throwable) -> {
                    startupFailure.compareAndSet(null, throwable);
                    startedSignal.countDown();
                })
                .start(() -> runFixtureRuntime(
                        handler,
                        reservedPort,
                        propertySnapshot,
                        startedSignal,
                        stop,
                        startupFailure));

        try {
            if (!startedSignal.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                stop.countDown();
                FixtureThreads.joinQuietly(thread, STOP_TIMEOUT_SECONDS);
                throw new IllegalStateException("Timed out while starting kernel HTTP fixture");
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            stop.countDown();
            FixtureThreads.joinQuietly(thread, STOP_TIMEOUT_SECONDS);
            throw new IllegalStateException("Interrupted while starting kernel HTTP fixture",
                    interruptedException);
        }

        Throwable failure = startupFailure.get();
        if (failure != null) {
            stop.countDown();
            FixtureThreads.joinQuietly(thread, STOP_TIMEOUT_SECONDS);
            throw new IllegalStateException("Kernel HTTP fixture failed to start", failure);
        }

        runtimeThread = thread;
        stopSignal = stop;
        started.set(true);
    }

    @Override
    public HttpServerEngine engine() {
        HttpServerEngine currentEngine = engine.get();
        if (!started.get() || currentEngine == null) {
            throw new IllegalStateException("Fixture has not been started");
        }
        return currentEngine;
    }

    @Override
    public int boundPort() {
        int currentBoundPort = boundPort.get();
        if (!started.get() || currentBoundPort < 0) {
            throw new IllegalStateException("Fixture has not been started");
        }
        return currentBoundPort;
    }

    @Override
    public boolean isRunning() {
        HttpServerEngine currentEngine = engine.get();
        return started.get() && currentEngine != null && currentEngine.isRunning();
    }

    @Override
    public void close() {
        Thread threadToJoin;
        CountDownLatch stopToSignal;

        synchronized (lifecycleLock) {
            if (!started.get()) {
                return;
            }
            stopToSignal = stopSignal;
            threadToJoin = runtimeThread;
        }

        if (stopToSignal != null) {
            stopToSignal.countDown();
        }
        FixtureThreads.joinQuietly(threadToJoin, STOP_TIMEOUT_SECONDS);

        synchronized (lifecycleLock) {
            started.set(false);
            boundPort.set(UNBOUND_PORT);
        }
    }

    private void runFixtureRuntime(HttpHandler handler,
                                   int reservedPort,
                                   SystemPropertySnapshot propertySnapshot,
                                   CountDownLatch startedSignal,
                                   CountDownLatch stop,
                                   AtomicReference<Throwable> startupFailure) {
        try {
            System.setProperty("exeris.http.mode", "SERVER");
            System.setProperty("exeris.http.bindHost", LOOPBACK_HOST);
            System.setProperty("exeris.http.port", Integer.toString(reservedPort));

            System.setProperty("http.mode", "SERVER");
            System.setProperty("http.bindHost", LOOPBACK_HOST);
            System.setProperty("http.port", Integer.toString(reservedPort));

            KernelBootstrap bootstrap = KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("http"))
                    .build();

            ScopedValue.where(HttpKernelProviders.HTTP_SERVER_HANDLER, handler)
                    .run(() -> {
                        try {
                            bootstrap.boot(() -> {
                                engine.set(HttpKernelProviders.httpServerEngine());
                                boundPort.set(reservedPort);
                                startedSignal.countDown();
                                awaitShutdownSignal(stop);
                            });
                        } catch (KernelBootstrap.BootstrapException bootstrapException) {
                            startupFailure.set(bootstrapException);
                            startedSignal.countDown();
                        }
                    });
        } finally {
            propertySnapshot.restore();
        }
    }

    private static void awaitShutdownSignal(CountDownLatch stop) {
        try {
            stop.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static int reserveLoopbackPort() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(LOOPBACK_HOST, 0));
            return socket.getLocalPort();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to reserve loopback HTTP port", exception);
        }
    }
}
