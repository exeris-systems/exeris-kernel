/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standardized check reporter and measurement logger for smoke verification.
 */
@SuppressWarnings({"PMD.SystemPrintln", "PMD.AvoidCatchingGenericException"})
public final class Checks {

    private static final int MIN_STATUS_TOKENS = 2;

    private final String step;
    private final Map<String, Boolean> declaredChecks = new LinkedHashMap<>();

    private Checks(String step, String... checkNames) {
        this.step = step;
        for (String checkName : checkNames) {
            declaredChecks.put(checkName, null);
        }
    }

    /**
     * Declares the full set of checks expected to execute during this step.
     *
     * @param step       step name
     * @param checkNames declared check names
     * @return a new Checks instance
     */
    public static Checks declare(String step, String... checkNames) {
        return new Checks(step, checkNames);
    }

    /**
     * Reports the outcome of an individual check.
     *
     * @param checkName check name matching a declared check
     * @param passed    true if the check passed
     * @param detail    human-readable detail
     */
    public void check(String checkName, boolean passed, String detail) {
        declaredChecks.put(checkName, passed);
        String detailText = detail == null ? "" : detail;
        System.out.printf("CHECK %s/%s %s %s%n", step, checkName, passed ? "PASS" : "FAIL", detailText);
    }

    /**
     * Reports any declared check that never ran, followed by the overall step status.
     *
     * @return true if all declared checks passed and none were missed
     */
    public boolean finish() {
        boolean allPassed = true;
        for (Map.Entry<String, Boolean> entry : declaredChecks.entrySet()) {
            if (entry.getValue() == null) {
                System.out.printf("CHECK %s/%s NOT-RUN missing%n", step, entry.getKey());
                allPassed = false;
            } else if (!entry.getValue()) {
                allPassed = false;
            }
        }
        System.out.printf("STEP %s %s%n", step, allPassed ? "PASS" : "FAIL");
        return allPassed;
    }

    /**
     * Prints the standard MEASURE line and java.vm.version.
     *
     * @param bootMs elapsed milliseconds between KernelBootstrap start and kernelMain callback
     */
    public static void printMeasure(long bootMs) {
        long processMs = ProcessHandle.current().info().startInstant()
                .map(startInstant -> Duration.between(startInstant, Instant.now()).toMillis())
                .orElse(0L);
        long vmHwmKb = readVmHwmKb();
        System.out.printf("MEASURE boot_ms=%d process_ms=%d vmhwm_kb=%d%n", bootMs, processMs, vmHwmKb);
        System.out.println("java.vm.version=" + System.getProperty("java.vm.version"));
        System.out.println("java.vm.gc=" + detectGc());
    }

    private static String detectGc() {
        try {
            java.util.List<java.lang.management.GarbageCollectorMXBean> beans =
                    java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
            if (!beans.isEmpty()) {
                return beans.stream()
                        .map(java.lang.management.GarbageCollectorMXBean::getName)
                        .collect(java.util.stream.Collectors.joining(","));
            }
        } catch (Throwable _) {
            // Best effort detection
        }
        return "unknown";
    }

    private static long readVmHwmKb() {
        Path statusPath = Path.of("/proc/self/status");
        if (!Files.exists(statusPath)) {
            return 0L;
        }
        try (BufferedReader reader = Files.newBufferedReader(statusPath)) {
            String line = reader.readLine();
            while (line != null) {
                if (line.startsWith("VmHWM:")) {
                    String[] tokens = line.split("\\s+");
                    if (tokens.length >= MIN_STATUS_TOKENS) {
                        return Long.parseLong(tokens[1]);
                    }
                }
                line = reader.readLine();
            }
        } catch (IOException | NumberFormatException _) {
            return 0L;
        }
        return 0L;
    }
}
