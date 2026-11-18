/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.persistence.CommunityPersistenceProvider;
import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.exceptions.SubsystemException;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code event.durableLogEnabled}: whether {@code events} drags {@code persistence} into a boot.
 *
 * <p>The edge exists only so the JDBC durable event log can borrow the shared persistence engine, and
 * it opens a connection pool whether or not anyone wants that log. These cases pin the key at three
 * levels: the declared dependency list, the {@code initialize()} bindings, and a real boot that selects
 * {@code events} alone.
 */
@DisplayName("CommunityEventsSubsystem — event.durableLogEnabled")
class CommunityEventsSubsystemTest {

    private static final String KEY = "event.durableLogEnabled";
    private static final String PROPERTY = "exeris." + KEY;

    @AfterEach
    void clearBootstrapRegistry() {
        CommunityBootstrapServices.clearSharedPersistenceEngine();
    }

    @Nested
    @DisplayName("dependsOn()")
    class DependsOn {

        @Test
        @DisplayName("default config keeps memory and persistence")
        void defaultConfigKeepsPersistence() {
            assertThat(new CommunityEventsSubsystem(config(Map.of())).dependsOn())
                    .containsExactly("memory", "persistence");
        }

        @Test
        @DisplayName("null config means the default")
        void nullConfigMeansDefault() {
            assertThat(new CommunityEventsSubsystem(null).dependsOn())
                    .containsExactly("memory", "persistence");
        }

        @Test
        @DisplayName("an explicit true keeps persistence")
        void explicitTrueKeepsPersistence() {
            assertThat(new CommunityEventsSubsystem(config(Map.of(KEY, "true"))).dependsOn())
                    .containsExactly("memory", "persistence");
        }

        @Test
        @DisplayName("false drops persistence and leaves exactly memory")
        void falseDropsPersistence() {
            assertThat(new CommunityEventsSubsystem(config(Map.of(KEY, "false"))).dependsOn())
                    .containsExactly("memory");
        }

        @Test
        @DisplayName("repeated calls return equal lists, as the Subsystem contract requires")
        void repeatedCallsAreEqual() {
            for (String value : List.of("true", "false")) {
                CommunityEventsSubsystem subsystem =
                        new CommunityEventsSubsystem(config(Map.of(KEY, value)));
                assertThat(subsystem.dependsOn())
                        .as("event.durableLogEnabled=%s", value)
                        .isEqualTo(subsystem.dependsOn())
                        .isEqualTo(subsystem.dependsOn());
            }
        }

        @Test
        @DisplayName("the provider hands its config to the events subsystem")
        void providerPassesConfigThrough() {
            assertThat(eventsFrom(new CommunitySubsystemProvider().getSubsystems(config(Map.of(KEY, "false"))))
                    .dependsOn()).containsExactly("memory");
            assertThat(eventsFrom(new CommunitySubsystemProvider().getSubsystems(config(Map.of())))
                    .dependsOn()).containsExactly("memory", "persistence");
            assertThat(eventsFrom(new CommunitySubsystemProvider().getSubsystems(null))
                    .dependsOn()).containsExactly("memory", "persistence");
        }
    }

    @Nested
    @DisplayName("A value that is not a boolean")
    class Malformed {

        @Test
        @DisplayName("does not escape from construction or dependsOn(); the default edge is kept")
        void constructionDoesNotThrow() {
            assertThatCode(() -> {
                CommunityEventsSubsystem subsystem =
                        new CommunityEventsSubsystem(config(Map.of(KEY, "flase")));
                assertThat(subsystem.dependsOn()).containsExactly("memory", "persistence");
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("fails initialize() with a SubsystemException that names the key")
        void initializeFailsNamingTheKey() {
            CommunityEventsSubsystem subsystem = new CommunityEventsSubsystem(config(Map.of(KEY, "yes")));

            assertThatThrownBy(subsystem::initialize)
                    .isInstanceOf(SubsystemException.class)
                    .satisfies(thrown -> assertThat(((SubsystemException) thrown).detail()).contains(KEY));
        }

        @Test
        @DisplayName("a provider that throws while reading the key is treated the same way")
        void throwingProviderFailsInitialize() {
            ConfigProvider throwing = new MapConfig(Map.of()) {
                @Override
                public Optional<String> getString(String key) {
                    throw new IllegalArgumentException("not a boolean");
                }
            };
            CommunityEventsSubsystem subsystem = new CommunityEventsSubsystem(throwing);

            assertThat(subsystem.dependsOn()).containsExactly("memory", "persistence");
            assertThatThrownBy(subsystem::initialize).isInstanceOf(SubsystemException.class);
        }
    }

    @Nested
    @DisplayName("initialize() and the durable-log bindings")
    class Bindings {

        @Test
        @DisplayName("false: no appender/reader is bound even though a shared persistence engine exists")
        void falseLeavesLogUnbound() {
            try (PersistenceEngine engine = newH2Engine("off")) {
                CommunityBootstrapServices.setSharedPersistenceEngine(engine);

                BoundSlots slots = exercise(config(Map.of(KEY, "false")));

                assertThat(slots.eventEngineBound()).as("the bus itself is unaffected").isTrue();
                assertThat(slots.appenderBound())
                        .as("the shared engine must not even be looked up").isFalse();
                assertThat(slots.readerBound()).isFalse();
            }
        }

        @Test
        @DisplayName("true: appender and reader are bound when a shared persistence engine exists")
        void trueBindsLogWhenEnginePresent() {
            try (PersistenceEngine engine = newH2Engine("on")) {
                CommunityBootstrapServices.setSharedPersistenceEngine(engine);

                BoundSlots slots = exercise(config(Map.of()));

                assertThat(slots.eventEngineBound()).isTrue();
                assertThat(slots.appenderBound()).isTrue();
                assertThat(slots.readerBound()).isTrue();
            }
        }

        @Test
        @DisplayName("true: without a shared persistence engine the slots stay unbound, as before")
        void trueWithoutEngineLeavesLogUnbound() {
            assertThat(CommunityBootstrapServices.getSharedPersistenceEngine()).isNull();

            BoundSlots slots = exercise(config(Map.of()));

            assertThat(slots.eventEngineBound()).isTrue();
            assertThat(slots.appenderBound()).isFalse();
            assertThat(slots.readerBound()).isFalse();
        }
    }

    @Nested
    @DisplayName("A real boot")
    class RealBoot {

        @Test
        @DisplayName("selecting events alone with durableLogEnabled=false boots no persistence and runs the bus")
        void eventsBootsWithoutPersistence() throws Exception {
            String previous = System.getProperty(PROPERTY);
            System.setProperty(PROPERTY, "false");
            AtomicBoolean persistenceEngineBound = new AtomicBoolean(true);
            AtomicBoolean persistenceProviderBound = new AtomicBoolean(true);
            AtomicBoolean appenderBound = new AtomicBoolean(true);
            AtomicBoolean engineBound = new AtomicBoolean(false);
            CountDownLatch delivered = new CountDownLatch(1);
            try {
                KernelBootstrap.builder()
                        .selector(BootstrapSelector.forNames("events"))
                        .build()
                        .boot(() -> {
                            persistenceEngineBound.set(KernelProviders.PERSISTENCE_ENGINE.isBound());
                            persistenceProviderBound.set(KernelProviders.PERSISTENCE_PROVIDER.isBound());
                            appenderBound.set(KernelProviders.EVENT_STREAM_APPENDER.isBound());
                            engineBound.set(KernelProviders.EVENT_ENGINE.isBound());
                            EventEngine engine = KernelProviders.eventEngine();
                            engine.registry().register(EventTypeSpec.of("durable.log.off.probe", 9201));
                            engine.bus().subscribe("durable.log.off.probe", (descriptor, payload) -> {
                                try (payload) {
                                    delivered.countDown();
                                }
                            });
                            UUID id = UUID.randomUUID();
                            engine.bus().publish(new EventDescriptor(
                                    id.getMostSignificantBits(), id.getLeastSignificantBits(),
                                    0L, 0L, 9201, 0x02, System.currentTimeMillis()), EventPayload.empty());
                            awaitQuietly(delivered);
                        });
            } finally {
                if (previous == null) {
                    System.clearProperty(PROPERTY);
                } else {
                    System.setProperty(PROPERTY, previous);
                }
            }

            assertThat(engineBound).as("the event engine is bound").isTrue();
            assertThat(delivered.getCount()).as("the bus is running and dispatches").isZero();
            assertThat(persistenceEngineBound).as("PERSISTENCE_ENGINE must not be bound").isFalse();
            assertThat(persistenceProviderBound).as("the persistence subsystem must not have booted").isFalse();
            assertThat(appenderBound).as("no durable event log without persistence").isFalse();
        }
    }

    private record BoundSlots(boolean eventEngineBound, boolean appenderBound, boolean readerBound) {
    }

    private static BoundSlots exercise(ConfigProvider config) {
        CommunityEventsSubsystem subsystem = new CommunityEventsSubsystem(config);
        AtomicReference<BoundSlots> captured = new AtomicReference<>();
        try {
            ScopedValue.where(KernelProviders.CURRENT_CONFIG, config).run(() -> {
                subsystem.initialize();
                subsystem.providerBindings()
                        .apply(ScopedValue.where(KernelProviders.CURRENT_CONFIG, config))
                        .run(() -> captured.set(new BoundSlots(
                                KernelProviders.EVENT_ENGINE.isBound(),
                                KernelProviders.EVENT_STREAM_APPENDER.isBound(),
                                KernelProviders.EVENT_STREAM_READER.isBound())));
            });
        } finally {
            subsystem.stop();
        }
        return captured.get();
    }

    private static Subsystem eventsFrom(List<Subsystem> subsystems) {
        return subsystems.stream()
                .filter(CommunityEventsSubsystem.class::isInstance)
                .findFirst()
                .orElseThrow();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("event was not delivered within 10 s");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting delivery", interrupted);
        }
    }

    private static PersistenceEngine newH2Engine(String namespace) {
        PersistenceConfig cfg = new PersistenceConfig(
                "jdbc:h2:mem:events_durable_" + namespace + "_" + System.nanoTime()
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

    private static ConfigProvider config(Map<String, String> values) {
        return new MapConfig(values);
    }

    /** Minimal string-backed {@link ConfigProvider}; booleans parse as the Community provider's do. */
    private static class MapConfig implements ConfigProvider {

        private final Map<String, String> values;

        MapConfig(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public Supplier<KernelSettings> kernelSettings() {
            return KernelSettings::defaults;
        }

        @Override
        public Optional<String> getString(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public Optional<Integer> getInt(String key) {
            return getString(key).map(Integer::parseInt);
        }

        @Override
        public Optional<Long> getLong(String key) {
            return getString(key).map(Long::parseLong);
        }

        @Override
        public Optional<Boolean> getBoolean(String key) {
            return getString(key).map(Boolean::parseBoolean);
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
