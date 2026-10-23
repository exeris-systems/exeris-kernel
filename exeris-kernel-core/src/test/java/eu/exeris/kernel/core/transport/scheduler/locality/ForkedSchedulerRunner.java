/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import org.junit.jupiter.api.Assumptions;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs {@link SchedulerScenarioMain} in a JVM with the locality scheduler installed.
 *
 * <p>The surefire JVM patches stub scheduler interfaces into {@code java.base}, so the real
 * scheduler can only be exercised in a separate JVM that is started without the patch. That JVM
 * must be the Loom early-access build; on any other JDK the scenarios are skipped.
 */
final class ForkedSchedulerRunner {

    private static final long TIMEOUT_SECONDS = 150;

    private ForkedSchedulerRunner() {
    }

    static void assumeLoomJdk() {
        String runtime = System.getProperty("java.runtime.version", "") + System.getProperty("java.vm.version", "");
        Assumptions.assumeTrue(runtime.toLowerCase(java.util.Locale.ROOT).contains("loom"),
                "the installed-scheduler scenarios need the Loom early-access JDK, running " + runtime);
    }

    /** Runs a scenario with the given system properties; returns the keys of its RESULT line. */
    static Map<String, String> run(String scenario, Map<String, String> properties) throws Exception {
        String output = runForOutput(scenario, properties, List.of());
        for (String line : output.split("\n")) {
            if (line.startsWith("RESULT ")) {
                Map<String, String> result = new HashMap<>();
                for (String pair : line.substring("RESULT ".length()).trim().split(" ")) {
                    int eq = pair.indexOf('=');
                    result.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
                return result;
            }
        }
        return fail("scenario " + scenario + " printed no RESULT line:\n" + output);
    }

    /** Runs a scenario with extra JVM arguments and returns everything the JVM printed. */
    static String runForOutput(String scenario, Map<String, String> properties, List<String> jvmArgs)
            throws Exception {
        assumeLoomJdk();
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Djdk.virtualThreadScheduler.implClass=" + ExerisCarrierScheduler.class.getName());
        command.add("--add-exports");
        command.add("java.base/jdk.internal.access=ALL-UNNAMED");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Dexeris.carrier.count=4");
        command.addAll(jvmArgs);
        properties.forEach((k, v) -> command.add("-D" + k + "=" + v));
        command.add("-cp");
        command.add(location(ExerisCarrierScheduler.class) + java.io.File.pathSeparator + location(SchedulerScenarioMain.class));
        command.add(SchedulerScenarioMain.class.getName());
        command.add(scenario);
        Path log = Files.createTempFile("locality-scenario-", ".log");
        String output;
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            output = Files.readString(log, StandardCharsets.UTF_8);
            if (!finished) {
                fail("scenario " + scenario + " timed out\n" + output);
            }
        } finally {
            process.destroyForcibly();
            Files.deleteIfExists(log);
        }
        assertEquals(0, process.exitValue(), "scenario " + scenario + " " + properties + " failed:\n" + output);
        return output;
    }

    static int intOf(Map<String, String> result, String key) {
        return Integer.parseInt(result.get(key));
    }

    private static String location(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
