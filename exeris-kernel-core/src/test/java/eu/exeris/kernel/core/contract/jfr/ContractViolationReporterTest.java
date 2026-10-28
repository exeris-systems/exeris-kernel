/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract.jfr;

import eu.exeris.kernel.spi.contract.ContractViolation;
import eu.exeris.kernel.spi.contract.EnforcementLevel;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContractViolationReporter — SOFT warns and records, AUDIT only records")
public class ContractViolationReporterTest {

    private static final String EVENT = "eu.exeris.kernel.contract.Enforcement";

    /**
     * Collects the records the reporter's logger publishes while {@code action} runs.
     *
     * @param action code that may report violations
     * @return the published records
     */
    public static List<LogRecord> captureWarnings(Runnable action) {
        Logger logger = Logger.getLogger(ContractViolationReporter.class.getName());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord logRecord) { records.add(logRecord); }
            @Override public void flush() { /* in memory */ }
            @Override public void close() { /* in memory */ }
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return records;
    }

    @Test
    @DisplayName("SOFT: one WARNING naming contract, constraint and subject")
    void softLogsWarning() {
        List<LogRecord> records = captureWarnings(() -> ContractViolationReporter.report(
                new ContractViolation("EXR-SOFT-LOG", "capability", EnforcementLevel.SOFT, "gateway.core")));

        assertThat(records).singleElement().satisfies(r -> {
            assertThat(r.getLevel()).isEqualTo(Level.WARNING);
            assertThat(java.text.MessageFormat.format(r.getMessage(), r.getParameters()))
                    .contains("EXR-SOFT-LOG").contains("capability").contains("gateway.core").contains("SOFT")
                    .doesNotContain("not permitted");
        });
    }

    @Test
    @DisplayName("AUDIT: nothing is logged")
    void auditLogsNothing() {
        List<LogRecord> records = captureWarnings(() -> ContractViolationReporter.report(
                new ContractViolation("EXR-AUDIT-LOG", "environment", EnforcementLevel.AUDIT, "production")));

        assertThat(records).isEmpty();
    }

    @Test
    @DisplayName("Control, format and line-separator characters in a reported value cannot forge a log line")
    void controlCharactersAreNeutralized() {
        List<LogRecord> records = captureWarnings(() -> ContractViolationReporter.report(
                new ContractViolation("EXR-CTRL", "capability", EnforcementLevel.SOFT,
                        "x\nA\u0085B\u2028C\u202eD")));

        assertThat(records).singleElement().satisfies(r ->
                assertThat(java.text.MessageFormat.format(r.getMessage(), r.getParameters()))
                        .contains("x?A?B?C?D")
                        .doesNotContain("\n", "\u0085", "\u2028", "\u202e"));
    }

    @Test
    @DisplayName("The same violation is reported once; a different subject is reported again")
    void repeatedViolationIsReportedOnce() {
        ContractViolation first = new ContractViolation("EXR-DEDUPE", "capability", EnforcementLevel.SOFT, "a");
        List<LogRecord> records = captureWarnings(() -> {
            ContractViolationReporter.report(first);
            ContractViolationReporter.report(new ContractViolation("EXR-DEDUPE", "capability", EnforcementLevel.SOFT, "a"));
            ContractViolationReporter.report(new ContractViolation("EXR-DEDUPE", "capability", EnforcementLevel.SOFT, "b"));
        });

        assertThat(records).hasSize(2);
    }

    @Test
    @DisplayName("SOFT and AUDIT both emit the Enforcement JFR event with their level")
    void bothLevelsEmitTheEvent(@TempDir Path dir) throws Exception {
        Path dump = dir.resolve("enforcement.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(EVENT);
            recording.start();
            ContractViolationReporter.report(
                    new ContractViolation("EXR-SOFT", "capability", EnforcementLevel.SOFT, "gateway.core"));
            ContractViolationReporter.report(
                    new ContractViolation("EXR-AUDIT", "environment", EnforcementLevel.AUDIT, "production"));
            recording.stop();
            recording.dump(dump);
        }

        List<RecordedEvent> events = RecordingFile.readAllEvents(dump).stream()
                .filter(e -> e.getEventType().getName().equals(EVENT))
                .toList();
        assertThat(events).extracting(e -> e.getString("contractId") + ":" + e.getString("enforcementLevel"))
                .containsExactlyInAnyOrder("EXR-SOFT:SOFT", "EXR-AUDIT:AUDIT");
    }
}
