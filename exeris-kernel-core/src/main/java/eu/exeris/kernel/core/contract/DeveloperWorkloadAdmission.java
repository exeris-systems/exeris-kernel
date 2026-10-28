/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract;

import eu.exeris.kernel.core.contract.jfr.ContractViolationReporter;
import eu.exeris.kernel.spi.contract.ContractViolation;
import eu.exeris.kernel.spi.contract.EnforcementLevel;
import eu.exeris.kernel.spi.contract.WorkloadAdmission;
import eu.exeris.kernel.spi.contract.WorkloadEnvelope;
import eu.exeris.kernel.spi.time.TimeKernelProviders;
import eu.exeris.kernel.spi.time.TimeSource;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@link WorkloadAdmission} of a kernel running under a developer licence: it holds the
 * contract's {@link WorkloadEnvelope} at {@link EnforcementLevel#HARD}.
 *
 * <h2>Requests</h2>
 * <p>The generic cell rate algorithm admits {@code maxThroughputRps} requests per second. One
 * {@code long}, the theoretical arrival time, is the whole state and moves by compare-and-set. The
 * emission interval is {@code T = 1 s / maxThroughputRps}; the burst tolerance is {@code 1 s - T}, so a
 * quiet gate admits a full second's worth of requests at once and then one every {@code T}. A ceiling
 * of zero refuses every request.
 *
 * <h2>Connections</h2>
 * <p>A counter bounded by {@code maxConnections} moves by compare-and-set; a ceiling of zero refuses every
 * connection. A release never takes the counter below zero.
 *
 * <p>The first refusal of each kind is reported once through {@link ContractViolationReporter} at
 * {@link EnforcementLevel#HARD}; later refusals only refuse.
 *
 * <p><b>Allocation:</b> none on the admit and release paths; the single report per kind allocates.
 * <p><b>Thread confinement:</b> any thread; every state change is a compare-and-set.
 * <p><b>Ownership:</b> holds no resource.
 *
 * @since 0.13
 */
public final class DeveloperWorkloadAdmission implements WorkloadAdmission {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final String contractId;
    private final TimeSource clock;
    private final long maxConnections;
    private final boolean refuseAllRequests;
    private final long emissionInterval;
    private final long burstTolerance;
    private final AtomicLong theoreticalArrival;
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicBoolean requestRefusalReported = new AtomicBoolean();
    private final AtomicBoolean connectionRefusalReported = new AtomicBoolean();

    /**
     * Creates the gate for {@code envelope}, reading time from the {@link TimeSource} the kernel binds.
     *
     * <p>The arrival-time comparison decides whether a request is admitted, so it reads the kernel's time
     * seam rather than the platform clock (ADR-082). The source is taken here, where the contract gate
     * builds the decision inside the kernel scope, and kept: a request thread inherits no binding.
     * {@link TimeSource#SYSTEM} stands in when none is bound.
     *
     * @param contractId the contract the violations are reported against
     * @param envelope   the ceilings to hold
     */
    public DeveloperWorkloadAdmission(String contractId, WorkloadEnvelope envelope) {
        this(contractId, envelope, TimeKernelProviders.timeSource());
    }

    /**
     * Creates the gate for {@code envelope} on an explicit time source.
     *
     * <p>This constructor exists for deterministic tests, which hold or step the source; a kernel builds
     * the gate with {@link #DeveloperWorkloadAdmission(String, WorkloadEnvelope)}.
     *
     * @param contractId the contract the violations are reported against
     * @param envelope   the ceilings to hold
     * @param clock      the source whose {@link TimeSource#nanoTime()} readings are compared; only
     *                   differences between them are used
     */
    public DeveloperWorkloadAdmission(String contractId, WorkloadEnvelope envelope, TimeSource clock) {
        this.contractId = Objects.requireNonNull(contractId, "contractId must not be null");
        Objects.requireNonNull(envelope, "envelope must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.maxConnections = envelope.maxConnections();
        this.refuseAllRequests = envelope.maxThroughputRps() == 0;
        this.emissionInterval = refuseAllRequests ? 0 : NANOS_PER_SECOND / envelope.maxThroughputRps();
        this.burstTolerance = NANOS_PER_SECOND - emissionInterval;
        this.theoreticalArrival = new AtomicLong(clock.nanoTime());
    }

    @Override
    public boolean enforced() {
        return true;
    }

    @Override
    public boolean tryAdmitConnection() {
        while (true) {
            int current = connections.get();
            if (current >= maxConnections) {
                reportFirst(connectionRefusalReported, "maxConnections");
                return false;
            }
            if (connections.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    @Override
    public void connectionClosed() {
        // Never below zero: a driver that releases twice must not raise the effective ceiling.
        connections.updateAndGet(held -> held > 0 ? held - 1 : 0);
    }

    @Override
    public boolean tryAdmitRequest() {
        if (refuseAllRequests) {
            reportFirst(requestRefusalReported, "maxThroughputRps");
            return false;
        }
        while (true) {
            long now = clock.nanoTime();
            long arrival = theoreticalArrival.get();
            long base = arrival - now < 0 ? now : arrival;
            if (base - now > burstTolerance) {
                reportFirst(requestRefusalReported, "maxThroughputRps");
                return false;
            }
            if (theoreticalArrival.compareAndSet(arrival, base + emissionInterval)) {
                return true;
            }
        }
    }

    private void reportFirst(AtomicBoolean alreadyReported, String ceiling) {
        if (!alreadyReported.get() && alreadyReported.compareAndSet(false, true)) {
            ContractViolationReporter.report(new ContractViolation(
                    contractId, "workloadEnvelope", EnforcementLevel.HARD, ceiling));
        }
    }
}
