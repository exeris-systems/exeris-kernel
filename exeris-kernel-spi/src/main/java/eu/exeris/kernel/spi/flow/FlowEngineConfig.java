/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Immutable configuration for the {@link FlowEngine}.
 *
 * <h2>Design</h2>
 * <p>All configuration is expressed via primitives and {@link String} keys —
 * no references to implementation-specific classes. Both Community and Enterprise
 * implementations read only the fields they need; unknown fields are ignored.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Purely primitive fields with {@code String} keys limited to bootstrap path.
 * No identity operations ({@code ==}, {@code synchronized}, {@code identityHashCode}).
 * Ready for {@code value record} when JEP 401 is stable.
 *
 * @param engineName             human-readable engine name (for JFR / logging)
 * @param maxConcurrentFlows     maximum number of simultaneously active flow instances
 * @param timeoutDurationNanos   default flow duration limit in nanoseconds; basis for computing
 *                               the absolute {@link eu.exeris.kernel.spi.flow.model.FlowContext#timeoutNanos()}
 * @param maxSteps               maximum number of distinct step types (Enterprise: slab sizing)
 * @param maxTransitions         maximum number of registered transitions (Enterprise: slab sizing)
 * @param maxExecutionPlans      maximum number of compiled execution plans (Enterprise: slab sizing)
 * @param schedulerQueueCapacity ring buffer capacity for the flow scheduler;
 *                               <b>must be a power of 2 when {@code partitionBytes > 0}</b>
 *                               (Enterprise lock-free ring buffer uses bitmask reduction
 *                               {@code index & (capacity - 1)} instead of modulo);
 *                               any positive value is accepted for Community ({@code partitionBytes = 0})
 * @param partitionName          memory partition name for Enterprise off-heap allocation
 * @param partitionBytes         total bytes to claim for the flow memory partition (Enterprise)
 * @param persistenceEnabled     whether flow snapshot persistence via SPI is enabled
 * @param compensationEnabled    whether backward compensation (saga rollback) is enabled
 * @param terminalCatalogMaxSize maximum number of entries retained in the in-memory terminal-state
 *                               catalog used as the idempotency fence for COMPLETED /
 *                               FAILED_ROLLEDBACK resubmits; {@code 0} = unbounded (default,
 *                               backward-compatible). When {@code > 0}, the catalog evicts the
 *                               least-recently-accessed entry; the engine then falls back to the
 *                               durable {@link eu.exeris.kernel.spi.flow.model.FlowSnapshotStore}
 *                               (when {@code persistenceEnabled = true}) to honor the idempotency
 *                               fence — see {@code docs/subsystems/flow.md}
 *                               §Terminal-State Catalog Retention. (since 0.7.0)
 * @param defaultSagaTimeoutShortNanos baseline timeout for short-lived (transactional) sagas;
 *                                     {@link #DEFAULT_SAGA_TIMEOUT_SHORT_NANOS} = 30 s. Exposed for
 *                                     callers that classify saga lifetimes when constructing a
 *                                     {@link eu.exeris.kernel.spi.flow.model.FlowContext}; the engine
 *                                     itself enforces whatever {@code timeoutNanos} the context carries.
 *                                     (since 0.7.0)
 * @param defaultSagaTimeoutLongNanos  baseline timeout for long-running (business-process) sagas;
 *                                     {@link #DEFAULT_SAGA_TIMEOUT_LONG_NANOS} = 30 days. Exposed for
 *                                     callers that classify saga lifetimes when constructing a
 *                                     {@link eu.exeris.kernel.spi.flow.model.FlowContext}.
 *                                     (since 0.7.0)
 *
 * @since 0.5
 */
@SuppressWarnings("PMD.ExcessiveParameterList") // 14 fields — flat-record config carrier;
public record FlowEngineConfig(
        String  engineName,
        int     maxConcurrentFlows,
        long    timeoutDurationNanos,
        int     maxSteps,
        int     maxTransitions,
        int     maxExecutionPlans,
        int     schedulerQueueCapacity,
        String  partitionName,
        long    partitionBytes,
        boolean persistenceEnabled,
        boolean compensationEnabled,
        int     terminalCatalogMaxSize,
        long    defaultSagaTimeoutShortNanos,
        long    defaultSagaTimeoutLongNanos
) {

    /** Default short-lived (transactional) saga timeout — 30 seconds. @since 0.7 */
    public static final long DEFAULT_SAGA_TIMEOUT_SHORT_NANOS = TimeUnit.SECONDS.toNanos(30L);

    /** Default long-running (business-process) saga timeout — 30 days. @since 0.7 */
    public static final long DEFAULT_SAGA_TIMEOUT_LONG_NANOS = TimeUnit.DAYS.toNanos(30L);


    /**
     * Validates every bound at construction, so a misconfigured engine fails during bootstrap
     * rather than at the first flow that trips over the field.
     *
     * @throws NullPointerException     if {@code engineName} is {@code null}
     * @throws IllegalArgumentException if {@code engineName} is blank, if any bounded field is
     *                                  outside its range, if {@code schedulerQueueCapacity} is not
     *                                  a power of 2 while {@code partitionBytes > 0}, if
     *                                  {@code partitionName} is absent while
     *                                  {@code partitionBytes > 0}, or if
     *                                  {@code defaultSagaTimeoutLongNanos} is below
     *                                  {@code defaultSagaTimeoutShortNanos}
     */
    public FlowEngineConfig {
        Objects.requireNonNull(engineName, "engineName must not be null");
        if (engineName.isBlank()) {
            throw new IllegalArgumentException("engineName must not be blank");
        }
        if (maxConcurrentFlows <= 0) {
            throw new IllegalArgumentException("maxConcurrentFlows must be > 0, got: " + maxConcurrentFlows);
        }
        if (timeoutDurationNanos <= 0) {
            throw new IllegalArgumentException("timeoutDurationNanos must be > 0, got: " + timeoutDurationNanos);
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be > 0, got: " + maxSteps);
        }
        if (maxTransitions < 0) {
            throw new IllegalArgumentException("maxTransitions must be >= 0, got: " + maxTransitions);
        }
        if (maxExecutionPlans <= 0) {
            throw new IllegalArgumentException("maxExecutionPlans must be > 0, got: " + maxExecutionPlans);
        }
        if (schedulerQueueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "schedulerQueueCapacity must be > 0, got: " + schedulerQueueCapacity);
        }
        // Enterprise lock-free ring buffer requires a power-of-2 capacity so that
        // the modulo reduction can be replaced by a bitmask (index & (capacity - 1)).
        // Community does not use a ring buffer, so any positive value is accepted there.
        if (partitionBytes > 0 && (schedulerQueueCapacity & (schedulerQueueCapacity - 1)) != 0) {
            throw new IllegalArgumentException(
                    "schedulerQueueCapacity must be a power of 2 when partitionBytes > 0 "
                    + "(Enterprise ring buffer), got: " + schedulerQueueCapacity);
        }
        validatePartition(partitionName, partitionBytes);
        if (terminalCatalogMaxSize < 0) {
            throw new IllegalArgumentException(
                    "terminalCatalogMaxSize must be >= 0, got: " + terminalCatalogMaxSize);
        }
        if (defaultSagaTimeoutShortNanos <= 0) {
            throw new IllegalArgumentException(
                    "defaultSagaTimeoutShortNanos must be > 0, got: " + defaultSagaTimeoutShortNanos);
        }
        if (defaultSagaTimeoutLongNanos <= 0) {
            throw new IllegalArgumentException(
                    "defaultSagaTimeoutLongNanos must be > 0, got: " + defaultSagaTimeoutLongNanos);
        }
        if (defaultSagaTimeoutLongNanos < defaultSagaTimeoutShortNanos) {
            throw new IllegalArgumentException(
                    "defaultSagaTimeoutLongNanos (" + defaultSagaTimeoutLongNanos
                    + ") must be >= defaultSagaTimeoutShortNanos (" + defaultSagaTimeoutShortNanos + ')');
        }
    }

    /**
     * Builds a configuration from the eleven fields a caller has to decide, leaving the three
     * retention and saga-timeout fields at their defaults: {@code terminalCatalogMaxSize = 0}
     * (unbounded terminal catalog), {@link #DEFAULT_SAGA_TIMEOUT_SHORT_NANOS} and
     * {@link #DEFAULT_SAGA_TIMEOUT_LONG_NANOS}.
     *
     * @param engineName             human-readable engine name (for JFR / logging); must not be
     *                               {@code null} or blank
     * @param maxConcurrentFlows     maximum number of simultaneously active flow instances;
     *                               must be &gt; 0
     * @param timeoutDurationNanos   default flow duration limit in nanoseconds; must be &gt; 0
     * @param maxSteps               maximum number of distinct step types (Enterprise: slab
     *                               sizing); must be &gt; 0
     * @param maxTransitions         maximum number of registered transitions (Enterprise: slab
     *                               sizing); must be &gt;= 0
     * @param maxExecutionPlans      maximum number of compiled execution plans retained by the plan
     *                               catalog, each definition version counting as one; must be &gt; 0
     * @param schedulerQueueCapacity ring buffer capacity for the flow scheduler; must be &gt; 0, and
     *                               a power of 2 when {@code partitionBytes > 0}
     * @param partitionName          memory partition name for off-heap allocation; must not be
     *                               {@code null} or blank when {@code partitionBytes > 0}
     * @param partitionBytes         bytes to claim for the flow memory partition; must be &gt;= 0,
     *                               and {@code 0} selects the heap-only path
     * @param persistenceEnabled     whether flow snapshot persistence via SPI is enabled
     * @param compensationEnabled    whether backward compensation (saga rollback) is enabled
     * @throws NullPointerException     if {@code engineName} is {@code null}
     * @throws IllegalArgumentException if any other bound above is violated
     * @since 0.7
     */
    public FlowEngineConfig(
            String  engineName,
            int     maxConcurrentFlows,
            long    timeoutDurationNanos,
            int     maxSteps,
            int     maxTransitions,
            int     maxExecutionPlans,
            int     schedulerQueueCapacity,
            String  partitionName,
            long    partitionBytes,
            boolean persistenceEnabled,
            boolean compensationEnabled
    ) {
        this(engineName, maxConcurrentFlows, timeoutDurationNanos, maxSteps, maxTransitions,
             maxExecutionPlans, schedulerQueueCapacity, partitionName, partitionBytes,
             persistenceEnabled, compensationEnabled,
             0,
             DEFAULT_SAGA_TIMEOUT_SHORT_NANOS,
             DEFAULT_SAGA_TIMEOUT_LONG_NANOS);
    }

    private static void validatePartition(String name, long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("partitionBytes must be >= 0, got: " + bytes);
        }
        if (bytes > 0 && (name == null || name.isBlank())) {
            throw new IllegalArgumentException(
                    "partitionName must not be null or blank when partitionBytes > 0");
        }
    }

    /**
     * Produces a heap-only baseline: no off-heap partition ({@code partitionBytes = 0},
     * {@code partitionName = null}), snapshot persistence off, compensation on, an unbounded
     * terminal-state catalog, and both saga-timeout defaults at their constants.
     *
     * @param engineName human-readable engine name (for JFR / logging); must not be {@code null}
     *                   or blank
     * @return a configuration whose every field is set for the heap path; never {@code null}
     * @throws NullPointerException     if {@code engineName} is {@code null}
     * @throws IllegalArgumentException if {@code engineName} is blank
     * @apiNote Persistence is off here, so cross-restart choreography wake is unsupported by
     *          contract until the caller enables it. Operators sizing an off-heap deployment start
     *          from {@link #enterpriseDefaults(String)} instead and override {@code partitionBytes}
     *          and the slab-sizing fields.
     */
    public static FlowEngineConfig defaults(String engineName) {
        return new FlowEngineConfig(
                engineName,
                10_000,
                300_000_000_000L,   // 5 minutes duration in nanoseconds
                256,
                4_096,
                1_024,
                65_536,
                null,               // no off-heap partition — Community heap path
                0L,                 // partitionBytes=0 → heap-only
                false,              // persistence disabled by default for Community
                true,
                0,                  // unbounded terminal catalog (backward-compatible default)
                DEFAULT_SAGA_TIMEOUT_SHORT_NANOS,
                DEFAULT_SAGA_TIMEOUT_LONG_NANOS
        );
    }

    /**
     * Produces an off-heap baseline: a 32 MB partition named {@code "flow"}, snapshot persistence
     * and compensation on, and a terminal-state catalog bounded at 100 000 entries — the bounded
     * mode, which is why persistence is enabled with it.
     *
     * @param engineName human-readable engine name (for JFR / logging); must not be {@code null}
     *                   or blank
     * @return a configuration whose every field is set for the off-heap path; never {@code null}
     * @throws NullPointerException     if {@code engineName} is {@code null}
     * @throws IllegalArgumentException if {@code engineName} is blank
     * @apiNote Tune {@code partitionBytes}, {@code maxSteps} and {@code schedulerQueueCapacity} to
     *          the deployment's density target before using this in production. Keep
     *          {@code persistenceEnabled} on while {@code terminalCatalogMaxSize > 0}: an evicted
     *          catalog entry has to stay recoverable from the snapshot store for the resubmit fence
     *          to hold.
     */
    public static FlowEngineConfig enterpriseDefaults(String engineName) {
        return new FlowEngineConfig(
                engineName,
                10_000,
                300_000_000_000L,   // 5 minutes duration in nanoseconds
                256,
                4_096,
                1_024,
                65_536,
                "flow",
                32L * 1024 * 1024,  // 32 MB off-heap partition
                true,
                true,
                100_000,            // bounded LRU terminal catalog — Enterprise long-running runtime
                DEFAULT_SAGA_TIMEOUT_SHORT_NANOS,
                DEFAULT_SAGA_TIMEOUT_LONG_NANOS
        );
    }
}

