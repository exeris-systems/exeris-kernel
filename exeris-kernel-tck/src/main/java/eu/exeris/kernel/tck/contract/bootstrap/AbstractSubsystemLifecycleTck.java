/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import eu.exeris.kernel.spi.bootstrap.Subsystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Abstract base for {@link Subsystem} per-instance lifecycle state machine verification.
 *
 * <h2>Gap Closed</h2>
 * <p>{@link AbstractSubsystemProviderTck} verifies provider-level concerns.
 * This class closes the spec-drift gap by verifying each subsystem's own
 * {@code initialize → start → stop} state machine contract.
 *
 * <h2>Verified Constraints</h2>
 * <ol>
 *   <li>{@code name()} returns non-null, non-blank identifier.</li>
 *   <li>{@code phase()} returns non-null {@link eu.exeris.kernel.spi.bootstrap.BootstrapPhase}.</li>
 *   <li>{@code dependsOn()} returns non-null list with no null or blank entries.</li>
 *   <li>{@code initialize()} does not throw for a valid, isolated instance.</li>
 *   <li>{@code start()} after {@code initialize()} does not throw.</li>
 *   <li>{@code stop()} after {@code start()} does not throw.</li>
 *   <li>{@code stop()} after {@code stop()} is idempotent — does not throw.</li>
 *   <li>{@code isRunning()} returns {@code false} after {@code stop()}.</li>
 * </ol>
 *
 * <h2>Context Hook</h2>
 * <p>Subsystems whose lifecycle methods require a bound execution context
 * (e.g., a {@code ScopedValue} slot such as {@code KernelProviders.CURRENT_CONFIG}) must
 * override {@link #withLifecycleContext(Runnable)} to wrap the action in that scope.
 * The default implementation runs the action directly with no additional context.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class MySubsystemLifecycleTckTest extends AbstractSubsystemLifecycleTck {
 *     \@Override protected Subsystem createSubsystem() {
 *         return new MySubsystem();
 *     }
 * }
 * }</pre>
 *
 * @since 0.5
 */
public abstract class AbstractSubsystemLifecycleTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /** Creates a {@link Subsystem} instance ready for {@code initialize()} — not yet initialized. */
    protected abstract Subsystem createSubsystem();

    /**
     * Wraps a lifecycle action in any required execution context.
     *
     * <p>Override in binding tests to bind {@code ScopedValue} slots or other required
     * context before calling lifecycle methods on the subsystem under test.
     * The default implementation runs the action directly with no additional scope.
     *
     * <p>Implementations must propagate all unchecked exceptions thrown by {@code action}.
     */
    protected void withLifecycleContext(Runnable action) {
        action.run();
    }

    private Subsystem subsystem;

    @BeforeEach
    final void setUpSubsystem() {
        subsystem = createSubsystem();
    }

    // =========================================================================
    // Identity contract — name, phase, dependsOn
    // =========================================================================

    @Nested
    @DisplayName("Identity contract — name, phase, dependsOn")
    class IdentityContract {

        @Test
        @DisplayName("name() returns non-null non-blank string")
        void nameIsNonBlank() {
            assertThat(subsystem.name())
                    .as("Subsystem.name() MUST be non-null and non-blank")
                    .isNotBlank();
        }

        @Test
        @DisplayName("phase() returns non-null BootstrapPhase")
        void phaseIsNonNull() {
            assertThat(subsystem.phase())
                    .as("Subsystem.phase() MUST return a non-null BootstrapPhase")
                    .isNotNull();
        }

        @Test
        @DisplayName("dependsOn() returns non-null list")
        void dependsOnIsNonNull() {
            assertThat(subsystem.dependsOn())
                    .as("Subsystem.dependsOn() MUST return a non-null list")
                    .isNotNull();
        }

        @Test
        @DisplayName("dependsOn() list contains no null entries")
        void dependsOnContainsNoNulls() {
            assertThat(subsystem.dependsOn())
                    .as("Subsystem.dependsOn() MUST NOT contain null entries")
                    .doesNotContainNull();
        }

        @Test
        @DisplayName("dependsOn() list contains no blank string entries")
        void dependsOnContainsNoBlanks() {
            subsystem.dependsOn().forEach(dep ->
                    assertThat(dep)
                            .as("Subsystem.dependsOn() MUST NOT contain blank dependency names")
                            .isNotBlank());
        }
    }

    // =========================================================================
    // Lifecycle state machine
    // =========================================================================

    @Nested
    @DisplayName("Lifecycle state transitions")
    class LifecycleStateTransitions {

        @Test
        @DisplayName("initialize() does not throw")
        void initializeDoesNotThrow() {
            assertThatCode(() -> withLifecycleContext(subsystem::initialize))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("start() after initialize() does not throw")
        void startAfterInitializeDoesNotThrow() {
            withLifecycleContext(subsystem::initialize);
            assertThatCode(() -> withLifecycleContext(subsystem::start))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("stop() after start() does not throw")
        void stopAfterStartDoesNotThrow() {
            withLifecycleContext(subsystem::initialize);
            withLifecycleContext(subsystem::start);
            assertThatCode(() -> withLifecycleContext(subsystem::stop))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("stop() after stop() is idempotent — does not throw")
        void doubleStopIsIdempotent() {
            withLifecycleContext(subsystem::initialize);
            withLifecycleContext(subsystem::start);
            withLifecycleContext(subsystem::stop);
            assertThatCode(() -> withLifecycleContext(subsystem::stop))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("isRunning() returns false after stop()")
        void isRunningFalseAfterStop() {
            withLifecycleContext(subsystem::initialize);
            withLifecycleContext(subsystem::start);
            withLifecycleContext(subsystem::stop);
            assertThat(subsystem.isRunning())
                    .as("Subsystem.isRunning() MUST return false after stop()")
                    .isFalse();
        }
    }
}
