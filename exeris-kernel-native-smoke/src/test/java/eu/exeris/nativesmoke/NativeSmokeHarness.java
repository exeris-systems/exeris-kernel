/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * JVM test harness executing native smoke images via ProcessBuilder and asserting verification contracts.
 */
public final class NativeSmokeHarness {

    private static final long DEFAULT_TIMEOUT_SECONDS = 120L;
    private static final int RECENT_OUTPUT_LINES = 40;

    private static final Pattern FORBIDDEN_PATTERN = Pattern.compile(
            "ClassNotFoundException|Missing\\w*RegistrationError|UnsupportedFeatureError|"
                    + "NoClassDefFoundError|ExceptionInInitializerError");

    private static final Pattern MEASURE_PATTERN = Pattern.compile(
            "MEASURE boot_ms=(\\d+) process_ms=(\\d+) vmhwm_kb=(\\d+)");

    private static final Map<String, Map<String, Object>> RECORDED_STEPS =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private static volatile String detectedGc = "unknown";

    private NativeSmokeHarness() {
        // Utility harness class
    }

    /**
     * Executes the given native image step with the default 120s timeout and verifies process exit,
     * step pass, check results, forbidden output lines, and logs measurement metrics.
     *
     * @param imageExecutablePath path to the native executable
     * @param stepName            expected step identifier
     * @param expectedCheckNames  names of checks declared and expected to PASS
     * @param extraArguments      additional CLI arguments passed to the process
     */
    public static void executeStep(
            Path imageExecutablePath,
            String stepName,
            List<String> expectedCheckNames,
            String... extraArguments) {
        executeStep(imageExecutablePath, stepName, DEFAULT_TIMEOUT_SECONDS, expectedCheckNames, extraArguments);
    }

    /**
     * Executes the given native image step with an explicit timeout in seconds and verifies process exit,
     * step pass, check results, forbidden output lines, and logs measurement metrics.
     *
     * @param imageExecutablePath path to the native executable
     * @param stepName            expected step identifier
     * @param timeoutSeconds      timeout in seconds
     * @param expectedCheckNames  names of checks declared and expected to PASS
     * @param extraArguments      additional CLI arguments passed to the process
     */
    public static void executeStep(
            Path imageExecutablePath,
            String stepName,
            long timeoutSeconds,
            List<String> expectedCheckNames,
            String... extraArguments) {

        assertThat(Files.isExecutable(imageExecutablePath))
                .as("Native executable must exist and be executable at %s", imageExecutablePath)
                .isTrue();

        List<String> command = new ArrayList<>();
        command.add(imageExecutablePath.toAbsolutePath().toString());
        command.add(stepName);
        Collections.addAll(command, extraArguments);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().keySet().removeIf(key -> key.startsWith("EXERIS_"));

        List<String> outputLines = Collections.synchronizedList(new ArrayList<>());
        int exitCode;
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException executionError) {
            fail("Failed to execute native smoke process for step " + stepName, executionError);
            return;
        }

        Thread readerThread = Thread.ofPlatform().daemon(true).start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputLines.add(line);
                    System.out.println("[" + stepName + "] " + line);
                }
            } catch (IOException _) {
                // Stream closed on process exit
            }
        });

        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                readerThread.join(TimeUnit.SECONDS.toMillis(5));
                fail("Step '%s' timed out after %d seconds; output summary:\n%s",
                        stepName, timeoutSeconds, formatOutputSummary(outputLines));
            }
            readerThread.join(TimeUnit.SECONDS.toMillis(10));
            exitCode = process.exitValue();
        } catch (InterruptedException executionInterrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            fail("Execution interrupted for step " + stepName, executionInterrupted);
            return;
        }

        // Key measurements on backend if step is graph
        String recordKey = ("graph".equals(stepName) && extraArguments.length > 0)
                ? "graph-" + extraArguments[0]
                : stepName;
        parseAndRecordMeasurements(recordKey, outputLines);

        // 1. Assert exit code 0
        assertThat(exitCode)
                .as("Process exit code for step '%s' must be 0; output summary:\n%s",
                        stepName, formatOutputSummary(outputLines))
                .isEqualTo(0);

        // 2. Assert STEP <step> PASS
        String expectedStepPass = "STEP " + stepName + " PASS";
        assertThat(outputLines)
                .as("Output must contain '%s'; output summary:\n%s",
                        expectedStepPass, formatOutputSummary(outputLines))
                .anyMatch(line -> line.contains(expectedStepPass));

        // 3. Assert every declared check is PASS
        for (String checkName : expectedCheckNames) {
            String expectedCheckPass = "CHECK " + stepName + "/" + checkName + " PASS";
            assertThat(outputLines)
                    .as("Check '%s' for step '%s' must pass; output summary:\n%s",
                            checkName, stepName, formatOutputSummary(outputLines))
                    .anyMatch(line -> line.contains(expectedCheckPass));
        }

        // 4. Assert forbidden patterns
        for (String outputLine : outputLines) {
            Matcher matcher = FORBIDDEN_PATTERN.matcher(outputLine);
            if (matcher.find()) {
                fail("Forbidden line found in output for step '%s': %s\nRecent output:\n%s",
                        stepName, outputLine, formatOutputSummary(outputLines));
            }
        }
    }

    private static String formatOutputSummary(List<String> outputLines) {
        if (outputLines.isEmpty()) {
            return "(no output)";
        }
        int start = Math.max(0, outputLines.size() - RECENT_OUTPUT_LINES);
        return String.join("\n", outputLines.subList(start, outputLines.size()));
    }

    private static void parseAndRecordMeasurements(String recordKey, List<String> outputLines) {
        boolean measureFound = false;
        long bootMs = 0L;
        long processMs = 0L;
        long vmHwmKb = 0L;
        String vmVersion = "unknown";

        for (String line : outputLines) {
            Matcher matcher = MEASURE_PATTERN.matcher(line);
            if (matcher.find()) {
                bootMs = Long.parseLong(matcher.group(1));
                processMs = Long.parseLong(matcher.group(2));
                vmHwmKb = Long.parseLong(matcher.group(3));
                measureFound = true;
            } else if (line.startsWith("java.vm.version=")) {
                vmVersion = line.substring("java.vm.version=".length()).trim();
            } else if (line.startsWith("java.vm.gc=")) {
                detectedGc = line.substring("java.vm.gc=".length()).trim();
            }
        }

        if (!measureFound) {
            // Failed steps without MEASURE emission are not recorded with 0ms boot metrics
            return;
        }

        Map<String, Object> stepData = new LinkedHashMap<>();
        stepData.put("boot_ms", bootMs);
        stepData.put("process_ms", processMs);
        stepData.put("vmhwm_kb", vmHwmKb);
        stepData.put("java_vm_version", vmVersion);
        stepData.put("n", 1);
        RECORDED_STEPS.put(recordKey, stepData);

        writeMeasurementsJson();
    }

    private static synchronized void writeMeasurementsJson() {
        String cpuModel = readCpuModel();
        String memTotal = readMemTotal();
        int nproc = Runtime.getRuntime().availableProcessors();

        StringBuilder json = new StringBuilder(512);
        json.append("{\n");
        json.append("  \"system\": {\n");
        json.append("    \"gc\": \"").append(escapeJson(detectedGc)).append("\",\n");
        json.append("    \"nproc\": ").append(nproc).append(",\n");
        json.append("    \"cpu_model\": \"").append(escapeJson(cpuModel)).append("\",\n");
        json.append("    \"mem_total\": \"").append(escapeJson(memTotal)).append("\"\n");
        json.append("  },\n");
        json.append("  \"steps\": {\n");

        int index = 0;
        int total = RECORDED_STEPS.size();
        for (Map.Entry<String, Map<String, Object>> entry : RECORDED_STEPS.entrySet()) {
            json.append("    \"").append(entry.getKey()).append("\": {\n");
            Map<String, Object> data = entry.getValue();
            json.append("      \"boot_ms\": ").append(data.get("boot_ms")).append(",\n");
            json.append("      \"process_ms\": ").append(data.get("process_ms")).append(",\n");
            json.append("      \"vmhwm_kb\": ").append(data.get("vmhwm_kb")).append(",\n");
            json.append("      \"java_vm_version\": \"").append(escapeJson(String.valueOf(data.get("java_vm_version"))))
                    .append("\",\n");
            json.append("      \"n\": ").append(data.get("n")).append("\n");
            json.append("    }").append(index < total - 1 ? ",\n" : "\n");
            index++;
        }
        json.append("  }\n");
        json.append("}\n");

        writeOutputFile(Path.of("target/native-smoke-measurements.json"), json.toString());
    }

    private static void writeOutputFile(Path path, String content) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException _) {
            // Best effort write
        }
    }

    private static String readCpuModel() {
        Path cpuInfo = Path.of("/proc/cpuinfo");
        if (Files.exists(cpuInfo)) {
            try (BufferedReader reader = Files.newBufferedReader(cpuInfo)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("model name")) {
                        int colonIndex = line.indexOf(':');
                        if (colonIndex >= 0) {
                            return line.substring(colonIndex + 1).trim();
                        }
                    }
                }
            } catch (IOException _) {
                return "unknown";
            }
        }
        return "unknown";
    }

    private static String readMemTotal() {
        Path memInfo = Path.of("/proc/meminfo");
        if (Files.exists(memInfo)) {
            try (BufferedReader reader = Files.newBufferedReader(memInfo)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("MemTotal:")) {
                        int colonIndex = line.indexOf(':');
                        if (colonIndex >= 0) {
                            return line.substring(colonIndex + 1).trim();
                        }
                    }
                }
            } catch (IOException _) {
                return "unknown";
            }
        }
        return "unknown";
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
