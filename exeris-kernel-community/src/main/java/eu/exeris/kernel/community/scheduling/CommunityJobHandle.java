/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.scheduling;

import eu.exeris.kernel.spi.scheduling.JobDescriptor;
import eu.exeris.kernel.spi.scheduling.JobHandle;
import eu.exeris.kernel.spi.scheduling.JobState;

/**
 * A registered job: its schedule position, its captured identity, and its state.
 *
 * <p>All mutable fields are read and written under the clock lock held by
 * {@link CommunityJobRegistry}, which is also where {@code state()} and {@code cancel()} land. The
 * handle is not a synchronisation point of its own — a second lock here would only create an
 * ordering to get wrong.
 *
 * @since 0.11.0
 */
// TooManyMethods: the JobHandle contract itself is most of them, and releasePayload/settle add the
// lifecycle the payload release needs. Splitting would put the released fields and the code that
// releases them in different classes, which is the one arrangement that makes the invariant harder
// to check.
@SuppressWarnings("PMD.TooManyMethods")
final class CommunityJobHandle implements JobHandle {

    private final CommunityJobRegistry registry;
    private final String jobId;
    private final String jobName;

    // Not final, and nulled on terminal settle: the descriptor holds the application's job body and
    // the captured context holds the submitter's PrincipalContext and StorageContext. A handle stays
    // addressable by id after the job ends, so keeping these alive would pin one closure and one
    // identity per job the scheduler has ever run, for the scheduler's lifetime. Nothing reads them
    // past the settle — the trigger is consulted while deciding it, not after.
    private JobDescriptor descriptor;
    private CapturedContext context;

    private long dueNanos;
    private JobState state = JobState.SCHEDULED;

    /* default */ CommunityJobHandle(CommunityJobRegistry registry, String jobId,
                                     JobDescriptor descriptor, CapturedContext context,
                                     long dueNanos) {
        this.registry = registry;
        this.jobId = jobId;
        this.descriptor = descriptor;
        this.context = context;
        // Copied out because jobName() must keep answering once the descriptor is released.
        this.jobName = descriptor.jobName();
        this.dueNanos = dueNanos;
    }

    @Override
    public String jobId() {
        return jobId;
    }

    @Override
    public String jobName() {
        return jobName;
    }

    @Override
    public JobState state() {
        return registry.stateOf(this);
    }

    @Override
    public boolean cancel() {
        return registry.cancel(this);
    }

    /**
     * The job body and trigger, or {@code null} once the job has settled.
     *
     * <p>Callers on the dispatch path never see null: a settled job is out of the queue, so nothing
     * reaches {@code execute()} for it. Callers deciding what happens NEXT must order their checks
     * so this is not read after a settle — {@code finishRun} tests {@code !running} first for
     * exactly that reason.
     */
    /* default */ JobDescriptor descriptor() {
        return descriptor;
    }

    /** The submitter's captured identity, or {@code null} once the job has settled. */
    /* default */ CapturedContext context() {
        return context;
    }

    /**
     * Drops the job body and the submitter's identity once the job can no longer run.
     *
     * <p>Caller holds the lock. Idempotent — a settle can be reached from cancellation, from a
     * one-shot completing, and from scheduler shutdown.
     */
    // NullAssignment is the point, not a smell: absence is the state being recorded. There is no
    // sentinel a JobDescriptor or a CapturedContext could take instead that would not itself keep a
    // closure and an identity alive, which is the whole reason for the release.
    @SuppressWarnings("PMD.NullAssignment")
    /* default */ void releasePayload() {
        this.descriptor = null;
        this.context = null;
    }

    /* default */ long dueNanos() {
        return dueNanos;
    }

    /* default */ void dueNanos(long value) {
        this.dueNanos = value;
    }

    /* default */ JobState rawState() {
        return state;
    }

    /* default */ void rawState(JobState value) {
        this.state = value;
    }

    /* default */ boolean isTerminal() {
        return state == JobState.COMPLETED || state == JobState.CANCELLED;
    }
}
