/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor;
import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor.SubsystemState;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Community-side background watcher that turns a live subsystem's post-boot health into the Core
 * {@link KernelHealthMonitor}'s reversible {@link SubsystemState#DEGRADED}/{@link SubsystemState#RUNNING}
 * axis, so readiness drops (and the load balancer drains the instance) when a dependency dies after
 * boot — and recovers when it returns.
 *
 * <h2>The Wall</h2>
 * <p>This lives in Community, not Core: it knows the <em>concrete</em> Community subsystems and their
 * health primitives (e.g. {@code CommunityPersistenceEngine#canServiceRequest()}), wired in as a
 * {@link HealthSource} per subsystem. It only pushes state through the existing public
 * {@code markSubsystemState} — no generic subsystem-health method is added to the SPI (that is a v0.10
 * decision). Core stays driver-agnostic.
 *
 * <h2>State discipline</h2>
 * <p>The watcher transitions <strong>only</strong> {@code RUNNING ↔ DEGRADED}. It never touches a
 * subsystem still booting ({@code REGISTERED}/{@code INITIALIZED}), nor a terminal
 * {@code FAILED}/{@code STOPPED} one — so it cannot resurrect a failed subsystem or race the boot DAG.
 */
public final class CommunitySubsystemHealthWatcher {

    private static final System.Logger LOG =
            System.getLogger(CommunitySubsystemHealthWatcher.class.getName());

    private final KernelHealthMonitor monitor;
    private final Map<String, HealthSource> sources = new ConcurrentHashMap<>();
    private final long intervalNanos;
    private final AtomicBoolean running = new AtomicBoolean(false);
    @SuppressWarnings("java:S3077") // safe publication; the referent owns its thread-safety
    private volatile Thread thread;

    public CommunitySubsystemHealthWatcher(KernelHealthMonitor monitor, long intervalNanos) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        if (intervalNanos <= 0) {
            throw new IllegalArgumentException("intervalNanos must be positive");
        }
        this.intervalNanos = intervalNanos;
    }

    /** Registers a concrete subsystem's health source under its monitor name. */
    public void register(String subsystemName, HealthSource source) {
        Objects.requireNonNull(subsystemName, "subsystemName");
        Objects.requireNonNull(source, "source");
        sources.put(subsystemName, source);
    }

    /** Starts the background poll loop on a dedicated platform thread. Idempotent. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread loop = Thread.ofPlatform()
                .name("kernel/health-watcher")
                .daemon(true)   // background infra thread — must never block JVM exit if stop() is skipped
                .unstarted(this::runLoop);
        this.thread = loop;
        loop.start();
    }

    /** Stops the poll loop and joins the thread (best effort). Idempotent. */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread loop = this.thread;
        if (loop != null) {
            LockSupport.unpark(loop);
            try {
                loop.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(1));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop() {
        while (running.get()) {
            pollOnce();
            LockSupport.parkNanos(intervalNanos);
        }
    }

    /**
     * Single poll pass: reconcile each source's health against the monitor's tracked state, flipping
     * only {@code RUNNING ↔ DEGRADED}. Package-private so the contract is testable without the loop.
     */
    /* default */ void pollOnce() {
        for (Map.Entry<String, HealthSource> entry : sources.entrySet()) {
            String name = entry.getKey();
            SubsystemState current = monitor.stateOf(name);
            if (current != SubsystemState.RUNNING && current != SubsystemState.DEGRADED) {
                continue;
            }
            boolean healthy = healthyQuietly(entry.getValue(), name);
            if (current == SubsystemState.RUNNING && !healthy) {
                monitor.markSubsystemState(name, SubsystemState.DEGRADED);
            } else if (current == SubsystemState.DEGRADED && healthy) {
                monitor.markSubsystemState(name, SubsystemState.RUNNING);
            }
        }
    }

    // A misbehaving health probe must not crash the watcher loop or other subsystems' reconciliation;
    // any throw is itself an impairment signal, so the broad catch is the resilience boundary here.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static boolean healthyQuietly(HealthSource source, String name) {
        try {
            return source.healthy();
        } catch (RuntimeException ex) {
            // A health probe that throws is itself an impairment signal — treat as not healthy.
            LOG.log(System.Logger.Level.DEBUG, () -> "health source threw for subsystem " + name, ex);
            return false;
        }
    }

    /** Health of one concrete subsystem; {@code true} = serving, {@code false} = impaired. */
    @FunctionalInterface
    public interface HealthSource {
        boolean healthy();
    }
}
