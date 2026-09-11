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

import eu.exeris.tools.jfr.JfrDirectoryReader.RecordingData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Writes the report set for one or more module {@code target/} directories.
 *
 * <p>Only recordings with an identity (a marker pair, or the TCK file-name shape) enter
 * {@code evidence.json} and the module-level aggregates. Everything else — {@code surefire.jfr},
 * {@code jmh-benchmarks.jfr}, the pinning monitor's files — is listed under {@code unattributed} in
 * {@code jfr-summary.json} and counted nowhere else, so a TCK window is never counted twice through
 * the JVM-wide recording that also captured it.
 */
final class ReportGenerator {

    private static final Logger LOGGER = Logger.getLogger(ReportGenerator.class.getName());

    static final int SCHEMA_VERSION = 2;

    static final String VERDICT_PASS = "PASS";
    static final String VERDICT_FAIL = "FAIL";
    static final String VERDICT_NOT_MEASURED = "NOT_MEASURED";

    private static final String FIELD_CLASS      = "class";
    private static final String FIELD_COUNT      = "count";
    private static final String FIELD_FILE       = "file";
    private static final String LOG_PREFIX       = "[jfr-reporter] ";
    private static final String LOG_PREFIX_WROTE = LOG_PREFIX + "Wrote ";
    private static final String UNATTRIBUTED     = "unattributed";

    static final String REASON_NOT_A_MEASUREMENT = "no_marker_and_no_tck_filename";
    static final String REASON_MULTIPLE_WINDOWS  = "multiple_windows";
    static final String REASON_DUPLICATE_WINDOW  = "duplicate_window";
    private static final int TOP_FRAMES          = 20;

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

    /**
     * The contract block of one windowed recording, computed the way the TCK computes it:
     * {@code eu.exeris.*}-typed events on the workload thread inside the window, against the mode and
     * budget the marker carries.
     *
     * @param mode              {@code zero}, {@code bounded} or {@code unspecified}
     * @param budget            budget per iteration, or -1
     * @param iterations        steady-state iterations
     * @param exerisEvents      the TCK's own count, recomputed
     * @param bytesDelta        the {@code ThreadMXBean} delta from the end marker, or -1
     * @param bytesPerIteration {@code bytesDelta / iterations}, or {@code null}
     * @param verdict           {@code PASS}, {@code FAIL} or {@code NOT_MEASURED}
     */
    record ContractResult(String mode, int budget, int iterations, long exerisEvents, long bytesDelta,
                          Double bytesPerIteration, String verdict) {

        static ContractResult notMeasured() {
            return new ContractResult(TckMarker.MODE_UNSPECIFIED, -1, 0, 0L,
                    TckMarker.BYTES_UNAVAILABLE, null, VERDICT_NOT_MEASURED);
        }
    }

    static ContractResult evaluate(RecordingData recording) {
        TckMarker.Window w = recording.window();
        if (w == null) {
            return ContractResult.notMeasured();
        }
        long count = recording.events().stream()
                .filter(e -> w.contains(e.tEpochMillis()))
                .filter(e -> e.objectKind() == ObjectKind.EXERIS)
                .filter(e -> e.threadId() == w.workloadThreadId())
                .filter(e -> !TckMarker.EVENT_CLASS.equals(e.className()))
                .count();
        long delta = w.allocatedBytesDelta();
        Double perIteration = delta >= 0 && w.iterations() > 0 ? (double) delta / w.iterations() : null;
        String verdict = switch (w.contractMode()) {
            case TckMarker.MODE_ZERO -> {
                boolean bytesOk = delta == TckMarker.BYTES_UNAVAILABLE || delta < w.iterations();
                yield count == 0 && bytesOk ? VERDICT_PASS : VERDICT_FAIL;
            }
            case TckMarker.MODE_BOUNDED ->
                    count <= (long) w.iterations() * w.budgetPerIteration() ? VERDICT_PASS : VERDICT_FAIL;
            default -> VERDICT_NOT_MEASURED;
        };
        return new ContractResult(w.contractMode(), w.budgetPerIteration(), w.iterations(), count,
                delta, perIteration, verdict);
    }

    /** Worst-of over recordings: any FAIL fails; else any PASS passes; else nothing was measured. */
    static String subsystemVerdict(List<ContractResult> results) {
        boolean anyPass = false;
        for (ContractResult r : results) {
            if (VERDICT_FAIL.equals(r.verdict())) {
                return VERDICT_FAIL;
            }
            anyPass |= VERDICT_PASS.equals(r.verdict());
        }
        return anyPass ? VERDICT_PASS : VERDICT_NOT_MEASURED;
    }

    void generate() throws IOException {
        LOGGER.info(() -> LOG_PREFIX + "Generating reports → " + outDir);
        Files.createDirectories(outDir);

        Map<String, Partition> perModule = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : moduleDirs.entrySet()) {
            LOGGER.info(() -> LOG_PREFIX + "Reading module '" + entry.getKey() + "' from " + entry.getValue());
            perModule.put(entry.getKey(), partition(JfrDirectoryReader.readDirectory(entry.getValue())));
        }

        writeEvidence(perModule);

        for (Map.Entry<String, Partition> moduleEntry : perModule.entrySet()) {
            String module = moduleEntry.getKey();
            Map<String, List<RecordingData>> bySubsystem = moduleEntry.getValue().bySubsystem();
            List<AllocEvent> allEvents = flatten(bySubsystem.values().stream().flatMap(List::stream).toList());

            Path moduleOutDir = outDir.resolve(module);
            Files.createDirectories(moduleOutDir);
            writeEventReports(module, moduleOutDir, allEvents);

            for (Map.Entry<String, List<RecordingData>> subsysEntry : bySubsystem.entrySet()) {
                List<AllocEvent> subsysEvents = flatten(subsysEntry.getValue());
                if (subsysEvents.isEmpty()) {
                    continue;
                }
                String safeSubsystem = subsysEntry.getKey().replaceAll("[^a-zA-Z0-9_\\-]", "_");
                if (safeSubsystem.isEmpty() || safeSubsystem.equals(".") || safeSubsystem.equals("..")) {
                    continue;
                }
                Path subsysOutDir = moduleOutDir.resolve(safeSubsystem);
                Files.createDirectories(subsysOutDir);
                writeEventReports(module + "/" + safeSubsystem, subsysOutDir, subsysEvents);
            }
        }

        writeJfrSummary(perModule);
        LOGGER.info(() -> LOG_PREFIX + "Done.");
    }

    private void writeEventReports(String label, Path dir, List<AllocEvent> events) throws IOException {
        Map<String, List<String>> stacksMap = new LinkedHashMap<>();
        AtomicInteger stackCounter = new AtomicInteger(0);
        Map<String, String> stackIdCache = new HashMap<>();
        writeTimeline(label, dir, events, stacksMap, stackIdCache, stackCounter);
        writeStacks(label, dir, stacksMap);
        writeAllocTopClasses(label, dir, events);
    }

    /**
     * A module's recordings split into subsystem measurements and everything else.
     *
     * @param bySubsystem  the measurements, grouped by lower-cased subsystem, file order kept
     * @param unattributed the rest, each with the reason it is not a measurement
     */
    record Partition(Map<String, List<RecordingData>> bySubsystem, List<Unattributed> unattributed) {}

    /**
     * A recording that is not a subsystem measurement.
     *
     * @param recording the recording
     * @param reason    one of the {@code REASON_*} constants
     */
    record Unattributed(RecordingData recording, String reason) {}

    /**
     * Splits recordings. Two measurements of the same window — the TCK's own file and the JVM-wide
     * {@code surefire.jfr} in a JVM that ran exactly one measurement — keep the one whose name is the
     * TCK's, else the smaller one; the other is a duplicate.
     *
     * @param recordings a module's recordings in file order
     * @return the partition
     */
    static Partition partition(List<RecordingData> recordings) {
        List<Unattributed> unattributed = new ArrayList<>();
        Map<String, RecordingData> winners = new LinkedHashMap<>();
        List<RecordingData> identified = new ArrayList<>();
        for (RecordingData r : recordings) {
            if (!r.identity().identified()) {
                unattributed.add(new Unattributed(r,
                        r.windows().size() > 1 ? REASON_MULTIPLE_WINDOWS : REASON_NOT_A_MEASUREMENT));
                continue;
            }
            TckMarker.Window w = r.window();
            if (w == null) {
                identified.add(r);
                continue;
            }
            String key = r.identity().subsystem() + "|" + w.testClass() + "|" + w.start().toEpochMilli();
            RecordingData incumbent = winners.get(key);
            if (incumbent == null) {
                winners.put(key, r);
            } else if (prefer(r, incumbent)) {
                winners.put(key, r);
                unattributed.add(new Unattributed(incumbent, REASON_DUPLICATE_WINDOW));
            } else {
                unattributed.add(new Unattributed(r, REASON_DUPLICATE_WINDOW));
            }
        }
        identified.addAll(winners.values());
        Map<String, List<RecordingData>> bySubsystem = new LinkedHashMap<>();
        for (RecordingData r : identified) {
            bySubsystem.computeIfAbsent(r.identity().subsystem(), k -> new ArrayList<>()).add(r);
        }
        return new Partition(bySubsystem, unattributed);
    }

    private static boolean prefer(RecordingData candidate, RecordingData incumbent) {
        if (candidate.tckShapedName() != incumbent.tckShapedName()) {
            return candidate.tckShapedName();
        }
        return candidate.events().size() < incumbent.events().size();
    }

    private static List<AllocEvent> flatten(List<RecordingData> recordings) {
        return recordings.stream().flatMap(r -> r.attributedEvents().stream()).toList();
    }

    // ── evidence.json ────────────────────────────────────────────────────────

    private void writeEvidence(Map<String, Partition> perModule) throws IOException {
        ObjectNode root = mapper.createObjectNode();

        ObjectNode meta = root.putObject("meta");
        meta.put("schema", SCHEMA_VERSION);
        meta.put("generated", Instant.now().atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        meta.put("jdk", System.getProperty("java.version", "unknown"));
        meta.put("commit", commit);
        meta.put("branch", branch);

        for (Map.Entry<String, Partition> moduleEntry : perModule.entrySet()) {
            ObjectNode moduleNode = root.putObject(moduleEntry.getKey());
            for (Map.Entry<String, List<RecordingData>> subsysEntry
                    : moduleEntry.getValue().bySubsystem().entrySet()) {
                writeSubsystemEvidence(moduleNode.putObject(subsysEntry.getKey()), subsysEntry.getValue());
            }
        }

        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(outDir.resolve("evidence.json").toFile(), root);
        LOGGER.info(() -> LOG_PREFIX_WROTE + "evidence.json");
    }

    private static void writeSubsystemEvidence(ObjectNode node, List<RecordingData> recordings) {
        List<ContractResult> results = new ArrayList<>();
        ArrayNode recordingsNode = JsonNodeFactoryHolder.array();
        for (RecordingData r : recordings) {
            ContractResult c = evaluate(r);
            results.add(c);
            ObjectNode rn = recordingsNode.addObject();
            rn.put(FIELD_FILE, r.file().getFileName().toString());
            rn.put("test_class", r.identity().testClass());
            rn.put("window_source", r.identity().source().name().toLowerCase(java.util.Locale.ROOT));
            rn.put("verdict", c.verdict());
            ObjectNode cn = rn.putObject("contract");
            cn.put("mode", c.mode());
            cn.put("budget_per_iteration", c.budget());
            cn.put("iterations", c.iterations());
            cn.put("exeris_events_on_workload_thread", c.exerisEvents());
            cn.put("allocated_bytes_delta", c.bytesDelta());
            if (c.bytesPerIteration() == null) {
                cn.putNull("bytes_per_iteration");
            } else {
                cn.put("bytes_per_iteration", c.bytesPerIteration());
            }
            cn.put("satisfied", VERDICT_PASS.equals(c.verdict()));
        }

        List<AllocEvent> events = flatten(recordings);
        node.put("verdict", subsystemVerdict(results));
        node.put("measured_recordings", results.stream().filter(r -> !VERDICT_NOT_MEASURED.equals(r.verdict())).count());
        node.set("recordings", recordingsNode);
        node.put("total_events", events.size());
        node.put("exeris_alloc_count", events.stream().filter(e -> e.objectKind() == ObjectKind.EXERIS).count());
        node.put("exeris_production_alloc_count", events.stream().filter(e -> e.ownerCategory() == Owner.PRODUCTION).count());
        node.put("exeris_test_harness_count", events.stream().filter(e -> e.ownerCategory() == Owner.TEST_HARNESS).count());

        ObjectNode owned = node.putObject("owned");
        EnumMap<Owner, Stats> byOwner = new EnumMap<>(Owner.class);
        for (Owner o : Owner.values()) {
            byOwner.put(o, new Stats());
        }
        for (AllocEvent e : events) {
            byOwner.get(e.ownerCategory()).add(e);
        }
        for (Owner o : Owner.values()) {
            byOwner.get(o).write(owned.putObject(o.json()));
        }

        Map<String, Stats> byFrame = new LinkedHashMap<>();
        for (AllocEvent e : events) {
            if (e.ownerCategory() == Owner.PRODUCTION) {
                byFrame.computeIfAbsent(e.owner().describe(), k -> new Stats()).add(e);
            }
        }
        ArrayNode topFrames = node.putArray("top_production_frames");
        byFrame.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Stats> en) -> en.getValue().events).reversed())
                .limit(TOP_FRAMES)
                .forEach(en -> {
                    ObjectNode fn = topFrames.addObject();
                    fn.put("frame", en.getKey());
                    en.getValue().write(fn);
                });
    }

    /** Per-type counters for one bucket. Bytes of different event types are never added together. */
    static final class Stats {
        long events;
        long samples;
        long sampleWeightBytes;
        long tlabRefills;
        long tlabBytes;
        long outsideTlab;
        long outsideTlabBytes;
        final EnumMap<ObjectKind, Long> byKind = new EnumMap<>(ObjectKind.class);

        void add(AllocEvent e) {
            events++;
            byKind.merge(e.objectKind(), 1L, Long::sum);
            switch (e.sizeKind()) {
                case SAMPLE_WEIGHT -> {
                    samples++;
                    sampleWeightBytes += e.sizeBytes();
                }
                case TLAB_SIZE -> {
                    tlabRefills++;
                    tlabBytes += e.sizeBytes();
                }
                case EXACT -> {
                    outsideTlab++;
                    outsideTlabBytes += e.sizeBytes();
                }
                default -> { }
            }
        }

        void write(ObjectNode n) {
            n.put("events", events);
            n.put("samples", samples);
            n.put("sample_weight_bytes", sampleWeightBytes);
            n.put("tlab_refills", tlabRefills);
            n.put("tlab_bytes", tlabBytes);
            n.put("outside_tlab", outsideTlab);
            n.put("outside_tlab_bytes", outsideTlabBytes);
            ObjectNode kinds = n.putObject("by_kind");
            for (ObjectKind k : ObjectKind.values()) {
                kinds.put(k.json(), byKind.getOrDefault(k, 0L));
            }
        }
    }

    // ── timeline.json / stacks.json ──────────────────────────────────────────

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
                gen.writeNumberField("threadId", e.threadId());
                gen.writeNumberField("size", e.sizeBytes());
                gen.writeStringField("sizeKind", e.sizeKind().name().toLowerCase(java.util.Locale.ROOT));
                gen.writeStringField("kind", e.objectKind().json());
                gen.writeStringField("category", e.ownerCategory().json());
                gen.writeStringField("owner", e.owner() != null ? e.owner().describe() : null);
                gen.writeStringField("stackId", stackId);
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

    // ── alloc-top-classes.json ───────────────────────────────────────────────

    private void writeAllocTopClasses(String module, Path moduleOutDir, List<AllocEvent> events) throws IOException {
        Map<String, Stats> byKey = new LinkedHashMap<>();
        Map<String, AllocEvent> sample = new HashMap<>();
        for (AllocEvent e : events) {
            String key = e.className() + "|" + e.ownerCategory().json() + "|" + e.objectKind().json();
            byKey.computeIfAbsent(key, k -> new Stats()).add(e);
            sample.putIfAbsent(key, e);
        }

        ArrayNode top = JsonNodeFactoryHolder.array();
        byKey.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Stats> en) -> en.getValue().events).reversed())
                .forEach(en -> {
                    AllocEvent e = sample.get(en.getKey());
                    ObjectNode item = top.addObject();
                    item.put(FIELD_CLASS, e.className());
                    item.put("kind", e.objectKind().json());
                    item.put("category", e.ownerCategory().json());
                    item.put(FIELD_COUNT, en.getValue().events);
                    en.getValue().write(item);
                });

        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(moduleOutDir.resolve("alloc-top-classes.json").toFile(), top);
        final int count = top.size();
        LOGGER.info(() -> LOG_PREFIX_WROTE + module + "/alloc-top-classes.json (" + count + " classes)");
    }

    // ── jfr-summary.json ─────────────────────────────────────────────────────

    private void writeJfrSummary(Map<String, Partition> perModule) throws IOException {
        ObjectNode root = mapper.createObjectNode();

        for (Map.Entry<String, Partition> moduleEntry : perModule.entrySet()) {
            List<RecordingData> identified = moduleEntry.getValue().bySubsystem().values().stream()
                    .flatMap(List::stream).toList();
            List<AllocEvent> allEvents = flatten(identified);
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

            writeTopClasses(moduleNode.putArray("topClasses"), allEvents, 20);

            ArrayNode phases = moduleNode.putArray("phaseBoundaries");
            for (RecordingData r : identified) {
                if (!r.windowed()) {
                    continue;
                }
                TckMarker.Window w = r.window();
                ObjectNode p = phases.addObject();
                p.put("subsystem", r.identity().subsystem());
                p.put("test_class", w.testClass());
                p.put(FIELD_FILE, r.file().getFileName().toString());
                p.put("start", w.start().toEpochMilli());
                p.put("end", w.end().toEpochMilli());
                p.put("thread_id", w.workloadThreadId());
                p.put("iterations", w.iterations());
            }

            ArrayNode unattributed = moduleNode.putArray(UNATTRIBUTED);
            for (Unattributed u : moduleEntry.getValue().unattributed()) {
                RecordingData r = u.recording();
                ObjectNode un = unattributed.addObject();
                un.put(FIELD_FILE, r.file().getFileName().toString());
                un.put("reason", u.reason());
                un.put("windows", r.windows().size());
                un.put("events", r.events().size());
                writeTopClasses(un.putArray("topClasses"), r.events(), 10);
            }
        }

        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(outDir.resolve("jfr-summary.json").toFile(), root);
        LOGGER.info(() -> LOG_PREFIX_WROTE + "jfr-summary.json");
    }

    private static void writeTopClasses(ArrayNode target, List<AllocEvent> events, int limit) {
        Map<String, Long> classCounts = new LinkedHashMap<>();
        for (AllocEvent e : events) {
            classCounts.merge(e.className(), 1L, Long::sum);
        }
        classCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .forEach(entry -> {
                    ObjectNode c = target.addObject();
                    c.put(FIELD_CLASS, entry.getKey());
                    c.put(FIELD_COUNT, entry.getValue());
                });
    }

    private String resolveStackId(List<Frame> frames,
                                  Map<String, List<String>> stacksMap,
                                  Map<String, String> stackIdCache,
                                  AtomicInteger counter) {
        List<String> rendered = frames.stream().map(Frame::describe).toList();
        String fingerprint = String.join("|", rendered);
        return stackIdCache.computeIfAbsent(fingerprint, k -> {
            String id = "stk_" + String.format("%04d", counter.incrementAndGet());
            stacksMap.put(id, rendered);
            return id;
        });
    }

    /** One place to mint detached array nodes. */
    private static final class JsonNodeFactoryHolder {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private JsonNodeFactoryHolder() {}

        static ArrayNode array() {
            return MAPPER.createArrayNode();
        }
    }
}
