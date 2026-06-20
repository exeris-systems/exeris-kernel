/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor;
import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor.SubsystemState;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link CommunitySubsystemHealthWatcher} reconciliation + lifecycle. */
class CommunitySubsystemHealthWatcherTest {

    private static final long INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

    @Test
    @DisplayName("RUNNING subsystem with an unhealthy source is marked DEGRADED")
    void runningUnhealthyBecomesDegraded() {
        KernelHealthMonitor monitor = new KernelHealthMonitor();
        monitor.registerSubsystem("persistence", true);
        monitor.markSubsystemState("persistence", SubsystemState.RUNNING);

        CommunitySubsystemHealthWatcher watcher = new CommunitySubsystemHealthWatcher(monitor, INTERVAL_NANOS);
        watcher.register("persistence", () -> false);

        watcher.pollOnce();

        assertThat(monitor.stateOf("persistence")).isEqualTo(SubsystemState.DEGRADED);
    }

    @Test
    @DisplayName("DEGRADED subsystem with a recovered source is marked RUNNING again (reversible)")
    void degradedHealthyRecoversToRunning() {
        KernelHealthMonitor monitor = new KernelHealthMonitor();
        monitor.registerSubsystem("persistence", true);
        monitor.markSubsystemState("persistence", SubsystemState.RUNNING);

        AtomicBoolean healthy = new AtomicBoolean(false);
        CommunitySubsystemHealthWatcher watcher = new CommunitySubsystemHealthWatcher(monitor, INTERVAL_NANOS);
        watcher.register("persistence", healthy::get);

        watcher.pollOnce();
        assertThat(monitor.stateOf("persistence")).isEqualTo(SubsystemState.DEGRADED);

        healthy.set(true);
        watcher.pollOnce();
        assertThat(monitor.stateOf("persistence")).isEqualTo(SubsystemState.RUNNING);
    }

    @Test
    @DisplayName("FAILED subsystem is never resurrected by the watcher")
    void failedSubsystemNotResurrected() {
        KernelHealthMonitor monitor = new KernelHealthMonitor();
        monitor.registerSubsystem("persistence", true);
        monitor.markSubsystemState("persistence", SubsystemState.FAILED);

        CommunitySubsystemHealthWatcher watcher = new CommunitySubsystemHealthWatcher(monitor, INTERVAL_NANOS);
        watcher.register("persistence", () -> true);

        watcher.pollOnce();

        assertThat(monitor.stateOf("persistence")).isEqualTo(SubsystemState.FAILED);
    }

    @Test
    @DisplayName("A health source that throws is treated as impaired (DEGRADED)")
    void throwingSourceTreatedAsUnhealthy() {
        KernelHealthMonitor monitor = new KernelHealthMonitor();
        monitor.registerSubsystem("events", true);
        monitor.markSubsystemState("events", SubsystemState.RUNNING);

        CommunitySubsystemHealthWatcher watcher = new CommunitySubsystemHealthWatcher(monitor, INTERVAL_NANOS);
        watcher.register("events", () -> {
            throw new IllegalStateException("broker unreachable");
        });

        watcher.pollOnce();

        assertThat(monitor.stateOf("events")).isEqualTo(SubsystemState.DEGRADED);
    }

    @Test
    @DisplayName("background loop reconciles state and stops cleanly")
    void backgroundLoopReconcilesAndStops() {
        KernelHealthMonitor monitor = new KernelHealthMonitor();
        monitor.registerSubsystem("persistence", true);
        monitor.markSubsystemState("persistence", SubsystemState.RUNNING);

        AtomicBoolean healthy = new AtomicBoolean(false);
        CommunitySubsystemHealthWatcher watcher = new CommunitySubsystemHealthWatcher(monitor, INTERVAL_NANOS);
        watcher.register("persistence", healthy::get);

        watcher.start();
        try {
            awaitState(monitor, "persistence", SubsystemState.DEGRADED);
            healthy.set(true);
            awaitState(monitor, "persistence", SubsystemState.RUNNING);
        } finally {
            watcher.stop();
        }

        assertThat(monitor.stateOf("persistence")).isEqualTo(SubsystemState.RUNNING);
    }

    @Test
    @DisplayName("RUNNING→DEGRADED flip emits a SubsystemHealthTransition JFR event")
    void degradedTransitionEmitsJfrEvent() throws InterruptedException {
        KernelHealthMonitor monitor = new KernelHealthMonitor();
        monitor.registerSubsystem("persistence", true);
        monitor.markSubsystemState("persistence", SubsystemState.RUNNING);

        CommunitySubsystemHealthWatcher watcher = new CommunitySubsystemHealthWatcher(monitor, INTERVAL_NANOS);
        watcher.register("persistence", () -> false);

        CountDownLatch recorded = new CountDownLatch(1);
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();
        try (RecordingStream stream = new RecordingStream()) {
            stream.enable("eu.exeris.kernel.bootstrap.SubsystemHealthTransition");
            stream.onEvent("eu.exeris.kernel.bootstrap.SubsystemHealthTransition", event -> {
                captured.set(event);
                recorded.countDown();
            });
            stream.startAsync();

            watcher.pollOnce(); // RUNNING → DEGRADED

            assertThat(recorded.await(5, TimeUnit.SECONDS))
                    .as("a DEGRADED transition must leave a JFR trail (ADR-005)").isTrue();
        }

        RecordedEvent event = captured.get();
        assertThat(event.getString("subsystemName")).isEqualTo("persistence");
        assertThat(event.getString("fromState")).isEqualTo("RUNNING");
        assertThat(event.getString("toState")).isEqualTo("DEGRADED");
    }

    private static void awaitState(KernelHealthMonitor monitor, String name, SubsystemState expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (monitor.stateOf(name) != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(monitor.stateOf(name)).as("state of %s within deadline", name).isEqualTo(expected);
    }
}
