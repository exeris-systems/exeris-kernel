package eu.exeris.tools.jfr;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
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

final class ReportGenerator {

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
        System.out.println("[jfr-reporter] Generating reports → " + outDir);

        Map<String, Map<String, List<AllocEvent>>> allModuleEvents = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : moduleDirs.entrySet()) {
            System.out.println("[jfr-reporter] Reading module '" + entry.getKey() + "' from " + entry.getValue());
            allModuleEvents.put(entry.getKey(), JfrDirectoryReader.readDirectory(entry.getValue()));
        }

        writeEvidence(allModuleEvents);

        for (Map.Entry<String, Map<String, List<AllocEvent>>> moduleEntry : allModuleEvents.entrySet()) {
            String module = moduleEntry.getKey();
            Map<String, List<AllocEvent>> subsystemEvents = moduleEntry.getValue();
            List<AllocEvent> allEvents = subsystemEvents.values().stream().flatMap(List::stream).toList();

            Map<String, List<String>> stacksMap = new LinkedHashMap<>();
            AtomicInteger stackCounter = new AtomicInteger(0);
            Map<String, String> stackIdCache = new HashMap<>();

            writeTimeline(module, allEvents, stacksMap, stackIdCache, stackCounter);
            writeStacks(module, stacksMap);
            writeAllocTopClasses(module, allEvents);
        }

        writeJfrSummary(allModuleEvents);
        System.out.println("[jfr-reporter] Done.");
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
                        .filter(e -> e.category() == Category.production).count();
                long harnessCount = events.stream()
                        .filter(e -> e.category() == Category.test_harness).count();

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
        System.out.println("[jfr-reporter] Wrote evidence.json");
    }

    private String verdict(String module, long productionCount) {
        if ("core".equals(module)) {
            return productionCount == 0 ? "VERIFIED" : "WARNING";
        }
        return productionCount < 1000 ? "VERIFIED" : "WARNING";
    }

    private void writeTimeline(String module, List<AllocEvent> events,
                               Map<String, List<String>> stacksMap,
                               Map<String, String> stackIdCache,
                               AtomicInteger stackCounter) throws IOException {
        List<AllocEvent> sorted = events.stream()
                .sorted(Comparator.comparingLong(AllocEvent::tEpochMillis))
                .toList();
        java.io.File outFile = outDir.resolve("timeline-" + module + ".json").toFile();
        try (JsonGenerator gen = mapper.getFactory().createGenerator(outFile, JsonEncoding.UTF8)) {
            gen.setPrettyPrinter(new DefaultPrettyPrinter());
            gen.writeStartArray();
            for (AllocEvent e : sorted) {
                String stackId = resolveStackId(e.stackFrames(), stacksMap, stackIdCache, stackCounter);
                gen.writeStartObject();
                gen.writeNumberField("t", e.tEpochMillis());
                gen.writeStringField("type", e.eventType());
                gen.writeStringField("class", e.className());
                gen.writeStringField("thread", e.threadName());
                gen.writeNumberField("size", e.sizeBytes());
                gen.writeStringField("stackId", stackId);
                gen.writeStringField("category", e.category().name());
                gen.writeEndObject();
            }
            gen.writeEndArray();
        }
        System.out.println("[jfr-reporter] Wrote timeline-" + module + ".json (" + sorted.size() + " events)");
    }

    private void writeStacks(String module, Map<String, List<String>> stacksMap) throws IOException {
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(outDir.resolve("stacks-" + module + ".json").toFile(), stacksMap);
        System.out.println("[jfr-reporter] Wrote stacks-" + module + ".json (" + stacksMap.size() + " stacks)");
    }

    private void writeAllocTopClasses(String module, List<AllocEvent> events) throws IOException {
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
            item.put("class", parts[0]);
            item.put("count", entry.getValue()[0]);
            item.put("bytes", entry.getValue()[1]);
            item.put("category", parts[1]);
            topClasses.add(item);
        }
        topClasses.sort(Comparator.comparingLong((Map<String, Object> m) -> ((Number) m.get("count")).longValue()).reversed());

        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(outDir.resolve("alloc-top-classes-" + module + ".json").toFile(), topClasses);
        System.out.println("[jfr-reporter] Wrote alloc-top-classes-" + module + ".json (" + topClasses.size() + " classes)");
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
                        t.put("count", entry.getValue());
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
                        c.put("class", entry.getKey());
                        c.put("count", entry.getValue());
                    });

            moduleNode.putArray("phaseBoundaries");
        }

        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(outDir.resolve("jfr-summary.json").toFile(), root);
        System.out.println("[jfr-reporter] Wrote jfr-summary.json");
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
