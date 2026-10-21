/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.UnaryOperator;

/**
 * Child-JVM entry point of {@link KernelSigtermShutdownTest}: boots the real Community HTTP subsystem
 * and reports on its streams what the kernel does when the process is signalled.
 *
 * <p>Standard output carries {@code READY <port>} once {@code kernelMain} runs and {@code IN-FLIGHT}
 * when the {@code /hold} handler has a request; standard error carries {@code START <name>} after each
 * subsystem's {@code start()} returns and {@code STOP-BEGIN <name>} / {@code STOP-END <name>} around each
 * {@code stop()}.
 *
 * <p>Argument 0 is the mode: {@code hook-on}, {@code hook-off}, {@code exit-main} (a line on standard
 * input makes {@code kernelMain} call {@code System.exit(7)}) or {@code exit-worker} (a line on standard
 * input makes a thread of an extra subsystem call {@code System.exit(9)}).
 */
@SuppressWarnings({"PMD.SystemPrintln", "PMD.DoNotTerminateVM", "PMD.AvoidUsingHardCodedIP"})
public final class SigtermChildKernel {

    private static final String MODE_HOOK_OFF = "hook-off";
    private static final String MODE_EXIT_MAIN = "exit-main";
    private static final String MODE_EXIT_WORKER = "exit-worker";
    private static final String EXIT_PROBE = "exit-probe";
    private static final long HOLD_MILLIS = 2_000;

    private static volatile boolean exitWorkerMode;

    private SigtermChildKernel() {
        // Entry point only; never instantiated.
    }

    /**
     * Boots the kernel and blocks in {@code kernelMain} until the process is signalled or exited.
     *
     * @param args one argument, the mode
     * @throws Exception if the harness itself fails
     */
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        // A child outliving the test JVM that forked it would hold its port and block forever.
        ProcessHandle.current().parent()
                .ifPresent(parent -> parent.onExit().thenRun(() -> Runtime.getRuntime().halt(2)));
        exitWorkerMode = MODE_EXIT_WORKER.equals(mode);
        int port = reservePort();
        for (String prefix : List.of("", "exeris.")) {
            System.setProperty(prefix + "http.mode", "SERVER");
            System.setProperty(prefix + "http.bindHost", "127.0.0.1");
            System.setProperty(prefix + "http.port", Integer.toString(port));
        }

        ClassLoader shim = new SubsystemProviderShim(
                Thread.currentThread().getContextClassLoader(), RecordingSubsystemProvider.class.getName());
        BootstrapSelector selector = exitWorkerMode
                ? BootstrapSelector.forNames("http", EXIT_PROBE)
                : BootstrapSelector.forNames("http");
        // Every mode but hook-off leaves the builder at its default, so the default is what the signal meets.
        KernelBootstrap.Builder builder = KernelBootstrap.builder().classLoader(shim).selector(selector);
        if (MODE_HOOK_OFF.equals(mode)) {
            builder.registerShutdownHook(false);
        }
        KernelBootstrap bootstrap = builder.build();

        CountDownLatch never = new CountDownLatch(1);
        ScopedValue.where(HttpKernelProviders.HTTP_SERVER_HANDLER, handler()).run(() -> {
            try {
                bootstrap.boot(() -> {
                    System.out.println("READY " + port);
                    System.out.flush();
                    if (MODE_EXIT_MAIN.equals(mode)) {
                        awaitStdinLine();
                        System.exit(7);
                    }
                    awaitForever(never);
                });
            } catch (KernelBootstrap.BootstrapException ex) {
                ex.printStackTrace();
                System.exit(1);
            }
        });
    }

    private static void awaitForever(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitStdinLine() {
        try {
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static int reservePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static HttpHandler handler() {
        return exchange -> {
            String path = exchange.request().path();
            if ("/hold".equals(path)) {
                System.out.println("IN-FLIGHT");
                System.out.flush();
                try {
                    Thread.sleep(HOLD_MILLIS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, "done");
            } else {
                respond(exchange, "ok");
            }
        };
    }

    private static void respond(HttpExchange exchange, String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        LoanedBuffer body = KernelProviders.allocator().allocateNetwork(payload.length);
        try {
            MemorySegment.copy(payload, 0, body.segment(), ValueLayout.JAVA_BYTE, 0, payload.length);
            body.setSize(payload.length);
            exchange.respond(new HttpResponse(HttpStatus.OK, exchange.request().version(),
                    List.of(new HttpHeader("Content-Length", Integer.toString(payload.length))), body));
        } catch (RuntimeException ex) {
            body.close();
            throw ex;
        }
    }

    /** Wraps every Community subsystem so each lifecycle edge is written to standard error. */
    public static final class RecordingSubsystemProvider implements SubsystemProvider {

        /** Required public no-arg constructor for {@link java.util.ServiceLoader}. */
        public RecordingSubsystemProvider() {
            // ServiceLoader-mandated no-op constructor.
        }

        @Override
        public List<Subsystem> getSubsystems(ConfigProvider config) {
            List<Subsystem> wrapped = new ArrayList<>();
            for (Subsystem subsystem : new CommunitySubsystemProvider().getSubsystems(config)) {
                wrapped.add(new RecordingSubsystem(subsystem));
            }
            if (exitWorkerMode) {
                wrapped.add(new RecordingSubsystem(new ExitProbeSubsystem()));
            }
            return wrapped;
        }
    }

    /** Delegates every {@link Subsystem} method, recording {@code start()} and {@code stop()}. */
    private static final class RecordingSubsystem implements Subsystem {

        private final Subsystem delegate;

        RecordingSubsystem(Subsystem delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public List<String> dependsOn() {
            return delegate.dependsOn();
        }

        @Override
        public BootstrapPhase phase() {
            return delegate.phase();
        }

        @Override
        public void initialize() {
            delegate.initialize();
        }

        @Override
        public void start() {
            delegate.start();
            System.err.println("START " + delegate.name());
        }

        @Override
        public void stop() {
            System.err.println("STOP-BEGIN " + delegate.name());
            delegate.stop();
            System.err.println("STOP-END " + delegate.name());
        }

        @Override
        public boolean isRunning() {
            return delegate.isRunning();
        }

        @Override
        public boolean isOptional() {
            return delegate.isOptional();
        }

        @Override
        public UnaryOperator<ScopedValue.Carrier> providerBindings() {
            return delegate.providerBindings();
        }
    }

    /** A subsystem whose own thread calls {@code System.exit(9)} on a line of standard input. */
    private static final class ExitProbeSubsystem implements Subsystem {

        private volatile boolean running;

        @Override
        public String name() {
            return EXIT_PROBE;
        }

        @Override
        public List<String> dependsOn() {
            return List.of("memory");
        }

        @Override
        public BootstrapPhase phase() {
            return BootstrapPhase.SERVICES;
        }

        @Override
        public void initialize() {
            // Nothing to allocate.
        }

        @Override
        public void start() {
            Thread.ofPlatform().daemon(true).name("exit-probe-worker").start(() -> {
                awaitStdinLine();
                System.exit(9);
            });
            running = true;
        }

        @Override
        public void stop() {
            // Does not join the worker: it may be the thread whose exit started the JVM's shutdown.
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    /** Serves exactly one {@code SubsystemProvider} registration, hiding the classpath's own. */
    private static final class SubsystemProviderShim extends ClassLoader {

        private static final String SPI_RESOURCE =
                "META-INF/services/eu.exeris.kernel.spi.bootstrap.SubsystemProvider";

        private final Map<String, String> resources;

        SubsystemProviderShim(ClassLoader parent, String providerClassName) {
            super(parent);
            this.resources = Map.of(SPI_RESOURCE, providerClassName);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            String impl = resources.get(name);
            return impl != null
                    ? new ByteArrayInputStream(impl.getBytes(StandardCharsets.UTF_8))
                    : super.getResourceAsStream(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            String impl = resources.get(name);
            if (impl == null) {
                return super.getResources(name);
            }
            byte[] bytes = impl.getBytes(StandardCharsets.UTF_8);
            return Collections.enumeration(List.of(URL.of(URI.create("memory:///" + name),
                    new URLStreamHandler() {
                        @Override
                        protected URLConnection openConnection(URL u) {
                            return new URLConnection(u) {
                                @Override
                                public void connect() {
                                    // In-memory; nothing to connect.
                                }

                                @Override
                                public InputStream getInputStream() {
                                    return new ByteArrayInputStream(bytes);
                                }
                            };
                        }
                    })));
        }
    }
}
