/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.scheduling.JobDescriptor;
import eu.exeris.kernel.spi.scheduling.JobScheduler;
import eu.exeris.kernel.spi.scheduling.JobSchedulerProvider;
import eu.exeris.kernel.spi.scheduling.JobTrigger;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bootstrap wiring for the scheduling subsystem (ADR-057 §1).
 *
 * <p>The point of these cases is not that a scheduler can be constructed — {@code
 * AbstractJobSchedulerTck} covers that — but that the one bound into {@code KernelProviders} is
 * usable, that the drain runs on subsystem stop, and that which provider won selection is recorded
 * rather than left to be inferred.
 */
@DisplayName("Community: scheduling subsystem bootstrap")
class CommunitySchedulingSubsystemTest {

    private static final String SELECTED = "eu.exeris.kernel.scheduling.SchedulingBootstrapSelected";
    private static final long TIMEOUT_SECONDS = 5L;
    private static final String TENANT = "tenant-alpha";

    @Nested
    @DisplayName("Subsystem contract")
    class Contract {

        @Test
        @DisplayName("declares no dependencies, because it genuinely has none")
        void declaresPlacement() {
            CommunitySchedulingSubsystem subsystem = new CommunitySchedulingSubsystem();

            assertThat(subsystem.name()).isEqualTo("scheduling");
            assertThat(subsystem.phase()).isEqualTo(BootstrapPhase.SERVICES);
            assertThat(subsystem.dependsOn())
                    .as("the in-process driver allocates nothing off-heap, opens no connection and "
                            + "reads no store; identity crosses at submit time from the caller's own "
                            + "scope, which no boot order can influence. A dependency declared for "
                            + "narrative tidiness constrains the graph on a fiction — the first "
                            + "attempt here named a 'security' subsystem that does not exist, and "
                            + "the boot-order suite caught it.")
                    .isEmpty();
        }

        @Test
        @DisplayName("binds both slots and the bound scheduler actually runs a job")
        void bindsUsableScheduler() throws InterruptedException {
            CommunitySchedulingSubsystem subsystem = new CommunitySchedulingSubsystem();
            CountDownLatch fired = new CountDownLatch(1);
            AtomicReference<String> seenTenant = new AtomicReference<>();

            withConfig(() -> {
                subsystem.initialize();
                subsystem.start();
                subsystem.providerBindings().apply(ScopedValue.where(
                                KernelProviders.STORAGE_CONTEXT,
                                ImmutableStorageContext.shared(TENANT)))
                        .run(() -> {
                            assertThat(KernelProviders.JOB_SCHEDULER_PROVIDER.isBound()).isTrue();
                            assertThat(KernelProviders.JOB_SCHEDULER_PROVIDER.get())
                                    .isInstanceOf(JobSchedulerProvider.class);
                            KernelProviders.JOB_SCHEDULER.get().submit(new JobDescriptor(
                                    "bootstrap-probe", new JobTrigger.OneShot(Duration.ZERO),
                                    () -> {
                                        seenTenant.set(KernelProviders.STORAGE_CONTEXT.get()
                                                .isolationKey().orElse(null));
                                        fired.countDown();
                                    }));
                        });
            });

            assertThat(fired.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("a slot bound to a scheduler that cannot run anything is wiring, not a "
                            + "subsystem")
                    .isTrue();
            assertThat(seenTenant.get()).isEqualTo(TENANT);
            withConfig(subsystem::stop);
        }

        @Test
        @DisplayName("stop() drains the scheduler, so no job survives subsystem shutdown")
        void stopDrains() throws InterruptedException {
            CommunitySchedulingSubsystem subsystem = new CommunitySchedulingSubsystem();
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<Boolean> finished = new AtomicReference<>(false);
            AtomicReference<JobScheduler> bound = new AtomicReference<>();

            withConfig(() -> {
                subsystem.initialize();
                subsystem.start();
                subsystem.providerBindings().apply(ScopedValue.where(
                                KernelProviders.STORAGE_CONTEXT,
                                ImmutableStorageContext.shared(TENANT)))
                        .run(() -> {
                            bound.set(KernelProviders.JOB_SCHEDULER.get());
                            bound.get().submit(new JobDescriptor(
                                    "draining", new JobTrigger.OneShot(Duration.ZERO), () -> {
                                        started.countDown();
                                        spin();
                                        finished.set(true);
                                    }));
                        });
            });

            assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            withConfig(subsystem::stop);

            assertThat(finished.get())
                    .as("ADR-057 §6 — subsystem stop must drain, not abandon; a job still running "
                            + "after shutdown returned is outside the lifecycle boundary")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Selection is recorded")
    class Selection {

        @Test
        @DisplayName("commits which provider bootstrap chose")
        void emitsSelectionEvent(@TempDir Path tmp) throws Exception {
            CommunitySchedulingSubsystem subsystem = new CommunitySchedulingSubsystem();
            Path jfr = tmp.resolve("scheduling-bootstrap.jfr");

            try (Recording recording = new Recording()) {
                recording.enable(SELECTED);
                recording.start();
                withConfig(() -> {
                    subsystem.initialize();
                    subsystem.start();
                });
                recording.stop();
                recording.dump(jfr);
            }
            withConfig(subsystem::stop);

            List<RecordedEvent> events = RecordingFile.readAllEvents(jfr).stream()
                    .filter(e -> SELECTED.equals(e.getEventType().getName()))
                    .toList();

            assertThat(events)
                    .as("which provider won a ServiceLoader race is invisible afterwards and is the "
                            + "first thing anyone asks when a scheduler misbehaves")
                    .isNotEmpty();
            RecordedEvent selected = events.getFirst();
            assertThat(selected.getString("providerId")).isEqualTo("job-loom-community");
            assertThat(selected.getInt("priority")).isZero();
            assertThat(selected.getString("schedulerName")).isEqualTo("default");
        }
    }

    private static void withConfig(Runnable action) {
        ScopedValue.where(KernelProviders.CURRENT_CONFIG, new EmptyConfigProvider()).run(action);
    }

    /** Long enough that an unwaited-for body is still running when stop() returns. */
    private static void spin() {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50L);
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }

    /** Supplies nothing, so every key falls through to its documented default. */
    private static final class EmptyConfigProvider implements ConfigProvider {

        @Override
        public Supplier<ConfigProvider.KernelSettings> kernelSettings() {
            return ConfigProvider.KernelSettings::defaults;
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
            return Optional.empty();
        }

        @Override
        public <T> Optional<T> get(String key, Class<T> type) {
            return Optional.empty();
        }

        @Override
        public void watch(String file, String key, Consumer<Object> callback) {
            // no-op
        }
    }
}
