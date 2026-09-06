/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;

import java.io.PrintStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Community: {@link TelemetrySink} that writes human-readable output to {@link System#out}
 * (or a configurable {@link PrintStream}).
 *
 * <h2>Zero-Allocation Note</h2>
 * <p>This sink intentionally calls {@code getMessage()} and formats strings — it is a
 * <b>diagnostic, low-frequency</b> path. For production use, prefer {@link JfrTelemetrySink} or
 * the Enterprise {@code BinaryGlassBoxSink}. This sink MUST NOT be used in benchmarks
 * or hot-path testing.
 *
 * @since 0.5
 */
public final class ConsoleSink implements TelemetrySink {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private volatile boolean closed;
    private final PrintStream out;

    /**
     * Creates a sink that writes to {@link System#out}.
     */
    public ConsoleSink() {
        this(System.out);
    }

    /**
     * Creates a sink that writes to the given stream.
     *
     * @param out destination stream; never closed by {@link #close()}
     */
    public ConsoleSink(PrintStream out) {
        this.out = out;
    }

    /**
     * Writes one human-readable line for {@code event} to the configured stream,
     * or does nothing if this sink is closed.
     *
     * <p>Calls {@link eu.exeris.kernel.spi.exceptions.ExerisKernelException#getMessage()}
     * when an exception is attached, which is acceptable only because this is a
     * diagnostic, low-frequency path — see the class-level allocation note.
     *
     * @param event the kernel event to record; never {@code null}
     */
    @Override
    public void emit(KernelEvent event) {
        if (closed) {
            return;
        }
        String timestamp = FMT.format(event.timestamp());
        String level = event.level().name();
        String code  = event.code();
        String comp  = event.component();
        String msg   = event.exception() != null ? event.exception().getMessage() : "(no exception)";
        out.printf("[%s] [%s] [%s] %s — %s%n", timestamp, level, code, comp, msg);
    }

    /**
     * No-op — counter increments are too noisy for a human-readable console stream.
     */
    @Override
    public void increment(String name, long delta) {
        // Console sink: counter increments are silent (too noisy for human output)
    }

    /**
     * No-op — gauge updates are too noisy for a human-readable console stream.
     */
    @Override
    public void gauge(String name, long value) {
        // Console sink: gauge updates are silent
    }

    /**
     * No-op — latency samples are too noisy for a human-readable console stream.
     */
    @Override
    public void latency(String name, long nanoseconds) {
        // Console sink: latency samples are silent
    }

    /**
     * Returns {@code "ExerisCommunity/ConsoleSink"}.
     */
    @Override
    public String sinkName() {
        return "ExerisCommunity/ConsoleSink";
    }

    /**
     * Marks this sink closed; subsequent {@link #emit(KernelEvent)} calls do nothing.
     *
     * <p>Does not close the underlying stream — {@link System#out} and any
     * caller-supplied {@link PrintStream} are owned by their creator, not this sink.
     */
    @Override
    public void close() {
        closed = true;
        // System.out is not owned by this sink — do not close it
    }
}

