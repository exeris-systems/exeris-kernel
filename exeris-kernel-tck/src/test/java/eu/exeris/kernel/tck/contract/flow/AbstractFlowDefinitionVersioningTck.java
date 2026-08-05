/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.exceptions.flow.FlowEngineException;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowSnapshot;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.flow.model.FlowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: a definition is identified by name <em>and</em> version, and a parked saga resumes on the
 * version it parked under (ADR-064).
 *
 * <h2>What this pins that {@code AbstractSagaRecoveryTck} cannot</h2>
 * <p>ADR-062 made a changed definition <em>detectable</em>: a saga whose step moved fails closed.
 * That guard is only reachable because resume rebinds to whatever plan holds the definition name. The
 * contract here is the other half — the rebinding itself is wrong, and two versions must be able to
 * exist at once so a saga does not need rebinding in the first place.
 *
 * <p>The two refusals are deliberately distinct and both mandatory. A suite proving only that
 * something is refused would pass against an engine that refuses every resume, which is exactly the
 * failure mode a fail-closed guard invites.
 *
 * @since 0.11.0
 */
@DisplayName("TCK: Flow definition versioning — coexistence and version-bound resume")
public abstract class AbstractFlowDefinitionVersioningTck {

    /** A definition name no test registers, used to prove the cross-engine path is untouched. */
    private static final String FOREIGN_DEFINITION = "definition-this-engine-does-not-host";
    private static final String DEFINITION = "versioned-saga";
    private static final String PARKED_STEP = "parked-step";
    private static final long AWAIT_SECONDS = 15L;

    protected abstract FlowEngine createEngine();

    protected abstract FlowSnapshotStore snapshotStore();

    private FlowEngine engine;

    @BeforeEach
    final void setUpEngine() {
        engine = createEngine();
        engine.start();
    }

    @AfterEach
    final void tearDownEngine() {
        engine.close();
    }

    /**
     * Registers a version whose single step records that it ran, so a resume can be observed by
     * <em>which</em> version executed rather than merely by the flow completing.
     */
    private FlowExecutionPlan register(int version, AtomicInteger executions) {
        // Two steps on purpose. A saga parked AT step 0 resumes at step 0+1, so a single-step
        // definition has nothing to run on wake and the resume looks identical to a refusal.
        // Differs from the plain builder path in exactly one respect — the version — so a failure
        // here cannot be blamed on how the definition was assembled.
        FlowDefinition base = engine.plans().newDefinition(DEFINITION)
                .step(PARKED_STEP, _ -> FlowOutcome.PARK, null)
                .step("resumed-step", _ -> {
                    executions.incrementAndGet();
                    return FlowOutcome.COMPLETE;
                }, null)
                .transition(0, 1)
                .build();
        FlowDefinition versioned = new FlowDefinition(
                base.name(), version, base.steps(), base.timeoutDurationNanos(), base.maxRetries());
        return engine.plans().compile(versioned);
    }

    private static FlowSnapshot parkedSnapshot(UUID id, String definitionName, int definitionVersion) {
        return new FlowSnapshot(
                id.getMostSignificantBits(),
                id.getLeastSignificantBits(),
                definitionName,
                definitionVersion,
                0,
                Optional.of(PARKED_STEP),
                FlowState.PARKED,
                Instant.now(),
                Instant.now().plusSeconds(60L),
                new int[0],
                0,
                new byte[0],
                FlowSnapshot.SCHEMA_VERSION_INITIAL);
    }

    private static FlowContext contextFor(UUID id) {
        return new FlowContext() {
            @Override public long instanceIdMost() {
                return id.getMostSignificantBits();
            }

            @Override public long instanceIdLeast() {
                return id.getLeastSignificantBits();
            }

            @Override public String definitionName() {
                return DEFINITION;
            }

            @Override public int currentStep() {
                return 0;
            }

            @Override public FlowState state() {
                return FlowState.PARKED;
            }

            @Override public long timeoutNanos() {
                return System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
            }
        };
    }

    @Nested
    @DisplayName("Coexistence")
    class Coexistence {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("registering a new version does not evict the one in-flight sagas parked under")
        void registeringANewVersionKeepsTheOldOne() {
            AtomicInteger v1Executions = new AtomicInteger();
            AtomicInteger v2Executions = new AtomicInteger();

            FlowExecutionPlan v1 = register(1, v1Executions);
            FlowExecutionPlan v2 = register(2, v2Executions);

            assertThat(v1.definitionVersion())
                    .as("both plans must remain distinct objects; a catalog keyed by name alone "
                            + "would have handed back the same one")
                    .isEqualTo(1);
            assertThat(v2.definitionVersion()).isEqualTo(2);
            assertThat(v1).isNotSameAs(v2);
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a saga parked under v1 resumes on v1, not on the newest registered version")
        void parkedSagaResumesOnItsOwnVersion() {
            AtomicInteger v1Executions = new AtomicInteger();
            AtomicInteger v2Executions = new AtomicInteger();
            register(1, v1Executions);
            register(2, v2Executions);

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1));

            engine.scheduler().wake(contextFor(id));
            awaitTrue(() -> v1Executions.get() + v2Executions.get() > 0);

            assertThat(v1Executions.get())
                    .as("the saga parked under v1; resuming it on v2 is the silent mis-replay this "
                            + "contract exists to prevent, and it would look like a successful resume")
                    .isEqualTo(1);
            assertThat(v2Executions.get())
                    .as("v2 must not have run — it is a different definition to this saga")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("Fail-closed refusals")
    class Refusals {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a snapshot carrying no version is refused (DEFINITION_VERSION_ABSENT)")
        void snapshotWithoutVersionIsRefused() {
            AtomicInteger executions = new AtomicInteger();
            register(1, executions);

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, DEFINITION, FlowSnapshot.VERSION_ABSENT));

            assertThatThrownBy(() -> engine.scheduler().wake(contextFor(id)))
                    .as("a pre-0.11 row cannot be validated, and admitting it would leave a "
                            + "permanent route back to resuming against whatever is registered")
                    .isInstanceOfSatisfying(FlowEngineException.class, ex ->
                            assertThat(ex.rawArgs()[2])
                                    .isEqualTo(FlowEngineException.REASON_DEFINITION_VERSION_ABSENT));
            assertThat(executions.get())
                    .as("refused before any step replays")
                    .isZero();
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a version this engine does not host is refused (DEFINITION_VERSION_UNRESOLVED)")
        void unknownVersionIsRefused() {
            AtomicInteger executions = new AtomicInteger();
            register(1, executions);

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 7));

            assertThatThrownBy(() -> engine.scheduler().wake(contextFor(id)))
                    .as("the engine hosts this definition, just not that version — so the saga is "
                            + "ours and unservable, which is a refusal rather than someone else's work")
                    .isInstanceOfSatisfying(FlowEngineException.class, ex ->
                            assertThat(ex.rawArgs()[2])
                                    .isEqualTo(FlowEngineException.REASON_DEFINITION_VERSION_UNRESOLVED));
            assertThat(executions.get()).isZero();
        }

        /**
         * The guarantee has to hold on the resubmit path too, not only on wake.
         *
         * <p>{@code schedule()} hands the runtime a plan the application already holds — plausibly
         * the newest it compiled — for an instance that may be parked under an older version. Where
         * the two happen to line up at the parked index, resuming on the caller's plan is the same
         * silent mis-replay reached through a different entry point, and it would look like an
         * ordinary successful resubmit. Choreography reaches this path directly.
         */
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("schedule() with a newer plan refuses a saga parked under an older version")
        void scheduleWithMismatchedVersionIsRefused() {
            AtomicInteger v1Executions = new AtomicInteger();
            AtomicInteger v2Executions = new AtomicInteger();
            register(1, v1Executions);
            FlowExecutionPlan v2 = register(2, v2Executions);

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1));

            assertThatThrownBy(() -> engine.scheduler().schedule(v2, contextFor(id)))
                    .as("the caller supplied v2 for a saga parked under v1; accepting it would "
                            + "resume the saga on a definition it never started under")
                    .isInstanceOfSatisfying(FlowEngineException.class, ex ->
                            assertThat(reasonOf(ex))
                                    .isEqualTo(FlowEngineException.REASON_DEFINITION_VERSION_UNRESOLVED));
            assertThat(v1Executions.get() + v2Executions.get())
                    .as("refused before any step replays, on either version")
                    .isZero();
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a definition this engine hosts no version of is not a refusal — it is another node's saga")
        void unhostedDefinitionIsNotRefused() {
            AtomicInteger executions = new AtomicInteger();
            register(1, executions);

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, FOREIGN_DEFINITION, 1));

            assertThatThrownBy(() -> engine.scheduler().wake(contextFor(id)))
                    .as("ADR-013 §8 routes a saga this node does not host to the cross-engine "
                            + "fallback. Turning that into a version refusal would break choreography "
                            + "on every node that legitimately hosts only part of the flow catalogue")
                    .isInstanceOfSatisfying(FlowEngineException.class, ex ->
                            assertThat(reasonOf(ex))
                                    .isNotEqualTo(FlowEngineException.REASON_DEFINITION_VERSION_UNRESOLVED));
        }
    }

    /**
     * The {@code rawArgs[2]} reason, or {@code null} for a plain lifecycle failure.
     *
     * <p>Not every {@code FlowEngineException} carries the Glass-Box layout — the "not parked"
     * refusal is constructed from a bare message — so a case asserting "this is <em>not</em> a
     * version refusal" must survive an empty payload rather than index into it.
     */
    private static Object reasonOf(FlowEngineException ex) {
        Object[] args = ex.rawArgs();
        return args.length > 2 ? args[2] : null;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the resumed saga never executed a step");
            }
            java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
        }
    }
}
