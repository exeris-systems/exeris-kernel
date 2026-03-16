/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
		} catch (SubsystemOrchestrator.BootstrapException | StructuredTaskScope.FailedException _) {
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



