/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Reads every {@code .jfr} under a directory into one {@link RecordingData} per file: the
 * allocation events with both attributions applied, and the window marker when the TCK wrote one.
 */
final class JfrDirectoryReader {

    private static final Logger LOGGER = Logger.getLogger(JfrDirectoryReader.class.getName());
    private static final String LOG_PREFIX = "[jfr-reporter] ";

    private static final Set<String> ALLOC_TYPES = Set.of(
            SizePolicy.IN_NEW_TLAB,
            SizePolicy.OUTSIDE_TLAB,
            SizePolicy.SAMPLE
    );

    /**
     * One recording as read.
     *
     * @param file     the recording
     * @param identity where it belongs: from the marker when the file holds exactly one window,
     *                 from the file name when it holds none, {@code NONE} when it holds several
     * @param windows  every marker pair in the file
     * @param events   the allocation events, in file order
     */
    record RecordingData(Path file, RecordingIdentity identity, List<TckMarker.Window> windows, List<AllocEvent> events) {

        /** The measured window, or {@code null} unless the file holds exactly one. */
        TckMarker.Window window() {
            return windows.size() == 1 ? windows.get(0) : null;
        }

        boolean windowed() {
            return window() != null;
        }

        /** The events that belong to this recording's subsystem: those inside the window when there is one. */
        List<AllocEvent> attributedEvents() {
            TckMarker.Window w = window();
            if (w == null) {
                return events;
            }
            return events.stream().filter(e -> w.contains(e.tEpochMillis())).toList();
        }

        boolean tckShapedName() {
            return RecordingIdentity.fromFilename(file.getFileName().toString()).identified();
        }
    }

    private JfrDirectoryReader() {}

    /**
     * Reads all {@code .jfr} files below {@code dir}, sorted by path.
     *
     * @param dir the module's {@code target/} directory
     * @return one entry per file
     * @throws IOException if the directory cannot be walked
     */
    static List<RecordingData> readDirectory(Path dir) throws IOException {
        List<RecordingData> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> jfrFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jfr"))
                    .sorted()
                    .toList();
            for (Path jfrFile : jfrFiles) {
                RecordingData data = readFile(jfrFile);
                result.add(data);
                LOGGER.info(() -> LOG_PREFIX + "  " + jfrFile.getFileName()
                        + " → subsystem=" + data.identity().subsystem()
                        + " source=" + data.identity().source()
                        + " events=" + data.events().size());
            }
        }
        return result;
    }

    static RecordingData readFile(Path jfrFile) {
        List<AllocEvent> events = new ArrayList<>();
        List<TckMarker.Boundary> boundaries = new ArrayList<>();
        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                if (TckMarker.isMarker(event)) {
                    boundaries.add(TckMarker.read(event));
                    continue;
                }
                AllocEvent alloc = toAllocEvent(event);
                if (alloc != null) {
                    events.add(alloc);
                }
            }
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, ex, () -> LOG_PREFIX + "WARN: failed to read " + jfrFile);
        }
        List<TckMarker.Window> windows = TckMarker.pairAll(boundaries);
        String fileName = jfrFile.getFileName().toString();
        RecordingIdentity identity;
        if (windows.size() == 1) {
            identity = RecordingIdentity.fromMarker(windows.get(0).subsystem(), windows.get(0).testClass());
        } else if (windows.isEmpty()) {
            identity = RecordingIdentity.fromFilename(fileName);
        } else {
            identity = RecordingIdentity.none();
        }
        return new RecordingData(jfrFile, identity, windows, events);
    }

    private static AllocEvent toAllocEvent(RecordedEvent event) {
        String typeName = event.getEventType().getName();
        if (!ALLOC_TYPES.contains(typeName)) {
            return null;
        }
        String className = extractClassName(event);
        if (className == null) {
            return null;
        }
        long allocationSize = longField(event, "allocationSize");
        SizePolicy.Size size = SizePolicy.sizeOf(typeName,
                allocationSize, longField(event, "weight"), longField(event, "tlabSize"));
        RecordedThread thread = event.getThread();
        String threadName = thread != null && thread.getJavaName() != null ? thread.getJavaName() : "unknown";
        long threadId = thread != null ? thread.getJavaThreadId() : -1L;
        List<Frame> frames = extractFrames(event);
        Frame owner = EventClassifier.ownerFrame(frames);
        return new AllocEvent(
                event.getStartTime().toEpochMilli(),
                typeName,
                className,
                threadName,
                threadId,
                size.bytes(),
                size.kind(),
                allocationSize,
                frames,
                owner,
                EventClassifier.classifyOwner(owner),
                EventClassifier.classifyObject(className));
    }

    private static String extractClassName(RecordedEvent e) {
        if (!e.hasField("objectClass")) {
            return null;
        }
        RecordedClass objClass = e.getValue("objectClass");
        return objClass != null ? objClass.getName() : null;
    }

    private static long longField(RecordedEvent e, String name) {
        return e.hasField(name) ? e.getLong(name) : 0L;
    }

    private static List<Frame> extractFrames(RecordedEvent e) {
        List<Frame> frames = new ArrayList<>();
        RecordedStackTrace stack = e.getStackTrace();
        if (stack == null) {
            return frames;
        }
        for (RecordedFrame frame : stack.getFrames()) {
            if (frame.getMethod() != null && frame.getMethod().getType() != null) {
                frames.add(new Frame(
                        frame.getMethod().getType().getName(),
                        frame.getMethod().getName(),
                        frame.getLineNumber()));
            }
        }
        return frames;
    }
}
