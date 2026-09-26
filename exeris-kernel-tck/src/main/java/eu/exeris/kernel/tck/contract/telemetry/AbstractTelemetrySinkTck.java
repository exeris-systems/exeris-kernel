/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.telemetry;

import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import eu.exeris.kernel.spi.telemetry.EventLevel;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies that a {@link TelemetrySink} accepts every event shape {@link KernelEvent} can carry
 * without throwing, and behaves correctly across {@code close()}.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Accepts {@link EventLevel#INFO}, {@link EventLevel#WARN} and {@link EventLevel#ERROR}
 *       without throwing; {@link EventLevel#FATAL} is not exercised by this class (see
 *       {@code AbstractTelemetryProviderTck#fatalBroadcast()} for that level)</li>
 *   <li>Accepts a {@code null} {@link KernelEvent#exception()} without throwing</li>
 *   <li>Is idempotently closeable — {@link TelemetrySink#close()} twice must not throw</li>
 *   <li>{@code emit()} does not throw after {@link TelemetrySink#close()}; the test does not read
 *       the sink's own output channel back, so it establishes the absence of an exception, not that
 *       the event was actually dropped rather than delivered</li>
 * </ul>
 *
 * <h2>How to use</h2>
 * {@snippet lang="java" :
 * class ConsoleSinkTckTest extends AbstractTelemetrySinkTck {
 *     @Override protected TelemetrySink createSink() { return new ConsoleSink(); }
 * }
 * }
 *
 * @since 0.5
 */
public abstract class AbstractTelemetrySinkTck {

    /**
     * Subclass supplies the sink under test. Called once before each test.
     *
     * @return a newly created, open sink
     */
    protected abstract TelemetrySink createSink();

    private TelemetrySink sink;

    /**
     * Creates the contract; subclasses supply the binding via {@link #createSink()}.
     */
    public AbstractTelemetrySinkTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @BeforeEach
    final void setUp() {
        sink = createSink();
    }

    @AfterEach
    final void tearDown() {
        sink.close();
    }

    // =========================================================================
    // emit() contract
    // =========================================================================

    @Test
    @DisplayName("emit() INFO event does not throw")
    void emitInfoDoesNotThrow() {
        KernelEvent event = KernelEvent.info("EX-TEST-001", "TCK");
        assertThatCode(() -> sink.emit(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("emit() WARN event does not throw")
    void emitWarnDoesNotThrow() {
        KernelEvent event = KernelEvent.warn("EX-TEST-002", "TCK",
                new MemoryExhaustedException(1024L, 0L, null));
        assertThatCode(() -> sink.emit(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("emit() ERROR event with exception does not throw")
    void emitErrorWithExceptionDoesNotThrow() {
        KernelEvent event = KernelEvent.error("EX-TEST-003", "TCK",
                new MemoryExhaustedException(4096L, 512L, new OutOfMemoryError("oom")));
        assertThatCode(() -> sink.emit(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("emit() INFO without exception does not throw")
    void emitInfoWithoutExceptionDoesNotThrow() {
        KernelEvent event = KernelEvent.info("EX-TEST-004", "TCK");
        assertThatCode(() -> sink.emit(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("emit() accepts WARN and ERROR levels without throwing")
    void emitWarnAndErrorAccepted() {
        assertThatCode(() ->
                sink.emit(KernelEvent.warn("EX-W", "TCK",
                        new MemoryExhaustedException(1L, 0L, null))))
                .doesNotThrowAnyException();
        assertThatCode(() ->
                sink.emit(KernelEvent.error("EX-E", "TCK",
                        new MemoryExhaustedException(1L, 0L, null))))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // close() contract
    // =========================================================================

    @Test
    @DisplayName("close() is idempotent — calling twice does not throw")
    void closeIsIdempotent() {
        sink.close();
        assertThatCode(() -> sink.close()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("emit() after close() silently drops the event — does not throw")
    void emitAfterCloseIsNoop() {
        sink.close();
        KernelEvent event = KernelEvent.info("EX-TEST-CLOSED", "TCK");
        assertThatCode(() -> sink.emit(event)).doesNotThrowAnyException();
    }

    // =========================================================================
    // KernelEvent contract
    // =========================================================================

    @Test
    @DisplayName("KernelEvent fields are correctly populated")
    void kernelEventFields() {
        MemoryExhaustedException ex = new MemoryExhaustedException(1L, 0L, null);
        KernelEvent event = KernelEvent.error("EX-MEM-0001", "MemorySubsystem", ex);
        assertThat(event.code()).isEqualTo("EX-MEM-0001");
        assertThat(event.component()).isEqualTo("MemorySubsystem");
        assertThat(event.level()).isEqualTo(EventLevel.ERROR);
        assertThat(event.exception()).isSameAs(ex);
    }
}
