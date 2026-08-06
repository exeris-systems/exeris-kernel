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
import eu.exeris.kernel.spi.flow.FlowScheduler;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowDefinitionMigration;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowMigrationState;
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
        return parkedSnapshot(id, definitionName, definitionVersion, new int[0], 0);
    }

    private static FlowSnapshot parkedSnapshot(UUID id, String definitionName, int definitionVersion,
                                               int[] compensationStack, int stackPointer) {
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
                compensationStack,
                stackPointer,
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

        /**
         * The cursor guards do not cover the steps the saga has already run.
         *
         * <p>ADR-062 validated where a saga <em>resumes</em>. A rollback walks the other direction,
         * through the compensation stack, and that is read as bare indices into whatever plan the
         * resume bound to. A saga can therefore pass both cursor guards — right index, right name —
         * and still hold a stack that is meaningless in that plan.
         *
         * <p>Refused on resume rather than left to fail during compensation, because the compensation
         * walk reads the plan outside its own catch: a stale entry there aborts the rest of the unwind
         * and skips the terminal write, leaving the saga mid-rollback with its guard held. That is
         * strictly worse than not resuming, and it only surfaces once something has already failed.
         */
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a compensation-stack entry outside the plan is refused before the saga resumes")
        void compensationStackOutsideThePlanIsRefused() {
            AtomicInteger executions = new AtomicInteger();
            register(1, executions);

            UUID id = UUID.randomUUID();
            // Cursor is step 0 under the name the plan really has there, so both ADR-062 guards pass.
            // The stack names step 5 of a two-step plan — a shape a shrinking redeploy leaves behind.
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1, new int[] {0, 5}, 2));

            assertThatThrownBy(() -> engine.scheduler().wake(contextFor(id)))
                    .as("the cursor guards pass here, so nothing else would have caught this")
                    .isInstanceOfSatisfying(FlowEngineException.class, ex ->
                            assertThat(reasonOf(ex))
                                    .isEqualTo(FlowEngineException.REASON_COMPENSATION_STACK_OUT_OF_RANGE));
            assertThat(snapshotStore().load(id.getMostSignificantBits(), id.getLeastSignificantBits()))
                    .as("refusing must leave the row recoverable, like every other resume refusal")
                    .isPresent();
            assertThat(executions.get()).isZero();
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("entries beyond the stack pointer are dead and do not refuse a valid resume")
        void staleEntriesAboveTheStackPointerAreIgnored() {
            AtomicInteger executions = new AtomicInteger();
            register(1, executions);

            UUID id = UUID.randomUUID();
            // stackPointer=1, so entry 9 is below the high-water mark of a stack that has been popped.
            // A guard scanning the whole array instead of the live prefix would refuse a sound saga —
            // fail-closed is only correct when what it closes on is actually live.
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1, new int[] {0, 9}, 1));

            engine.scheduler().wake(contextFor(id));
            awaitTrue(() -> executions.get() > 0);

            assertThat(executions.get()).isEqualTo(1);
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

    @Nested
    @DisplayName("In-flight migration")
    class Migration {

        /** Identity transform: the versions differ but the saga sits at the same named step. */
        private FlowMigrationState sameStep(FlowMigrationState parked) {
            return parked;
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a single hop moves a saga parked under v1 onto the registered v2")
        void singleHopMigrationResumesOnTargetVersion() {
            AtomicInteger v2Executions = new AtomicInteger();
            register(2, v2Executions);
            engine.plans().registerMigration(DEFINITION, 1, this::sameStep);

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1));

            engine.scheduler().wake(contextFor(id));
            awaitTrue(() -> v2Executions.get() > 0);

            assertThat(v2Executions.get())
                    .as("v1 is no longer hosted but a path to v2 exists, so the saga moves rather "
                            + "than being refused")
                    .isEqualTo(1);
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("adjacent hops chain: v1 reaches a registered v3 through two transforms")
        void chainedMigrationCrossesTwoHops() {
            AtomicInteger v3Executions = new AtomicInteger();
            register(3, v3Executions);
            engine.plans().registerMigration(DEFINITION, 1, this::sameStep);
            engine.plans().registerMigration(DEFINITION, 2, this::sameStep);

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1));

            engine.scheduler().wake(contextFor(id));
            awaitTrue(() -> v3Executions.get() > 0);

            assertThat(v3Executions.get())
                    .as("an application registers n-1 adjacent transforms, not every pair; the "
                            + "runtime is what walks them")
                    .isEqualTo(1);
        }

        /**
         * The chain's terminating condition is "hosted", not "no further transform".
         *
         * <p>Without a registered hop past the hosted version, every chained case ends at the last
         * transform and at a hosted version simultaneously — so the two candidate rules are
         * indistinguishable, and an engine walking one hop too far passes the whole suite. Here they
         * disagree: v2 is hosted and a 2 to 3 transform exists, so an engine that keeps hopping
         * lands on an unhosted v3, abandons the chain, and refuses the saga it could have served.
         */
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("the chain stops at the first hosted version even when a further hop is registered")
        void migrationStopsAtTheFirstHostedVersion() {
            AtomicInteger v2Executions = new AtomicInteger();
            register(2, v2Executions);
            engine.plans().registerMigration(DEFINITION, 1, this::sameStep);
            engine.plans().registerMigration(DEFINITION, 2, this::sameStep);

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1));

            engine.scheduler().wake(contextFor(id));
            awaitTrue(() -> v2Executions.get() > 0);

            assertThat(v2Executions.get())
                    .as("an application retires versions on its own schedule and may well keep a "
                            + "transform registered past what it still hosts; that is not a reason "
                            + "to carry a saga beyond the version it can actually run on")
                    .isEqualTo(1);
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("registering a second transform for the same version is refused")
        void duplicateMigrationRegistrationIsRefused() {
            register(2, new AtomicInteger());
            engine.plans().registerMigration(DEFINITION, 1, this::sameStep);

            assertThatThrownBy(() -> engine.plans().registerMigration(DEFINITION, 1, this::sameStep))
                    .as("silently replacing would let a redeploy change how in-flight sagas are "
                            + "moved without anyone stating it — the transform is as load-bearing "
                            + "as the definition itself")
                    .isInstanceOf(FlowEngineException.class);
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a missing link mid-chain is refused, and the row survives it")
        void missingLinkIsRefused() {
            AtomicInteger v3Executions = new AtomicInteger();
            register(3, v3Executions);
            engine.plans().registerMigration(DEFINITION, 2, this::sameStep);

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1));

            assertThatThrownBy(() -> engine.scheduler().wake(contextFor(id)))
                    .as("no 1 to 2 transform exists, so there is no path; a best-effort jump to the "
                            + "2 to 3 hop would be exactly the guess this epic removes")
                    .isInstanceOfSatisfying(FlowEngineException.class, ex ->
                            assertThat(reasonOf(ex))
                                    .isEqualTo(FlowEngineException.REASON_DEFINITION_VERSION_UNRESOLVED));
            assertThat(snapshotStore().load(id.getMostSignificantBits(), id.getLeastSignificantBits()))
                    .as("rejection must not mutate the row — registering the missing transform and "
                            + "waking again is the documented remedy, and it needs the row intact")
                    .isPresent();
            assertThat(v3Executions.get()).isZero();
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a transform that throws fails the wake closed and leaves the row recoverable")
        void throwingMigrationFailsClosed() {
            AtomicInteger v2Executions = new AtomicInteger();
            register(2, v2Executions);
            engine.plans().registerMigration(DEFINITION, 1, _ -> {
                throw new IllegalStateException("transform is broken");
            });

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1));

            // The transform's own exception, by type and message — not merely "something was
            // thrown". FlowEngineException is itself a RuntimeException, so the loose assertion also
            // passed against an engine that never invoked the transform and refused the version
            // outright, which is the opposite of what this case claims to pin.
            assertThatThrownBy(() -> engine.scheduler().wake(contextFor(id)))
                    .as("application code failing on the resume path must surface, not be swallowed "
                            + "into a wake that quietly does nothing, and not be relabelled as a "
                            + "version refusal the operator would then chase in the wrong place")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("transform is broken");
            assertThat(snapshotStore().load(id.getMostSignificantBits(), id.getLeastSignificantBits()))
                    .as("a broken transform is fixable; destroying the row would make it terminal")
                    .isPresent();
            assertThat(v2Executions.get()).isZero();
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a transform naming a step the target plan does not have is refused")
        void migrationEmittingUnknownStepIsRefused() {
            AtomicInteger v2Executions = new AtomicInteger();
            register(2, v2Executions);
            engine.plans().registerMigration(DEFINITION, 1, parked -> new FlowMigrationState(
                    parked.parkedStep(), "step-that-does-not-exist",
                    parked.compensationStack(), parked.stackPointer(), parked.opaqueState()));

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1));

            assertThatThrownBy(() -> engine.scheduler().wake(contextFor(id)))
                    .as("ADR-064 obligation 9 — the transform runs first and the step it names is "
                            + "checked by the same guard as any resume; this is the cursor half of "
                            + "that obligation, the stack half is the case below")
                    .isInstanceOfSatisfying(FlowEngineException.class, ex ->
                            assertThat(reasonOf(ex))
                                    .isEqualTo(FlowEngineException.REASON_STEP_IDENTITY_MISMATCH));
            assertThat(v2Executions.get()).isZero();
        }

        /**
         * A runtime that cannot persist must not migrate.
         *
         * <p>{@code persistSnapshot} carries a lifecycle write-fence so a worker on a closed or
         * superseded runtime cannot re-establish a non-terminal row another runtime may already have
         * reclaimed. The migration write needs the same fence, and it is hoisted above the walk rather
         * than applied at the save: migrating in memory and skipping the write would resume the saga on
         * a version its row does not name, which is the failure A3 exists to prevent. So the fence has
         * to make the migration not happen, not merely not persist.
         *
         * <p>The scheduler reference is captured <em>before</em> the close, because that is the only
         * way this is reachable and it is the shape real code has: {@code FlowChoreographyBridge} holds
         * a {@code FlowScheduler} from construction, and {@code lookupParked} — unlike {@code wake} and
         * unlike {@code FlowEngine.scheduler()} itself — has no started-check of its own. A bridge
         * draining an in-flight event while the engine closes is precisely the abandoned worker the
         * fence exists for. Fetching the scheduler after the close instead would make this case pass
         * against no fence at all, satisfied by {@code scheduler()}'s own refusal.
         */
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a closed runtime does not migrate — a migration it could not persist must not happen")
        void closedRuntimeDoesNotMigrate() {
            AtomicInteger transformInvocations = new AtomicInteger();
            register(2, new AtomicInteger());
            engine.plans().registerMigration(DEFINITION, 1, parked -> {
                transformInvocations.incrementAndGet();
                return parked;
            });
            FlowScheduler captured = engine.scheduler();

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1));

            engine.close();

            assertThatThrownBy(() -> captured
                    .lookupParked(id.getMostSignificantBits(), id.getLeastSignificantBits()))
                    .as("with nothing able to migrate it, the saga is unservable here and says so")
                    .isInstanceOf(FlowEngineException.class);
            assertThat(transformInvocations.get())
                    .as("application code must not run on behalf of a runtime that has stopped")
                    .isZero();
            assertThat(snapshotStore().load(id.getMostSignificantBits(), id.getLeastSignificantBits())
                    .map(FlowSnapshot::definitionVersion))
                    .as("and the row another runtime may own must be left exactly as found")
                    .hasValue(1);
        }

        /**
         * A snapshot with a version but no step identity belongs to ADR-062's refusal, not to a hop.
         *
         * <p>{@code applyHop} builds the transform's input from {@code currentStepName().orElseThrow()}.
         * Without a bail-out that is a bare {@code NoSuchElementException} escaping a path where every
         * other refusal is a Glass-Box {@code FlowEngineException} with a reason an operator can act on
         * — reachable from a hand-built or partially-written store row, since the record permits the
         * combination.
         *
         * <p>The <em>reason</em> is the assertion, not merely that something was refused. Declining
         * quietly would leave the row to the version guard, which refuses first with
         * {@code DEFINITION_VERSION_UNRESOLVED} — whose documented remedy is "deploy the missing
         * version or register the missing transform", and here a transform is already registered. The
         * row is unresumable because it records no step identity, which no deployment fixes. A refusal
         * naming the wrong remedy is worse than a raw exception, because it looks actionable; an
         * assertion that only checks "a FlowEngineException was thrown" cannot tell the two apart.
         */
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a versioned snapshot with no step identity refuses as STEP_IDENTITY_ABSENT, not as a version miss")
        void migrationOfSnapshotWithoutStepIdentityRefusesCleanly() {
            AtomicInteger transformInvocations = new AtomicInteger();
            register(2, new AtomicInteger());
            engine.plans().registerMigration(DEFINITION, 1, parked -> {
                transformInvocations.incrementAndGet();
                return parked;
            });

            UUID id = UUID.randomUUID();
            snapshotStore().save(new FlowSnapshot(
                    id.getMostSignificantBits(), id.getLeastSignificantBits(),
                    DEFINITION, 1, 0, Optional.empty(),
                    FlowState.PARKED, Instant.now(), Instant.now().plusSeconds(60L),
                    new int[0], 0, new byte[0], FlowSnapshot.SCHEMA_VERSION_INITIAL));

            assertThatThrownBy(() -> engine.scheduler().wake(contextFor(id)))
                    .as("the reason an operator will act on: no step identity, which no deployment "
                            + "fixes — not a version miss, whose remedy is already satisfied here")
                    .isInstanceOfSatisfying(FlowEngineException.class, ex ->
                            assertThat(reasonOf(ex))
                                    .isEqualTo(FlowEngineException.REASON_STEP_IDENTITY_ABSENT));
            assertThat(transformInvocations.get())
                    .as("a row that cannot be validated must not be handed to application code first")
                    .isZero();
        }

        /**
         * The choreography wake shape, end to end.
         *
         * <p>{@code FlowChoreographyBridge} wakes a saga as
         * {@code lookupParked(most, least).ifPresent(scheduler::wake)}, so on a node that does not
         * hold the instance in memory the migration happens inside {@code lookupParked}'s durable-store
         * fallback — that fallback is a <em>restore</em>, not a read. Nothing pinned this, and the
         * consequence of getting it wrong is not a slow path but a dead one: a cross-engine saga on a
         * retired version would refuse instead of migrating, on the very topology ADR-013 §8 exists for.
         */
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("the choreography shape — lookupParked() then wake() — migrates and resumes")
        void lookupParkedThenWakeMigratesAndResumes() {
            AtomicInteger v2Executions = new AtomicInteger();
            register(2, v2Executions);
            engine.plans().registerMigration(DEFINITION, 1, this::sameStep);

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1));

            Optional<FlowContext> found = engine.scheduler()
                    .lookupParked(id.getMostSignificantBits(), id.getLeastSignificantBits());
            assertThat(found)
                    .as("the saga is parked and migratable; an empty lookup here is how a choreography "
                            + "wake becomes a silent no-op rather than a visible refusal")
                    .isPresent();

            found.ifPresent(engine.scheduler()::wake);
            awaitTrue(() -> v2Executions.get() > 0);

            assertThat(v2Executions.get()).isEqualTo(1);
        }

        /**
         * Repeated lookups do not re-run the transform — the persisted result is what stops them.
         *
         * <p>The direct-wake case asserts the write happens; this asserts what the write <em>buys</em>,
         * on the path where it matters most. A lookup is cheap to repeat and an application may poll
         * one, so an unpersisted migration would run application code once per lookup and make
         * transform purity a load-bearing obligation nothing states.
         */
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a repeated lookupParked() runs the transform once, not once per lookup")
        void repeatedLookupRunsTheTransformOnce() {
            AtomicInteger transformInvocations = new AtomicInteger();
            register(2, new AtomicInteger());
            engine.plans().registerMigration(DEFINITION, 1, parked -> {
                transformInvocations.incrementAndGet();
                return parked;
            });

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1));

            for (int attempt = 0; attempt < 3; attempt++) {
                assertThat(engine.scheduler()
                        .lookupParked(id.getMostSignificantBits(), id.getLeastSignificantBits()))
                        .isPresent();
            }

            assertThat(transformInvocations.get())
                    .as("ADR-064 A3 — the row now names v2, so the second and third lookups have "
                            + "nothing left to migrate")
                    .isEqualTo(1);
            assertThat(snapshotStore().load(id.getMostSignificantBits(), id.getLeastSignificantBits())
                    .map(FlowSnapshot::definitionVersion))
                    .hasValue(2);
        }

        /**
         * The transform's compensation stack is checked too, not only the step it names.
         *
         * <p>{@code FlowMigrationState} has five components and ADR-064 obligation 9 claims the
         * runtime validates the transform's output. Until this case, that held for two of them: the
         * parked step and its name. A transform emitting a stack entry the target plan cannot index
         * was accepted, and the mistake surfaced later as an aborted rollback — the furthest possible
         * point from the code that caused it, and only after something else had already failed.
         */
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a transform emitting a compensation-stack entry the target plan lacks is refused")
        void migrationEmittingOutOfRangeCompensationStackIsRefused() {
            AtomicInteger v2Executions = new AtomicInteger();
            register(2, v2Executions);
            engine.plans().registerMigration(DEFINITION, 1, parked -> new FlowMigrationState(
                    parked.parkedStep(), parked.parkedStepName(),
                    new int[] {4}, 1, parked.opaqueState()));

            UUID id = UUID.randomUUID();
            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1));

            assertThatThrownBy(() -> engine.scheduler().wake(contextFor(id)))
                    .as("obligation 9 covers the whole carrier or it does not hold — a transform is "
                            + "application code and the stack it emits drives compensation")
                    .isInstanceOfSatisfying(FlowEngineException.class, ex ->
                            assertThat(reasonOf(ex))
                                    .isEqualTo(FlowEngineException.REASON_COMPENSATION_STACK_OUT_OF_RANGE));
            assertThat(v2Executions.get()).isZero();
        }

        /**
         * The migrated version reaches the store <em>before</em> the resumed saga runs.
         *
         * <p>Read from inside the resumed step rather than after it, because the runtime writes a
         * snapshot on PARK / COMPENSATING / FAILED_ROLLEDBACK and on no other transition. At this
         * instant the migration's own write is the only one that can have happened. Asserting the
         * end state instead cannot see the write at all: an engine that migrated in memory and never
         * persisted would still park at version 2 a moment later, so the row would read 2 either way
         * and the case would pass while pinning nothing.
         */
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a successful migration reaches the store before the resumed step runs")
        void migrationIsPersistedBeforeTheResumedStepRuns() {
            UUID id = UUID.randomUUID();
            AtomicInteger observedVersion = new AtomicInteger(FlowSnapshot.VERSION_ABSENT);
            FlowDefinition base = engine.plans().newDefinition(DEFINITION)
                    .step(PARKED_STEP, _ -> FlowOutcome.PARK, null)
                    .step("resumed-step", _ -> {
                        observedVersion.set(snapshotStore()
                                .load(id.getMostSignificantBits(), id.getLeastSignificantBits())
                                .map(FlowSnapshot::definitionVersion)
                                .orElse(FlowSnapshot.VERSION_ABSENT));
                        return FlowOutcome.COMPLETE;
                    }, null)
                    .transition(0, 1)
                    .build();
            engine.plans().compile(new FlowDefinition(
                    base.name(), 2, base.steps(), base.timeoutDurationNanos(), base.maxRetries()));
            engine.plans().registerMigration(DEFINITION, 1, this::sameStep);

            snapshotStore().save(parkedSnapshot(id, DEFINITION, 1));

            engine.scheduler().wake(contextFor(id));
            awaitTrue(() -> observedVersion.get() != FlowSnapshot.VERSION_ABSENT);

            assertThat(observedVersion.get())
                    .as("ADR-064 amendment A3: not persisting would leave the row asserting a version "
                            + "the saga no longer runs, and would make transform purity an unstated "
                            + "obligation because the chain would re-run on every wake")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("SPI stability (spi.flow is stable since 0.5.0)")
    class StabilityCompatibility {

        /**
         * The 0.10.0 canonical constructor still exists, and still fails closed.
         *
         * <p>Two obligations that pull in opposite directions and must both hold. `spi.flow` is
         * declared stable, so code compiled against 0.10.0 must still be able to construct a
         * snapshot. And ADR-062/ADR-064 both refuse a snapshot that records no step identity or no
         * definition version — so the compatibility bridge must not be a way to obtain a snapshot
         * that resumes anyway. A shim defaulting to "version 1, step name from the plan" would
         * satisfy the first and quietly destroy the second.
         */
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("the 0.10.0 constructor still compiles and its snapshot is refused on resume")
        void legacyConstructorSurvivesAndStillFailsClosed() {
            AtomicInteger executions = new AtomicInteger();
            register(1, executions);

            UUID id = UUID.randomUUID();
            FlowSnapshot legacy = new FlowSnapshot(
                    id.getMostSignificantBits(), id.getLeastSignificantBits(),
                    DEFINITION, 0, FlowState.PARKED,
                    Instant.now(), Instant.now().plusSeconds(60L),
                    new int[0], 0, new byte[0], FlowSnapshot.SCHEMA_VERSION_INITIAL);

            assertThat(legacy.definitionVersion())
                    .as("the bridge must default to the fail-closed sentinel, never to a real version")
                    .isEqualTo(FlowSnapshot.VERSION_ABSENT);
            assertThat(legacy.currentStepName())
                    .as("and to absent identity, for the same reason ADR-062 gave")
                    .isEmpty();

            snapshotStore().save(legacy);
            assertThatThrownBy(() -> engine.scheduler().wake(contextFor(id)))
                    .as("compiling against 0.10.0 buys a caller compilation, not a bypass of the "
                            + "guards this milestone added")
                    .isInstanceOfSatisfying(FlowEngineException.class, ex ->
                            assertThat(reasonOf(ex))
                                    .isEqualTo(FlowEngineException.REASON_DEFINITION_VERSION_ABSENT));
            assertThat(executions.get()).isZero();
        }
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
