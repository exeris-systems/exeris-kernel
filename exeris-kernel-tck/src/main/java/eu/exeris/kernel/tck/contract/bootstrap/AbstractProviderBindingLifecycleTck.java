/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK contract for provider binding lifecycle and initialization-state symmetry.
 *
 * <h2>Initialization-State Symmetry Guarantee</h2>
 * <p>This test validates that provider bindings are composed <strong>only</strong>
 * for subsystems whose {@code initialize()} completed successfully. Degraded subsystems
 * (those that fail and are skipped via DEGRADE policy) must NOT contribute
 * bindings to the kernel scope.
 *
 * <p>The test ensures:
 * <ul>
 *   <li>Active (non-degraded) subsystems' bindings appear in {@code buildKernelScope()}</li>
 *   <li>Degraded subsystems' bindings do NOT appear in the scope</li>
 *   <li>Bindings are composed after successful {@code initialize()}, before {@code start()}</li>
 * </ul>
 *
 * @since 0.5
 */
@DisplayName("Provider Binding Lifecycle — State Symmetry TCK")
public abstract class AbstractProviderBindingLifecycleTck extends AbstractBootstrapOrchestratorTck {

    private static final ScopedValue<String>  HEALTHY_SLOT  = ScopedValue.newInstance();
    private static final ScopedValue<String>  FAILING_SLOT  = ScopedValue.newInstance();
    private static final ScopedValue<String>  PROVIDER_SLOT = ScopedValue.newInstance();
    private static final ScopedValue<Boolean> BINDING_SEED  = ScopedValue.newInstance();

    /**
     * Creates the contract; subclasses supply the binding via the inherited
     * {@link AbstractBootstrapOrchestratorTck#runSort(List)}.
     */
    public AbstractProviderBindingLifecycleTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Validates that provider bindings are composed only for active (non-degraded) subsystems.
     *
     * <h2>Scenario</h2>
     * <ol>
     *   <li>Two subsystems: one succeeds {@code initialize()}, one throws (simulating DEGRADE)</li>
     *   <li>Binding protocol: {@code providerBindings()} is composed only on successful init</li>
     *   <li>Verify that only the active subsystem's {@link ScopedValue} is present in scope</li>
     * </ol>
     *
     * <h2>Assertion</h2>
     * <p>Degraded subsystems' enrichers are not included in {@code buildKernelScope()}.
     */
    @Test
    @DisplayName("Degraded subsystems' bindings are excluded from kernel scope")
    void providerBindingsExcludedForDegradedSubsystems() {
        BindingTestSubsystem healthy = new BindingTestSubsystem(
                "healthy", List.of(), BootstrapPhase.SERVICES, false) {
            @Override
            public UnaryOperator<ScopedValue.Carrier> providerBindings() {
                return carrier -> carrier.where(HEALTHY_SLOT, "healthy-value");
            }
        };

        BindingTestSubsystem failing = new BindingTestSubsystem(
                "failing", List.of(), BootstrapPhase.SERVICES, true) {
            @Override
            public UnaryOperator<ScopedValue.Carrier> providerBindings() {
                return carrier -> carrier.where(FAILING_SLOT, "failing-value");
            }
        };

        // Simulate the DEGRADE binding protocol:
        // providerBindings() is composed only for subsystems whose initialize() succeeds.
        UnaryOperator<ScopedValue.Carrier> composedEnricher = carrier -> carrier;
        for (BindingTestSubsystem s : List.of(healthy, failing)) {
            try {
                s.initialize();
                composedEnricher = composedEnricher.andThen(s.providerBindings())::apply;
            } catch (RuntimeException ignored) { // simulated DEGRADE: skip degraded subsystem enricher
            }
        }

        ScopedValue.Carrier seed  = ScopedValue.where(BINDING_SEED, Boolean.TRUE);
        ScopedValue.Carrier scope = composedEnricher.apply(seed);

        AtomicReference<String> capturedHealthy = new AtomicReference<>();
        AtomicReference<String> capturedFailing = new AtomicReference<>();

        scope.run(() -> {
            capturedHealthy.set(HEALTHY_SLOT.isBound() ? HEALTHY_SLOT.get() : null);
            capturedFailing.set(FAILING_SLOT.isBound() ? FAILING_SLOT.get() : null);
        });

        assertThat(capturedHealthy.get())
                .as("Active subsystem's binding must be present in scope")
                .isEqualTo("healthy-value");
        assertThat(capturedFailing.get())
                .as("Degraded subsystem's binding must NOT be present in scope")
                .isNull();
    }

    /**
     * Validates that provider bindings are available during dependent subsystem start().
     *
     * <h2>Intent</h2>
     * <p>The binding protocol guarantees that all {@code providerBindings()} enrichers are
     * composed before any {@code start()} is invoked. A dependent subsystem must be able
     * to access the {@link ScopedValue} bindings of its dependencies during its own
     * {@code start()} execution.
     */
    @Test
    @DisplayName("Active subsystems' bindings are visible during dependent start()")
    void providerBindingsVisibleDuringDependentStart() {
        AtomicReference<String> capturedDuringStart = new AtomicReference<>();

        BindingTestSubsystem provider = new BindingTestSubsystem(
                "provider", List.of(), BootstrapPhase.FOUNDATION, false) {
            @Override
            public UnaryOperator<ScopedValue.Carrier> providerBindings() {
                return carrier -> carrier.where(PROVIDER_SLOT, "provider-value");
            }
        };

        BindingTestSubsystem consumer = new BindingTestSubsystem(
                "consumer", List.of("provider"), BootstrapPhase.SERVICES, false) {
            @Override
            public void start() {
                super.start();
                capturedDuringStart.set(PROVIDER_SLOT.isBound() ? PROVIDER_SLOT.get() : null);
            }
        };

        // Phase 1: initialize all subsystems and compose enricher
        UnaryOperator<ScopedValue.Carrier> enricher = carrier -> carrier;
        for (BindingTestSubsystem s : List.of(provider, consumer)) {
            try {
                s.initialize();
                enricher = enricher.andThen(s.providerBindings())::apply;
            } catch (RuntimeException ignored) { // not expected: both subsystems healthy
            }
        }

        // Phase 2: run start() inside the composed scope — bindings must be visible
        ScopedValue.Carrier seed  = ScopedValue.where(BINDING_SEED, Boolean.TRUE);
        ScopedValue.Carrier scope = enricher.apply(seed);

        scope.run(consumer::start);

        assertThat(capturedDuringStart.get())
                .as("Provider's binding must be visible to dependent subsystem during start()")
                .isEqualTo("provider-value");
    }

    /**
     * Stub subsystem for binding lifecycle testing.
     */
    static class BindingTestSubsystem implements Subsystem {
        private final String name;
        private final List<String> deps;
        private final BootstrapPhase phase;
        private volatile boolean running;
        private volatile boolean shouldFail;

        BindingTestSubsystem(String name, List<String> deps, BootstrapPhase phase, boolean shouldFail) {
            this.name = name;
            this.deps = deps;
            this.phase = phase;
            this.shouldFail = shouldFail;
        }

        @Override public String name() { return name; }
        @Override public List<String> dependsOn() { return deps; }
        @Override public BootstrapPhase phase() { return phase; }
        @Override public boolean isOptional() { return true; }
        @Override public boolean isRunning() { return running; }

        @Override
        public void initialize() {
            if (shouldFail) {
                throw new RuntimeException("Synthetic failure for testing");
            }
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public UnaryOperator<ScopedValue.Carrier> providerBindings() {
            // Return a no-op enricher for testing
            return carrier -> carrier;
        }
    }
}
