/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Community: {@link TelemetrySink} that appends kernel lifecycle events to a log file.
 *
 * <h2>Implementation</h2>
 * <p>Events are queued in a bounded {@link ArrayBlockingQueue} and drained by a
 * dedicated Virtual Thread (not a carrier thread — no pinning). If the queue is full,
 * the event is dropped and a {@link #droppedCount()} counter is incremented
 * (backpressure, NOT blocking on the caller's thread).
 *
 * <h2>Zero-Allocation on the Hot Path</h2>
 * <p>{@link #emit} only enqueues the event reference — no {@code String.format()} occurs
 * on the caller thread. All formatting happens on the writer Virtual Thread.
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent invocation from any number of Virtual Threads.
 * {@link #close()} is idempotent.
 *
 * @since 0.5.0
 */
public final class FileSink implements TelemetrySink {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                             .withZone(ZoneOffset.UTC);

    private static final KernelEvent POISON = KernelEvent.info("POISON", "FileSink");

    private final Path logPath;
    private final BlockingQueue<KernelEvent> queue;
    private final Thread writerThread;
    private final AtomicLong droppedCount = new AtomicLong(0L);
    private volatile boolean running = true;

    /**
     * @param logPath       target file; created if absent, appended to if present
     * @param maxQueueDepth maximum number of events buffered before drops start
     */
    public FileSink(Path logPath, int maxQueueDepth) {
        // Fail-fast: validate path is writable before starting the writer thread.
        try (BufferedWriter probe = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            // probe only — immediately closed
        } catch (IOException e) {
            throw new UncheckedIOException("FileSink cannot open log file: " + logPath, e);
        }
        this.logPath = logPath;
        this.queue   = new ArrayBlockingQueue<>(maxQueueDepth);
        this.writerThread = Thread.ofVirtual()
              .name("exeris-telemetry-file-writer")
              .start(this::writeLoop);
    }

    // =========================================================================
    // TelemetrySink
    // =========================================================================

    @Override
    public void emit(KernelEvent event) {
        if (!running) {
            droppedCount.incrementAndGet();
            return;
        }
        if (!queue.offer(event)) {
            droppedCount.incrementAndGet();
        }
    }

    /** Counter increments are not persisted to file (too noisy). Use {@link JfrTelemetrySink} for metrics. */
    @Override
    public void increment(String name, long delta) {
        // Intentionally empty: metric counters are not written to the file log.
        // Use JfrTelemetrySink for structured metric capture.
    }

    @Override
    public void gauge(String name, long value) {
        // Intentionally empty: gauge values are not written to the file log.
    }

    @Override
    public void latency(String name, long nanoseconds) {
        // Intentionally empty: latency samples are not written to the file log.
    }

    @Override
    public String sinkName() {
        return "ExerisCommunity/FileSink[" + logPath + "]";
    }

    @Override
    public void close() {
        if (running) {
            running = false;
            if (!queue.offer(POISON)) {
                // Queue saturated — writer loop will exit via the !running + isEmpty() guard.
                droppedCount.incrementAndGet();
            }
        }
        // Wait for the writer thread to finish and close the file handle.
        // Required for test correctness (@TempDir cleanup) and graceful shutdown.
        boolean interrupted = false;
        while (writerThread.isAlive()) {
            try {
                writerThread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================================
    // Diagnostics
    // =========================================================================

    /** Returns events dropped due to queue saturation since startup. Accessible via JFR or stats API. */
    /* default */ long droppedCount() {
        return droppedCount.get();
    }

    // =========================================================================
    // Writer loop — runs on a dedicated Virtual Thread
    // =========================================================================

    private void writeLoop() {
        try (BufferedWriter writer = Files.newBufferedWriter(
                logPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {

            KernelEvent event = queue.poll(100L, TimeUnit.MILLISECONDS);
            while (event != POISON) {
                if (event != null) {
                    writeLine(writer, event);
                } else if (!running && queue.isEmpty()) {
                    break; // single exit point when shutdown + empty
                }
                event = queue.poll(100L, TimeUnit.MILLISECONDS);
            }
            // Drain any remaining events enqueued before POISON arrived
            KernelEvent remaining = queue.poll();
            while (remaining != null && remaining != POISON) {
                writeLine(writer, remaining);
                remaining = queue.poll();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (IOException _) {
            // File write failure — stop accepting future events; telemetry MUST NOT crash the kernel.
            running = false;
        }
    }

    private void writeLine(BufferedWriter writer, KernelEvent event) throws IOException {
        String timestamp = FMT.format(event.timestamp());
        String level = event.level().name();
        String comp  = event.component();
        String code  = event.code();
        String msg   = event.exception() != null ? event.exception().getMessage() : "(none)";
        writer.write(timestamp + " [" + level + "] [" + code + "] " + comp + " — " + msg);
        writer.newLine();
    }
}
