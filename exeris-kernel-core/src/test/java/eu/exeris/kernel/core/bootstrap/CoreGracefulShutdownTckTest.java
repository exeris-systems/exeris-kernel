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
import eu.exeris.kernel.tck.contract.bootstrap.AbstractGracefulShutdownTck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core binding for {@link AbstractGracefulShutdownTck}: six stub subsystems, one per canonical engine,
 * booted by the real {@link KernelBootstrap}. Each stub's {@code stop()} closes a tracked engine, so the
 * TCK observes the order in which {@link SubsystemOrchestrator} stops real subsystems.
 *
 * <p>The subsystems form a linear dependency chain and are discovered in an order that differs from the
 * canonical close order, so a stop in discovery order or in dependency order fails the contract.
 */
@DisplayName("Core: Graceful Shutdown TCK")
class CoreGracefulShutdownTckTest extends AbstractGracefulShutdownTck {

    private static final long JOIN_MILLIS = TimeUnit.SECONDS.toMillis(30);

    /**
     * In-process provider supplying the stub subsystems to the bootstrap sequence.
     */
    public static final class ShutdownTckSubsystemProvider implements SubsystemProvider {
        static volatile List<Subsystem> subsystems = List.of();

        /** Creates the provider; the bootstrap instantiates it through its service shim. */
        public ShutdownTckSubsystemProvider() {
            super();
        }

        @Override
        public List<Subsystem> getSubsystems(ConfigProvider config) {
            return subsystems;
        }
    }

    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<Throwable> bootFailure = new AtomicReference<>();
    private volatile Thread bootThread;

    @Override
    protected int drainingRequestCount() {
        return 10_000;
    }

    @Override
    protected KernelHandle buildAndStartKernel() {
        CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
        PersistenceSink sink = new PersistenceSink();
        TransportIngress ingress = new TransportIngress(sink);
        AtomicInteger seq = new AtomicInteger();

        TrackedEngine memory = new TrackedEngine("Memory", null).withObservedOrder(order);
        TrackedEngine graph = new TrackedEngine("Graph", null).withObservedOrder(order);
        TrackedEngine events = new TrackedEngine("Events", null).withObservedOrder(order);
        TrackedEngine flow = new TrackedEngine("Flow", null).withObservedOrder(order);
        TrackedEngine persistence = new TrackedEngine("Persistence", sink).withObservedOrder(order);
        TrackedEngine transport = new TrackedEngine("Transport", ingress).withObservedOrder(order);

        Subsystem memoryStub = new StubSubsystem("memory", BootstrapPhase.FOUNDATION, null, memory);
        Subsystem graphStub = new StubSubsystem("graph", BootstrapPhase.SERVICES, "memory", graph);
        Subsystem eventsStub = new StubSubsystem("events", BootstrapPhase.SERVICES, "graph", events);
        Subsystem flowStub = new StubSubsystem("flow", BootstrapPhase.SERVICES, "events", flow);
        Subsystem persistenceStub = new StubSubsystem("persistence", BootstrapPhase.SERVICES, "flow", persistence);
        Subsystem transportStub = new StubSubsystem("transport", BootstrapPhase.RUNTIME, "persistence", transport);
        ShutdownTckSubsystemProvider.subsystems = List.of(
                flowStub, memoryStub, transportStub, graphStub, persistenceStub, eventsStub);

        ClassLoader shim = new KernelBootstrapTest.DualServiceShimLoader(
                Thread.currentThread().getContextClassLoader(),
                Map.of(
                        "eu.exeris.kernel.spi.config.ConfigProvider",
                        KernelBootstrapTest.StubConfigProvider.class.getName(),
                        "eu.exeris.kernel.spi.bootstrap.SubsystemProvider",
                        ShutdownTckSubsystemProvider.class.getName()
                ));
        KernelBootstrap bootstrap = KernelBootstrap.builder()
                .failurePolicy(SubsystemOrchestrator.FailurePolicy.FAIL_FAST)
                .selector(BootstrapSelector.all())
                .classLoader(shim)
                .build();

        bootThread = Thread.ofPlatform().daemon(true).name("core-graceful-shutdown-tck-boot").unstarted(() -> {
            try {
                bootstrap.boot(() -> {
                    started.countDown();
                    awaitUninterruptibly(release);
                });
            } catch (Throwable t) {
                bootFailure.set(t);
                started.countDown();
            }
        });
        bootThread.start();

        try {
            assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the kernel to boot", ex);
        }
        assertThat(bootFailure.get()).isNull();

        return new KernelHandle(transport, persistence, flow, events, graph, memory,
                () -> ingress.accept(seq.incrementAndGet()),
                () -> sink.confirmed.get(),
                bootstrap::shutdown,
                order);
    }

    @AfterEach
    void releaseKernelMain() {
        release.countDown();
        joinBootThread();
        assertThat(bootFailure.get()).isNull();
    }

    private void joinBootThread() {
        Thread thread = bootThread;
        if (thread == null) {
            return;
        }
        try {
            thread.join(JOIN_MILLIS);
            assertThat(thread.isAlive()).as("boot thread ended within %d ms of the release", JOIN_MILLIS).isFalse();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while joining the boot thread", ex);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException _) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** A subsystem whose {@code stop()} closes the tracked engine it was built with. */
    private static final class StubSubsystem implements Subsystem {
        private final String name;
        private final BootstrapPhase phase;
        private final List<String> dependsOn;
        private final TrackedEngine engine;
        private volatile boolean running;

        StubSubsystem(String name, BootstrapPhase phase, String dependency, TrackedEngine engine) {
            this.name = name;
            this.phase = phase;
            this.dependsOn = dependency == null ? List.of() : List.of(dependency);
            this.engine = engine;
        }

        @Override public String name() { return name; }
        @Override public BootstrapPhase phase() { return phase; }
        @Override public List<String> dependsOn() { return dependsOn; }
        @Override public void initialize() { /* nothing to initialise */ }
        @Override public void start() { running = true; }

        @Override
        public void stop() {
            running = false;
            engine.close();
        }

        @Override public boolean isRunning() { return running; }
    }

    /** Persistence fake: writes are accepted until the sink is closed. */
    private static final class PersistenceSink implements AutoCloseable {
        final AtomicInteger confirmed = new AtomicInteger();
        private volatile boolean closed;

        void write() {
            if (closed) {
                throw new IllegalStateException("sink closed");
            }
            confirmed.incrementAndGet();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * Transport fake: accepted work is queued and written to the sink only when the ingress closes, so
     * the request sender returns before its write is durable.
     */
    private static final class TransportIngress implements AutoCloseable {
        private final PersistenceSink sink;
        private final ConcurrentLinkedQueue<Integer> inFlight = new ConcurrentLinkedQueue<>();
        private final AtomicInteger lost = new AtomicInteger();
        private volatile boolean accepting = true;

        TransportIngress(PersistenceSink sink) {
            this.sink = sink;
        }

        void accept(int id) {
            if (!accepting) {
                throw new IllegalStateException("ingress closed");
            }
            inFlight.add(id);
        }

        @Override
        public void close() {
            accepting = false;
            while (inFlight.poll() != null) {
                try {
                    sink.write();
                } catch (IllegalStateException _) {
                    lost.incrementAndGet();
                }
            }
        }
    }
}
