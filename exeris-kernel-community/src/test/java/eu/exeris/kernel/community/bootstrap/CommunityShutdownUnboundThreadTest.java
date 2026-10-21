/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor;
import eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor.SubsystemState;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code stop()} runs on the JVM shutdown hook's thread or an embedding host's lifecycle thread, neither
 * of which carries the kernel's {@code ScopedValue} bindings. Booting the real Community subsystems and
 * stopping them from a thread with no bindings finds a {@code stop()} that reads a binding it no longer has,
 * including through a callee: such a {@code stop()} throws, and a subsystem whose {@code stop()} threw is
 * not marked {@code STOPPED}.
 *
 * <p>The stand-alone {@code transport} subsystem is not part of the scenario: its {@code initialize()} builds
 * the engine while the memory allocator is not yet bound, which that engine refuses, so it cannot boot beside
 * the other subsystems. The HTTP subsystem owns its own listener, which this test does stop.
 */
@DisplayName("Community: shutdown() from a thread that carries no kernel bindings")
@SuppressWarnings("PMD.AvoidUsingHardCodedIP")
class CommunityShutdownUnboundThreadTest {

    private static final long AWAIT_SECONDS = 60;

    /** Hands the orchestrator the real Community subsystems, and keeps them so the test can read their state. */
    public static final class RecordingProvider implements SubsystemProvider {

        static volatile List<Subsystem> created = List.of();

        /** Required public no-arg constructor for {@link java.util.ServiceLoader}. */
        public RecordingProvider() {
            // ServiceLoader-mandated no-op constructor.
        }

        @Override
        public List<Subsystem> getSubsystems(ConfigProvider config) {
            created = new CommunitySubsystemProvider().getSubsystems(config);
            return created;
        }
    }

    /** Serves exactly one {@code SubsystemProvider} registration, hiding the classpath's own. */
    private static final class RecordingProviderShim extends ClassLoader {

        private static final String SPI_RESOURCE =
                "META-INF/services/eu.exeris.kernel.spi.bootstrap.SubsystemProvider";

        RecordingProviderShim(ClassLoader parent) {
            super(parent);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return SPI_RESOURCE.equals(name) ? providerBytes() : super.getResourceAsStream(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (!SPI_RESOURCE.equals(name)) {
                return super.getResources(name);
            }
            return Collections.enumeration(List.of(URL.of(URI.create("memory:///" + name), new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL u) {
                    return new URLConnection(u) {
                        @Override
                        public void connect() {
                            // In-memory; nothing to connect.
                        }

                        @Override
                        public InputStream getInputStream() {
                            return providerBytes();
                        }
                    };
                }
            })));
        }

        private static InputStream providerBytes() {
            return new ByteArrayInputStream(RecordingProvider.class.getName().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("every running subsystem reaches STOPPED when shutdown() is called from an unbound thread")
    void everyRunningSubsystemStopsFromAnUnboundThread() throws Exception {
        int port = reservePort();
        Map<String, String> published = new LinkedHashMap<>();
        for (String prefix : List.of("", "exeris.")) {
            published.put(prefix + "http.mode", "SERVER");
            published.put(prefix + "http.bindHost", "127.0.0.1");
            published.put(prefix + "http.port", Integer.toString(port));
        }
        // Websocket is off by default, and a subsystem that is off reports isRunning() false and is never
        // stopped, so it is switched on to put its stop() under the test.
        published.put("exeris.websocket.enabled", "true");
        published.put("exeris.websocket.bindHost", "127.0.0.1");
        published.put("exeris.websocket.port", Integer.toString(reservePort()));
        published.put("exeris.persistence.jdbcUrl", "jdbc:h2:mem:unbound-stop;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        published.put("exeris.persistence.runMigrations", "true");
        Map<String, String> saved = new LinkedHashMap<>();
        published.forEach((key, value) -> {
            saved.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
        try {
            runScenario();
        } finally {
            saved.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    private static void runScenario() throws Exception {
        KernelBootstrap bootstrap = KernelBootstrap.builder()
                .selector(BootstrapSelector.forNames("http", "scheduling", "security", "storage", "websocket",
                        "persistence", "events", "flow", "graph", "crypto"))
                .classLoader(new RecordingProviderShim(Thread.currentThread().getContextClassLoader()))
                .registerShutdownHook(false)
                .build();
        AtomicReference<KernelHealthMonitor> monitor = new AtomicReference<>();
        AtomicReference<List<String>> running = new AtomicReference<>(List.of());
        AtomicReference<Throwable> bootFailure = new AtomicReference<>();
        CountDownLatch inMain = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread bootThread = Thread.ofPlatform().daemon(true).name("community-unbound-boot").unstarted(() -> {
            try {
                bootstrap.boot(() -> {
                    KernelHealthMonitor health = bootstrap.healthMonitor();
                    monitor.set(health);
                    List<String> names = new ArrayList<>();
                    for (Subsystem subsystem : RecordingProvider.created) {
                        // Only a subsystem that reports isRunning() is stopped, so only it is judged.
                        if (subsystem.isRunning() && health.stateOf(subsystem.name()) == SubsystemState.RUNNING) {
                            names.add(subsystem.name());
                        }
                    }
                    running.set(names);
                    inMain.countDown();
                    awaitUninterruptibly(release);
                });
            } catch (Throwable t) { // NOPMD — handed to the asserting thread
                bootFailure.set(t);
                inMain.countDown();
            }
        });
        bootThread.start();
        try {
            assertThat(inMain.await(AWAIT_SECONDS, TimeUnit.SECONDS)).as("kernelMain entered").isTrue();
            assertThat(bootFailure.get()).as("what boot() threw").isNull();
            assertThat(running.get()).as("subsystems RUNNING in kernelMain").contains("memory", "http");
            assertThat(KernelProviders.CURRENT_CONFIG.isBound()).as("the calling thread carries no bindings")
                    .isFalse();

            bootstrap.shutdown();

            for (String name : running.get()) {
                assertThat(monitor.get().stateOf(name)).as("state of %s after shutdown()", name)
                        .isEqualTo(SubsystemState.STOPPED);
            }
        } finally {
            release.countDown();
            bootThread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        }
        assertThat(bootThread.isAlive()).as("boot thread returned").isFalse();
        assertThat(bootFailure.get()).as("what boot() threw").isNull();
    }

    private static int reservePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
