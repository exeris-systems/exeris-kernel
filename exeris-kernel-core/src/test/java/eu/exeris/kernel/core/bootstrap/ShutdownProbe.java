/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A subsystem that counts its lifecycle calls and runs a configurable action inside each, so a test can
 * hold the boot or the stop at a chosen point without sleeping.
 */
final class ShutdownProbe implements Subsystem {

    private static final Runnable NO_ACTION = () -> { };

    private final String name;
    private final BootstrapPhase phase;
    private final List<String> dependsOn;
    private final List<String> stopLog;
    private final AtomicInteger initializeCount = new AtomicInteger();
    private final AtomicInteger startCount = new AtomicInteger();
    private final AtomicInteger stopCount = new AtomicInteger();
    private volatile boolean running;
    private volatile Runnable onInitialize = NO_ACTION;
    private volatile Runnable onStart = NO_ACTION;
    private volatile Runnable onStop = NO_ACTION;

    /**
     * Creates a probe.
     *
     * @param name      subsystem name
     * @param phase     bootstrap phase
     * @param stopLog   list that receives this probe's name when its {@code stop()} begins; may be {@code null}
     * @param dependsOn names of the subsystems this one depends on
     */
    ShutdownProbe(String name, BootstrapPhase phase, List<String> stopLog, String... dependsOn) {
        this.name = name;
        this.phase = phase;
        this.stopLog = stopLog;
        this.dependsOn = List.of(dependsOn);
    }

    ShutdownProbe onInitialize(Runnable action) {
        this.onInitialize = action;
        return this;
    }

    ShutdownProbe onStart(Runnable action) {
        this.onStart = action;
        return this;
    }

    ShutdownProbe onStop(Runnable action) {
        this.onStop = action;
        return this;
    }

    int initializeCount() {
        return initializeCount.get();
    }

    int startCount() {
        return startCount.get();
    }

    int stopCount() {
        return stopCount.get();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<String> dependsOn() {
        return dependsOn;
    }

    @Override
    public BootstrapPhase phase() {
        return phase;
    }

    @Override
    public void initialize() {
        initializeCount.incrementAndGet();
        onInitialize.run();
    }

    @Override
    public void start() {
        startCount.incrementAndGet();
        onStart.run();
        running = true;
    }

    @Override
    public void stop() {
        stopCount.incrementAndGet();
        if (stopLog != null) {
            stopLog.add(name);
        }
        onStop.run();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
