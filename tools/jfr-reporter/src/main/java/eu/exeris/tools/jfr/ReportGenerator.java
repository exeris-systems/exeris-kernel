/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

final class ReportGenerator {

    private static final Logger LOGGER = Logger.getLogger(ReportGenerator.class.getName());

    private static final String FIELD_CLASS      = "class";
    private static final String LOG_PREFIX       = "[jfr-reporter] ";
    private static final String LOG_PREFIX_WROTE = LOG_PREFIX + "Wrote ";
    private static final String FIELD_COUNT      = "count";

    private final Map<String, Path> moduleDirs;
    private final String commit;
    private final String branch;
    private final Path outDir;
    private final ObjectMapper mapper = new ObjectMapper();

    ReportGenerator(Map<String, Path> moduleDirs, String commit, String branch, Path outDir) {
        this.moduleDirs = moduleDirs;
        this.commit = commit;
        this.branch = branch;
        this.outDir = outDir;
    }

    void generate() throws IOException {
        LOGGER.info(() -> LOG_PREFIX + "Generating reports → " + outDir);

        Map<String, Map<String, List<AllocEvent>>> allModuleEvents = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : moduleDirs.entrySet()) {
            LOGGER.info(() -> LOG_PREFIX + "Reading module '" + entry.getKey() + "' from " + entry.getValue());
            allModuleEvents.put(entry.getKey(), JfrDirectoryReader.readDirectory(entry.getValue()));
        }

        writeEvidence(allModuleEvents);

        for (Map.Entry<String, Map<String, List<AllocEvent>>> moduleEntry : allModuleEvents.entrySet()) {
            String module = moduleEntry.getKey();
            Map<String, List<AllocEvent>> subsystemEvents = moduleEntry.getValue();
            List<AllocEvent> allEvents = subsystemEvents.values().stream().flatMap(List::stream).toList();

            Path moduleOutDir = outDir.resolve(module);
            Files.createDirectories(moduleOutDir);

            // Aggregated module-level reports
            Map<String, List<String>> stacksMap = new LinkedHashMap<>();
            AtomicInteger stackCounter = new AtomicInteger(0);
            Map<String, String> stackIdCache = new HashMap<>();

            writeTimeline(module, moduleOutDir, allEvents, stacksMap, stackIdCache, stackCounter);
            writeStacks(module, moduleOutDir, stacksMap);
            writeAllocTopClasses(module, moduleOutDir, allEvents);

            // Per-subsystem reports
            for (Map.Entry<String, List<AllocEvent>> subsysEntry : subsystemEvents.entrySet()) {
                String subsystem = subsysEntry.getKey();
                List<AllocEvent> subsysEvents = subsysEntry.getValue();
                if (subsysEvents.isEmpty()) continue;

                String safeSubsystem = subsystem.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                if (safeSubsystem.isEmpty() || safeSubsystem.equals(".") || safeSubsystem.equals("..")) continue;

                Path subsysOutDir = moduleOutDir.resolve(safeSubsystem);
                Files.createDirectories(subsysOutDir);

                Map<String, List<String>> subsysStacksMap = new LinkedHashMap<>();
                AtomicInteger subsysStackCounter = new AtomicInteger(0);
                Map<String, String> subsysStackIdCache = new HashMap<>();

                writeTimeline(module + "/" + safeSubsystem, subsysOutDir, subsysEvents, subsysStacksMap, subsysStackIdCache, subsysStackCounter);
                writeStacks(module + "/" + safeSubsystem, subsysOutDir, subsysStacksMap);
                writeAllocTopClasses(module + "/" + safeSubsystem, subsysOutDir, subsysEvents);
            }
        }

        writeJfrSummary(allModuleEvents);
        LOGGER.info(() -> LOG_PREFIX + "Done.");
    }

    private void writeEvidence(Map<String, Map<String, List<AllocEvent>>> allModuleEvents) throws IOException {
        ObjectNode root = mapper.createObjectNode();

        ObjectNode meta = root.putObject("meta");
        meta.put("generated", Instant.now().atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        meta.put("jdk", System.getProperty("java.version", "26"));
        meta.put("commit", commit);
        meta.put("branch", branch);

        for (Map.Entry<String, Map<String, List<AllocEvent>>> moduleEntry : allModuleEvents.entrySet()) {
            ObjectNode moduleNode = root.putObject(moduleEntry.getKey());
            for (Map.Entry<String, List<AllocEvent>> subsysEntry : moduleEntry.getValue().entrySet()) {
                List<AllocEvent> events = subsysEntry.getValue();
                long totalEvents = events.size();
                long exerisCount = events.stream()
                        .filter(e -> e.className().startsWith("eu.exeris.")).count();
                long productionCount = events.stream()
                        .filter(e -> e.category() == Category.PRODUCTION).count();
                long harnessCount = events.stream()
                        .filter(e -> e.category() == Category.TEST_HARNESS).count();

                ObjectNode subsysNode = moduleNode.putObject(subsysEntry.getKey());
                subsysNode.put("verdict", verdict(moduleEntry.getKey(), productionCount));
                subsysNode.put("total_events", totalEvents);
                subsysNode.put("exeris_alloc_count", exerisCount);
                subsysNode.put("exeris_production_alloc_count", productionCount);
                subsysNode.put("exeris_test_harness_count", harnessCount);
            }
        }

        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(outDir.resolve("evidence.json").toFile(), root);
        LOGGER.info(() -> LOG_PREFIX_WROTE + "evidence.json");
    }

    private String verdict(String module, long productionCount) {
        if ("core".equals(module)) {
            return productionCount == 0 ? "VERIFIED" : "WARNING";
        }
        return productionCount < 1000 ? "VERIFIED" : "WARNING";
    }

    private void writeTimeline(String module, Path moduleOutDir, List<AllocEvent> events,
                               Map<String, List<String>> stacksMap,
                               Map<String, String> stackIdCache,
                               AtomicInteger stackCounter) throws IOException {
        List<AllocEvent> sorted = events.stream()
                .sorted(Comparator.comparingLong(AllocEvent::tEpochMillis))
                .toList();
        java.io.File outFile = moduleOutDir.resolve("timeline.json").toFile();
        try (JsonGenerator gen = mapper.getFactory().createGenerator(outFile, JsonEncoding.UTF8)) {
            gen.setPrettyPrinter(new DefaultPrettyPrinter());
            gen.writeStartArray();
            for (AllocEvent e : sorted) {
                String stackId = resolveStackId(e.stackFrames(), stacksMap, stackIdCache, stackCounter);
                gen.writeStartObject();
                gen.writeNumberField("t", e.tEpochMillis());
                gen.writeStringField("type", e.eventType());
                gen.writeStringField(FIELD_CLASS, e.className());
                gen.writeStringField("thread", e.threadName());
                gen.writeNumberField("size", e.sizeBytes());
                gen.writeStringField("stackId", stackId);
                gen.writeStringField("category", e.category().name());
                gen.writeEndObject();
            }
            gen.writeEndArray();
        }
        final int count = sorted.size();
        LOGGER.info(() -> LOG_PREFIX_WROTE + module + "/timeline.json (" + count + " events)");
    }

    private void writeStacks(String module, Path moduleOutDir, Map<String, List<String>> stacksMap) throws IOException {
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(moduleOutDir.resolve("stacks.json").toFile(), stacksMap);
        final int count = stacksMap.size();
        LOGGER.info(() -> LOG_PREFIX_WROTE + module + "/stacks.json (" + count + " stacks)");
    }

    private void writeAllocTopClasses(String module, Path moduleOutDir, List<AllocEvent> events) throws IOException {
        Map<String, long[]> countMap = new LinkedHashMap<>();
        for (AllocEvent e : events) {
            String key = e.className() + "|" + e.category().name();
            countMap.computeIfAbsent(key, k -> new long[2]);
            countMap.get(key)[0]++;
            countMap.get(key)[1] += e.sizeBytes();
        }

        List<Map<String, Object>> topClasses = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : countMap.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(FIELD_CLASS, parts[0]);
            item.put(FIELD_COUNT, entry.getValue()[0]);
            item.put("bytes", entry.getValue()[1]);
            item.put("category", parts[1]);
            topClasses.add(item);
        }
        topClasses.sort(Comparator.comparingLong((Map<String, Object> m) -> ((Number) m.get("count")).longValue()).reversed());

        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(moduleOutDir.resolve("alloc-top-classes.json").toFile(), topClasses);
        final int count = topClasses.size();
        LOGGER.info(() -> LOG_PREFIX_WROTE + module + "/alloc-top-classes.json (" + count + " classes)");
    }

    private void writeJfrSummary(Map<String, Map<String, List<AllocEvent>>> allModuleEvents) throws IOException {
        ObjectNode root = mapper.createObjectNode();

        for (Map.Entry<String, Map<String, List<AllocEvent>>> moduleEntry : allModuleEvents.entrySet()) {
            List<AllocEvent> allEvents = moduleEntry.getValue().values().stream()
                    .flatMap(List::stream).toList();
            ObjectNode moduleNode = root.putObject(moduleEntry.getKey());

            Map<String, Long> threadCounts = new LinkedHashMap<>();
            for (AllocEvent e : allEvents) {
                threadCounts.merge(e.threadName(), 1L, Long::sum);
            }
            ArrayNode topThreads = moduleNode.putArray("topThreads");
            threadCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(10)
                    .forEach(entry -> {
                        ObjectNode t = topThreads.addObject();
                        t.put("thread", entry.getKey());
                        t.put(FIELD_COUNT, entry.getValue());
                    });

            Map<String, Long> classCounts = new LinkedHashMap<>();
            for (AllocEvent e : allEvents) {
                classCounts.merge(e.className(), 1L, Long::sum);
            }
            ArrayNode topClasses = moduleNode.putArray("topClasses");
            classCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(20)
                    .forEach(entry -> {
                        ObjectNode c = topClasses.addObject();
                        c.put(FIELD_CLASS, entry.getKey());
                        c.put(FIELD_COUNT, entry.getValue());
                    });

            moduleNode.putArray("phaseBoundaries");
        }

        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(outDir.resolve("jfr-summary.json").toFile(), root);
        LOGGER.info(() -> LOG_PREFIX_WROTE + "jfr-summary.json");
    }

    private String resolveStackId(List<String> frames,
                                  Map<String, List<String>> stacksMap,
                                  Map<String, String> stackIdCache,
                                  AtomicInteger counter) {
        String fingerprint = String.join("|", frames);
        return stackIdCache.computeIfAbsent(fingerprint, k -> {
            String id = "stk_" + String.format("%04d", counter.incrementAndGet());
            stacksMap.put(id, frames);
            return id;
        });
    }
}
