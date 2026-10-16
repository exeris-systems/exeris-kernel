/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceHealthStatus;
import eu.exeris.kernel.spi.persistence.QueryResult;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Executes the boot-local smoke verification step.
 */
@SuppressWarnings({
        "PMD.SystemPrintln", "PMD.AvoidUsingHardCodedIP", "PMD.CloseResource",
        "PMD.AvoidCatchingGenericException", "PMD.DoNotTerminateVM", "PMD.LawOfDemeter",
        "PMD.ExcessiveImports", "PMD.CouplingBetweenObjects", "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity", "PMD.UseTryWithResources"
})
/* default */ final class BootLocalStep {

    private static final String SUBSYSTEM_STARTED_EVENT = "eu.exeris.kernel.bootstrap.SubsystemStarted";
    private static final String SMOKE_PROBE_EVENT = "eu.exeris.nativesmoke.SmokeProbe";
    private static final String LOOPBACK = "127.0.0.1";

    private static final String CHECK_SUBSYSTEMS = "subsystems";
    private static final String CHECK_HTTP1 = "http1";
    private static final String CHECK_PERSISTENCE = "persistence";
    private static final String CHECK_JFR_CUSTOM = "jfr-custom";

    private static final List<String> EXPECTED_SUBSYSTEMS = List.of(
            "memory", "crypto", "transport", "http", "persistence",
            "security", "events", "flow", "scheduling", "storage", "websocket"
    );

    private BootLocalStep() {
        // Utility step class
    }

    /* default */ static void run() throws IOException {
        Checks checks = Checks.declare("boot-local",
                CHECK_SUBSYSTEMS, CHECK_HTTP1, CHECK_PERSISTENCE, CHECK_JFR_CUSTOM);
        int httpPort = allocatePort();

        Path storageDir = Files.createTempDirectory("native-smoke-storage");

        System.setProperty("exeris.http.mode", "SERVER");
        System.setProperty("exeris.http.bindHost", LOOPBACK);
        System.setProperty("exeris.http.port", Integer.toString(httpPort));
        System.setProperty("exeris.persistence.jdbcUrl", "jdbc:h2:mem:smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        System.setProperty("exeris.persistence.runMigrations", "true");
        System.setProperty("exeris.storage.blob.provider", "blob-fs-community");
        System.setProperty("exeris.storage.blob.location", storageDir.toAbsolutePath().toString());

        // Validate that EXPECTED_SUBSYSTEMS matches ServiceLoader discovery minus graph
        Set<String> discoveredSubsystems = discoverSubsystemsMinusGraph();
        Set<String> expectedSubsystemsSet = Set.copyOf(EXPECTED_SUBSYSTEMS);
        boolean subsystemsDrift = !expectedSubsystemsSet.equals(discoveredSubsystems);

        Path jfrDumpPath = Files.createTempFile("native-smoke-boot-local", ".jfr");
        long startTime = System.nanoTime();
        long[] bootTimeHolder = {0L};

        Recording recording = null;
        try {
            recording = new Recording();
            recording.enable(SUBSYSTEM_STARTED_EVENT);
            recording.enable(SMOKE_PROBE_EVENT);
            recording.start();
        } catch (Throwable t) {
            printException("JFR recording initialization error", t);
            checks.check(CHECK_JFR_CUSTOM, false, "init-failed: " + t.getMessage());
        }

        try {
            HttpHandler handler = exchange -> exchange.respond(HttpResponse.noBody(
                    HttpStatus.OK, exchange.request().version(), List.of(new HttpHeader("X-Smoke", "native"))));

            ScopedValue.where(HttpKernelProviders.HTTP_SERVER_HANDLER, handler).run(() -> {
                try {
                    KernelBootstrap bootstrap = KernelBootstrap.builder()
                            .selector(BootstrapSelector.forNames(EXPECTED_SUBSYSTEMS.toArray(String[]::new)))
                            .build();

                    bootstrap.boot(() -> {
                        bootTimeHolder[0] = (System.nanoTime() - startTime) / 1_000_000L;
                        SmokeProbe probe = new SmokeProbe();
                        probe.record("probe-1", "smoke-local-probe");

                        boolean httpSuccess = checkHttp1(httpPort);
                        checks.check(CHECK_HTTP1, httpSuccess, httpSuccess ? "200 OK received with X-Smoke" : "failed");

                        boolean persistenceSuccess = checkPersistence();
                        checks.check(CHECK_PERSISTENCE, persistenceSuccess,
                                persistenceSuccess ? "SELECT 1 executed" : "failed");
                    });
                } catch (Throwable e) {
                    printException("Boot local error", e);
                }
            });
        } finally {
            if (recording != null) {
                try {
                    recording.stop();
                    recording.dump(jfrDumpPath);
                    verifyJfrEvents(jfrDumpPath, checks, subsystemsDrift, discoveredSubsystems);
                } catch (Throwable t) {
                    printException("Recording dump/verify error", t);
                    checks.check(CHECK_JFR_CUSTOM, false, "dump-failed: " + t.getMessage());
                } finally {
                    recording.close();
                    Files.deleteIfExists(jfrDumpPath);
                }
            } else {
                Files.deleteIfExists(jfrDumpPath);
                if (subsystemsDrift) {
                    checks.check(CHECK_SUBSYSTEMS, false, "drift: expected=" + expectedSubsystemsSet
                            + " discovered=" + discoveredSubsystems);
                }
            }
            deleteRecursively(storageDir);
        }

        boolean passed = checks.finish();
        Checks.printMeasure(bootTimeHolder[0]);
        if (!passed) {
            System.exit(1);
        }
    }

    private static Set<String> discoverSubsystemsMinusGraph() {
        Set<String> set = new HashSet<>();
        for (SubsystemProvider provider : ServiceLoader.load(SubsystemProvider.class)) {
            for (Subsystem subsystem : provider.getSubsystems(null)) {
                if (!"graph".equals(subsystem.name())) {
                    set.add(subsystem.name());
                }
            }
        }
        return set;
    }

    private static int allocatePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(LOOPBACK, 0));
            return socket.getLocalPort();
        }
    }

    private static boolean checkHttp1(int port) {
        try (Socket socket = new Socket(LOOPBACK, port);
             PrintWriter writer = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {

            writer.print("GET /smoke HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            writer.flush();

            String statusLine = reader.readLine();
            boolean gotOk = statusLine != null && statusLine.contains("200");
            boolean gotHeader = false;
            String headerLine = reader.readLine();
            while (headerLine != null && !headerLine.isEmpty()) {
                if (headerLine.toLowerCase(Locale.ROOT).startsWith("x-smoke:")) {
                    gotHeader = true;
                }
                headerLine = reader.readLine();
            }
            return gotOk && gotHeader;
        } catch (IOException e) {
            printException("HTTP/1 check error", e);
            return false;
        }
    }

    private static boolean checkPersistence() {
        try {
            PersistenceEngine engine = KernelProviders.persistenceEngine();
            PersistenceHealthStatus health = engine.healthCheckDetailed();
            if (!health.healthy()) {
                return false;
            }
            try (PersistenceConnection connection = engine.openConnection();
                 QueryResult result = connection.executeQuery("SELECT 1")) {
                return result.next();
            }
        } catch (Throwable e) {
            printException("Persistence check error", e);
            return false;
        }
    }

    private static void verifyJfrEvents(
            Path dumpFile,
            Checks checks,
            boolean subsystemsDrift,
            Set<String> discoveredSubsystems) throws IOException {

        Set<String> startedSubsystems = new HashSet<>();
        boolean foundCustomProbe = false;

        try (RecordingFile recordingFile = new RecordingFile(dumpFile)) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent recordedEvent = recordingFile.readEvent();
                String eventName = recordedEvent.getEventType().getName();
                if (SUBSYSTEM_STARTED_EVENT.equals(eventName)) {
                    startedSubsystems.add(recordedEvent.getString("subsystemName"));
                } else if (SMOKE_PROBE_EVENT.equals(eventName)) {
                    foundCustomProbe = true;
                }
            }
        }

        Set<String> expectedSet = Set.copyOf(EXPECTED_SUBSYSTEMS);
        boolean subsystemsMatch = !subsystemsDrift && startedSubsystems.equals(expectedSet);
        String detail;
        if (subsystemsDrift) {
            detail = "drift detected: expected=" + expectedSet + " discovered=" + discoveredSubsystems;
        } else if (subsystemsMatch) {
            detail = "all " + expectedSet.size() + " started";
        } else {
            detail = "started: " + startedSubsystems;
        }
        checks.check(CHECK_SUBSYSTEMS, subsystemsMatch, detail);
        checks.check(CHECK_JFR_CUSTOM, foundCustomProbe, foundCustomProbe ? "SmokeProbe recorded" : "not found");
    }

    private static void printException(String prefix, Throwable throwable) {
        System.out.println(prefix + ": " + throwable);
        for (Throwable cause = throwable.getCause(); cause != null; cause = cause.getCause()) {
            System.out.println("  caused by: " + cause);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException _) {
                            // Best effort cleanup
                        }
                    });
        } catch (IOException _) {
            // Best effort cleanup
        }
    }
}
