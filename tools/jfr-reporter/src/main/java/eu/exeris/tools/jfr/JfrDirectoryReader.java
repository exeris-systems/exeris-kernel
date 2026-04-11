package eu.exeris.tools.jfr;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class JfrDirectoryReader {

    private static final Set<String> ALLOC_TYPES = Set.of(
            "jdk.ObjectAllocationInNewTLAB",
            "jdk.ObjectAllocationOutsideTLAB",
            "jdk.ObjectAllocationSample"
    );

    private JfrDirectoryReader() {}

    /**
     * Reads all .jfr files in dir, groups events by subsystem extracted from filename.
     * Filename pattern from JfrAllocationMonitor: {TestClass}-{Subsystem}-{yyyyMMdd}-{HHmmss}.jfr
     */
    static Map<String, List<AllocEvent>> readDirectory(Path dir) throws IOException {
        Map<String, List<AllocEvent>> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.list(dir)) {
            List<Path> jfrFiles = paths
                    .filter(p -> p.getFileName().toString().endsWith(".jfr"))
                    .sorted()
                    .toList();
            for (Path jfrFile : jfrFiles) {
                String subsystem = extractSubsystem(jfrFile.getFileName().toString());
                List<AllocEvent> events = readFile(jfrFile);
                result.computeIfAbsent(subsystem, k -> new ArrayList<>()).addAll(events);
                System.out.println("[jfr-reporter]   " + jfrFile.getFileName() + " → subsystem=" + subsystem + " events=" + events.size());
            }
        }
        return result;
    }

    private static String extractSubsystem(String filename) {
        String base = filename.endsWith(".jfr") ? filename.substring(0, filename.length() - 4) : filename;
        String[] parts = base.split("-");
        if (parts.length >= 2) {
            return parts[1].toLowerCase(Locale.ROOT);
        }
        return "unknown";
    }

    private static List<AllocEvent> readFile(Path jfrFile) {
        List<AllocEvent> events = new ArrayList<>();
        Instant recordingStart = null;
        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                if (recordingStart == null) {
                    recordingStart = e.getStartTime();
                }
                String typeName = e.getEventType().getName();
                if (!ALLOC_TYPES.contains(typeName)) continue;

                RecordedClass objClass = null;
                try { objClass = e.getValue("objectClass"); } catch (Exception ignored) {}
                if (objClass == null) continue;

                String className = objClass.getName();

                long sizeBytes = 0;
                try { sizeBytes = e.getLong("allocationSize"); } catch (Exception ignored) {}
                if (sizeBytes == 0) {
                    try { sizeBytes = e.getLong("weight"); } catch (Exception ignored) {}
                }

                String threadName = "unknown";
                RecordedThread thread = e.getThread();
                if (thread != null && thread.getJavaName() != null) {
                    threadName = thread.getJavaName();
                }

                List<String> frames = new ArrayList<>();
                RecordedStackTrace stack = e.getStackTrace();
                if (stack != null) {
                    for (RecordedFrame frame : stack.getFrames()) {
                        if (frame.getMethod() != null && frame.getMethod().getType() != null) {
                            String fqn = frame.getMethod().getType().getName();
                            int dot = fqn.lastIndexOf('.');
                            String simpleName = dot >= 0 ? fqn.substring(dot + 1) : fqn;
                            frames.add(fqn
                                    + "." + frame.getMethod().getName()
                                    + "(" + simpleName
                                    + ".java:" + frame.getLineNumber() + ")");
                        }
                    }
                }

                Category category = EventClassifier.classify(className, frames);
                long tMillis = recordingStart != null
                        ? e.getStartTime().toEpochMilli() - recordingStart.toEpochMilli()
                        : 0;

                events.add(new AllocEvent(tMillis, typeName, className, threadName, sizeBytes, frames, category));
            }
        } catch (IOException ex) {
            System.err.println("[jfr-reporter] WARN: failed to read " + jfrFile + ": " + ex.getMessage());
        }
        return events;
    }
}
