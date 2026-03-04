/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.telemetry;

import eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit: {@link KernelEvent} factory method contracts.
 *
 * @since 0.5.0
 */
@DisplayName("KernelEvent — factory method contract")
class KernelEventTest {

    private static final MemoryExhaustedException CAUSE = new MemoryExhaustedException(4096L, 512L, null);

    // =========================================================================
    // info()
    // =========================================================================

    @Test
    @DisplayName("info() — level is INFO, exception is null")
    void info_levelAndNullException() {
        KernelEvent e = KernelEvent.info("EX-TEST-001", "TestComponent");

        assertThat(e.level()).isEqualTo(EventLevel.INFO);
        assertThat(e.exception()).isNull();
    }

    @Test
    @DisplayName("info() — code and component are propagated")
    void info_codeAndComponent() {
        KernelEvent e = KernelEvent.info("EX-TEST-001", "TestComponent");

        assertThat(e.code()).isEqualTo("EX-TEST-001");
        assertThat(e.component()).isEqualTo("TestComponent");
    }

    @Test
    @DisplayName("info() — timestamp is not null and not in the future")
    void info_timestampBound() {
        KernelEvent e = KernelEvent.info("EX-TEST-001", "TestComponent");
        Instant now = Instant.now();

        assertThat(e.timestamp()).isNotNull();
        assertThat(e.timestamp()).isBeforeOrEqualTo(now.plusMillis(10));
    }

    // =========================================================================
    // warn()
    // =========================================================================

    @Test
    @DisplayName("warn() — level is WARN, exception is propagated")
    void warn_levelAndException() {
        KernelEvent e = KernelEvent.warn("EX-TEST-002", "TestComponent", CAUSE);

        assertThat(e.level()).isEqualTo(EventLevel.WARN);
        assertThat(e.exception()).isSameAs(CAUSE);
    }

    @Test
    @DisplayName("warn() — accepts null exception")
    void warn_acceptsNullException() {
        KernelEvent e = KernelEvent.warn("EX-TEST-002", "TestComponent", null);

        assertThat(e.exception()).isNull();
    }

    // =========================================================================
    // error()
    // =========================================================================

    @Test
    @DisplayName("error() — level is ERROR, exception is propagated")
    void error_levelAndException() {
        KernelEvent e = KernelEvent.error("EX-TEST-003", "TestComponent", CAUSE);

        assertThat(e.level()).isEqualTo(EventLevel.ERROR);
        assertThat(e.exception()).isSameAs(CAUSE);
    }

    @Test
    @DisplayName("error() — accepts null exception")
    void error_acceptsNullException() {
        KernelEvent e = KernelEvent.error("EX-TEST-003", "TestComponent", null);

        assertThat(e.exception()).isNull();
    }

    // =========================================================================
    // fatal()
    // =========================================================================

    @Test
    @DisplayName("fatal() — level is FATAL, exception is propagated")
    void fatal_levelAndException() {
        KernelEvent e = KernelEvent.fatal("EX-TEST-004", "TestComponent", CAUSE);

        assertThat(e.level()).isEqualTo(EventLevel.FATAL);
        assertThat(e.exception()).isSameAs(CAUSE);
    }

    @Test
    @DisplayName("fatal() — accepts null exception")
    void fatal_acceptsNullException() {
        KernelEvent e = KernelEvent.fatal("EX-TEST-004", "TestComponent", null);

        assertThat(e.exception()).isNull();
    }
}

