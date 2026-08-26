/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.flow.CommunityFlowSnapshotStore;
import eu.exeris.kernel.community.flow.JdbcFlowSnapshotStore;
import eu.exeris.kernel.community.persistence.CommunityPersistenceProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.flow.model.FlowSnapshotStore;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-022 §7 — locks the wiring invariant for {@code FLOW_SNAPSHOT_STORE}:
 * the JDBC-backed durable store is selected when a {@link PersistenceEngine}
 * is registered with {@link CommunityBootstrapServices}; the in-memory store
 * is the fallback when no engine is available; nothing is bound at all when
 * {@code flow.persistenceEnabled=false}.
 *
 * <p>This pins the regression fix for the 0.7.0 wiring gap where
 * {@code CommunityFlowSubsystem.initialize()} always constructed
 * {@link CommunityFlowSnapshotStore} regardless of persistence availability,
 * silently demoting durable saga state to heap state.
 */
@DisplayName("CommunityFlowSubsystem snapshot store wiring (ADR-022)")
class CommunityFlowSubsystemSnapshotStoreWiringTest {

    @AfterEach
    void clearBootstrapRegistry() {
        CommunityBootstrapServices.clearSharedPersistenceEngine();
    }

    @Test
    @DisplayName("persistenceEnabled=true + PersistenceEngine present → JdbcFlowSnapshotStore")
    void wiresJdbcStoreWhenEngineAvailable() {
        try (PersistenceEngine engine = newH2Engine("jdbc_wired")) {
            CommunityBootstrapServices.setSharedPersistenceEngine(engine);
            FlowSnapshotStore bound = exerciseSubsystem(true);
            assertThat(bound).isInstanceOf(JdbcFlowSnapshotStore.class);
        }
    }

    @Test
    @DisplayName("persistenceEnabled=true + no PersistenceEngine → CommunityFlowSnapshotStore fallback")
    void fallsBackToInMemoryWhenEngineAbsent() {
        // Registry left empty by @AfterEach contract; explicitly assert the precondition.
        assertThat(CommunityBootstrapServices.getSharedPersistenceEngine()).isNull();
        FlowSnapshotStore bound = exerciseSubsystem(true);
        assertThat(bound).isInstanceOf(CommunityFlowSnapshotStore.class);
    }

    @Test
    @DisplayName("persistenceEnabled=false → FLOW_SNAPSHOT_STORE not bound at all")
    void noStoreBoundWhenPersistenceDisabled() {
        try (PersistenceEngine engine = newH2Engine("jdbc_disabled")) {
            CommunityBootstrapServices.setSharedPersistenceEngine(engine);
            FlowSnapshotStore bound = exerciseSubsystem(false);
            assertThat(bound)
                    .as("flow.persistenceEnabled=false MUST short-circuit the snapshot-store decision; "
                            + "no FLOW_SNAPSHOT_STORE binding may appear in the provider carrier")
                    .isNull();
        }
    }

    private static FlowSnapshotStore exerciseSubsystem(boolean persistenceEnabled) {
        WiringTestConfigProvider config = new WiringTestConfigProvider(persistenceEnabled);
        CommunityFlowSubsystem subsystem = new CommunityFlowSubsystem();
        AtomicReference<FlowSnapshotStore> captured = new AtomicReference<>();
        ScopedValue.where(KernelProviders.CURRENT_CONFIG, config).run(() -> {
            subsystem.initialize();
            ScopedValue.Carrier composed = subsystem.providerBindings().apply(
                    ScopedValue.where(KernelProviders.CURRENT_CONFIG, config));
            composed.run(() -> {
                if (KernelProviders.FLOW_SNAPSHOT_STORE.isBound()) {
                    captured.set(KernelProviders.FLOW_SNAPSHOT_STORE.get());
                }
            });
        });
        return captured.get();
    }

    private static PersistenceEngine newH2Engine(String namespace) {
        PersistenceConfig cfg = new PersistenceConfig(
                "jdbc:h2:mem:flow_wiring_" + namespace + "_" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
                4,
                1,
                5_000L,
                60_000L,
                600_000L,
                false,
                false,
                false,
                0,
                Map.of("run.migrations", "true"));
        return new CommunityPersistenceProvider().createEngine(cfg);
    }

    /**
     * Minimal {@link ConfigProvider} stub returning only the {@code flow.persistenceEnabled}
     * value plus engine-config defaults — every other lookup returns {@code Optional.empty()},
     * which forces {@code FlowEngineConfig.defaults} to populate the rest.
     */
    private static final class WiringTestConfigProvider implements ConfigProvider {

        private final boolean persistenceEnabled;

        private WiringTestConfigProvider(boolean persistenceEnabled) {
            this.persistenceEnabled = persistenceEnabled;
        }

        @Override
        public Supplier<KernelSettings> kernelSettings() {
            return KernelSettings::defaults;
        }

        @Override
        public Optional<String> getString(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> getInt(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<Long> getLong(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<Boolean> getBoolean(String key) {
            return switch (key) {
                case "flow.persistenceEnabled" -> Optional.of(persistenceEnabled);
                default -> Optional.empty();
            };
        }

        @Override
        public <T> Optional<T> get(String key, Class<T> type) {
            return Optional.empty();
        }

        @Override
        public void watch(String file, String key, Consumer<Object> callback) {
            // no-op for tests
        }
    }
}
