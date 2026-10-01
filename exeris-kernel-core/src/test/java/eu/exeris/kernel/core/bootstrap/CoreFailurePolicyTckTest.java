/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.exceptions.SubsystemException;
import eu.exeris.kernel.tck.contract.bootstrap.AbstractFailurePolicyTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Core binding for {@link AbstractFailurePolicyTck}. */
@DisplayName("Core: FailurePolicy TCK")
class CoreFailurePolicyTckTest extends AbstractFailurePolicyTck {

	public static final class FailurePolicySubsystemProvider implements SubsystemProvider {
		static List<Subsystem> subsystems = List.of();

		@Override
		public List<Subsystem> getSubsystems(ConfigProvider config) {
			return subsystems;
		}
	}

	@Test
	@DisplayName("DEGRADE removes optional start failure from active snapshots and shutdown skips it")
	void degradeRemovesOptionalStartFailureFromActiveSnapshots() throws Exception {
		AtomicInteger healthyStops = new AtomicInteger();
		AtomicInteger failedStops = new AtomicInteger();

		FailurePolicySubsystemProvider.subsystems = List.of(
				stub("memory", BootstrapPhase.FOUNDATION, false, false, List.of()),
				stub("events", BootstrapPhase.SERVICES, true, true, List.of("memory"), failedStops),
				stub("http", BootstrapPhase.SERVICES, false, false, List.of("memory"), healthyStops)
		);

		SubsystemOrchestrator orchestrator = newOrchestrator(SubsystemOrchestrator.FailurePolicy.DEGRADE);
		orchestrator.initialize(minimalConfig());
		orchestrator.start(minimalConfig());

		assertThat(orchestrator.isStarted()).isTrue();
		assertThat(orchestrator.subsystem("events")).isEmpty();
		assertThat(orchestrator.subsystems())
				.extracting(Subsystem::name)
				.containsExactly("memory", "http");

		orchestrator.shutdown();

		assertThat(healthyStops).hasValue(1);
		assertThat(failedStops).hasValue(0);
	}

	@Override
	protected boolean missingDependencyFailsInAllSelector() {
		FailurePolicySubsystemProvider.subsystems = List.of(
				stub("transport", BootstrapPhase.SERVICES, false, false, List.of("memory", "ghost")),
				stub("memory", BootstrapPhase.FOUNDATION, false, false, List.of())
		);

		SubsystemOrchestrator orchestrator = newOrchestrator(SubsystemOrchestrator.FailurePolicy.FAIL_FAST);
		try {
			orchestrator.initialize(minimalConfig());
			return false;
		} catch (SubsystemOrchestrator.BootstrapException _) {
			return true;
		}
	}

	@Override
	protected boolean degradeContinuesOnOptionalFailure() throws Exception {
		FailurePolicySubsystemProvider.subsystems = List.of(
				stub("memory", BootstrapPhase.FOUNDATION, false, false, List.of()),
				stub("events", BootstrapPhase.SERVICES, true, true, List.of("memory"))
		);

		SubsystemOrchestrator orchestrator = newOrchestrator(SubsystemOrchestrator.FailurePolicy.DEGRADE);
		orchestrator.initialize(minimalConfig());
		orchestrator.start(minimalConfig());

		return orchestrator.isStarted() && orchestrator.subsystem("events").isEmpty();
	}

	@Override
	protected boolean failFastAbortsOnOptionalFailure() throws Exception {
		FailurePolicySubsystemProvider.subsystems = List.of(
				stub("memory", BootstrapPhase.FOUNDATION, false, false, List.of()),
				stub("events", BootstrapPhase.SERVICES, true, true, List.of("memory"))
		);

		SubsystemOrchestrator orchestrator = newOrchestrator(SubsystemOrchestrator.FailurePolicy.FAIL_FAST);
		orchestrator.initialize(minimalConfig());
		try {
			orchestrator.start(minimalConfig());
			return false;
		} catch (SubsystemOrchestrator.BootstrapException _) {
			// The StructuredTaskScope.FailedException arm is gone with the preview type itself.
			// It existed because the bare open() joined with awaitAllSuccessfulOrThrow, whose join()
			// threw that unchecked preview class straight past the orchestrator's own failure
			// handling. A phase now always throws BootstrapException (ADR-066).
			return true;
		}
	}

	private static SubsystemOrchestrator newOrchestrator(SubsystemOrchestrator.FailurePolicy policy) {
		ClassLoader shimLoader = new SubsystemOrchestratorKahnTest.SubsystemProviderShimLoader(
				Thread.currentThread().getContextClassLoader(),
				FailurePolicySubsystemProvider.class.getName()
		);

		return SubsystemOrchestrator.builder()
				.failurePolicy(policy)
				.selector(BootstrapSelector.all())
				.classLoader(shimLoader)
				.build();
	}

	private static ConfigProvider minimalConfig() {
		return new ConfigProvider() {
			@Override
			public Supplier<KernelSettings> kernelSettings() {
				return KernelSettings::defaults;
			}

			@Override public Optional<String> getString(String key) { return Optional.empty(); }
			@Override public Optional<Integer> getInt(String key) { return Optional.empty(); }
			@Override public Optional<Long> getLong(String key) { return Optional.empty(); }
			@Override public Optional<Boolean> getBoolean(String key) { return Optional.empty(); }
			@Override public <T> Optional<T> get(String key, Class<T> type) { return Optional.empty(); }
			@Override
			public void watch(String file, String key, Consumer<Object> callback) {
				// Hot-reload is irrelevant for this bootstrap-only contract stub.
			}
		};
	}

	private static Subsystem stub(String name,
								  BootstrapPhase phase,
								  boolean optional,
								  boolean failOnStart,
								  List<String> deps) {
		return stub(name, phase, optional, failOnStart, deps, new AtomicInteger());
	}

	private static Subsystem stub(String name,
								  BootstrapPhase phase,
								  boolean optional,
								  boolean failOnStart,
								  List<String> deps,
								  AtomicInteger stopCount) {
		return new Subsystem() {
			private boolean running;

			@Override public String name() { return name; }
			@Override public List<String> dependsOn() { return deps; }
			@Override public BootstrapPhase phase() { return phase; }

			@Override
			public void initialize() {
				// no-op
			}

			@Override
			public void start() {
				if (failOnStart) {
					throw new SubsystemException(name, SubsystemException.Phase.START, "boom");
				}
				running = true;
			}

			@Override
			public void stop() {
				running = false;
				stopCount.incrementAndGet();
			}

			@Override
			public boolean isRunning() {
				return running;
			}

			@Override
			public boolean isOptional() {
				return optional;
			}
		};
	}
}



