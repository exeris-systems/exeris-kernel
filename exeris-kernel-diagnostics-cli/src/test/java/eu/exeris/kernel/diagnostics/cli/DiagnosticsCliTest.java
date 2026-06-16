/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.diagnostics.cli;

import eu.exeris.kernel.spi.diagnostics.BootstrapDagSnapshot;
import eu.exeris.kernel.spi.diagnostics.CompositionSnapshot;
import eu.exeris.kernel.spi.diagnostics.DagNode;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnostics;
import eu.exeris.kernel.spi.diagnostics.ProviderDescriptor;
import eu.exeris.kernel.spi.diagnostics.ProvidersSnapshot;
import eu.exeris.kernel.spi.diagnostics.SubsystemDescriptor;
import eu.exeris.kernel.spi.diagnostics.SubsystemSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DiagnosticsCli — NDJSON request dispatch")
class DiagnosticsCliTest {

    private final DiagnosticsCli cli = new DiagnosticsCli(new FakeDiagnostics(), DiagnosticsCli.newMapper());

    @Test
    @DisplayName("listProviders serialises schemaVersion first and the provider list")
    void listProviders() {
        String out = cli.handle("{\"method\":\"listProviders\"}");
        assertThat(out).startsWith("{\"schemaVersion\":\"1.0\"");
        assertThat(out).contains("\"providerName\":\"ExerisTest/Telemetry\"");
        assertThat(out).contains("\"spiType\":\"telemetry\"");
    }

    @Test
    @DisplayName("capturedAt is an ISO-8601 string, not an epoch number")
    void instantIsIso() {
        String out = cli.handle("{\"method\":\"getBootstrapDag\"}");
        assertThat(out).contains("\"capturedAt\":\"").contains("Z\"");
    }

    @Test
    @DisplayName("getJvmErgonomics serialises the ergonomics snapshot (default-method path on the fake)")
    void jvmErgonomics() {
        String out = cli.handle("{\"method\":\"getJvmErgonomics\"}");
        assertThat(out).startsWith("{\"schemaVersion\":\"1.0\"");
        assertThat(out).contains("\"gcName\":").contains("\"availableProcessors\":");
    }

    @Test
    @DisplayName("describeSubsystem with a known name returns the subsystem detail")
    void describeKnown() {
        String out = cli.handle("{\"method\":\"describeSubsystem\",\"name\":\"memory\"}");
        assertThat(out).contains("\"requestedName\":\"memory\"");
        assertThat(out).contains("\"subsystem\":{").contains("\"phase\":\"FOUNDATION\"");
    }

    @Test
    @DisplayName("describeSubsystem without a name is an error")
    void describeMissingName() {
        assertThat(cli.handle("{\"method\":\"describeSubsystem\"}"))
                .contains("\"error\"").contains("name");
    }

    @Test
    @DisplayName("unknown method is an error")
    void unknownMethod() {
        assertThat(cli.handle("{\"method\":\"frobnicate\"}"))
                .contains("\"error\"").contains("frobnicate");
    }

    @Test
    @DisplayName("malformed JSON is an error, never an exception")
    void malformed() {
        assertThat(cli.handle("this is not json")).contains("\"error\"");
    }

    @Test
    @DisplayName("missing method field is an error")
    void missingMethod() {
        assertThat(cli.handle("{}")).contains("\"error\"").contains("method");
    }

    /** Deterministic in-memory {@link KernelDiagnostics} for dispatch tests. */
    private static final class FakeDiagnostics implements KernelDiagnostics {
        @Override
        public ProvidersSnapshot listProviders() {
            return ProvidersSnapshot.capture(List.of(
                    new ProviderDescriptor("ExerisTest/Telemetry", "telemetry", 0, Optional.empty())));
        }

        @Override
        public CompositionSnapshot listCapabilities() {
            return CompositionSnapshot.capture(List.of());
        }

        @Override
        public BootstrapDagSnapshot getBootstrapDag() {
            return BootstrapDagSnapshot.capture(List.of(
                    new DagNode("memory", "FOUNDATION", List.of(), false, false)));
        }

        @Override
        public SubsystemSnapshot describeSubsystem(String name) {
            Optional<SubsystemDescriptor> detail = "memory".equals(name)
                    ? Optional.of(new SubsystemDescriptor("memory", "FOUNDATION", List.of(), false, false))
                    : Optional.empty();
            return SubsystemSnapshot.capture(name, detail);
        }
    }
}
