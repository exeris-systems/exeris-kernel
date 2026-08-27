/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.diagnostics.cli;

import eu.exeris.kernel.spi.diagnostics.BootstrapDagSnapshot;
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
import java.util.ServiceConfigurationError;

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

    @Test
    @DisplayName("a provider that fails to instantiate is an error response, not a dead process")
    void providerFailureIsAnErrorResponse() {
        DiagnosticsCli failing = new DiagnosticsCli(new ExplodingDiagnostics(), DiagnosticsCli.newMapper());

        String out = failing.handle("{\"method\":\"listProviders\"}");

        assertThat(out).contains("\"error\"").contains("listProviders").contains("Broken/Provider");
    }

    @Test
    @DisplayName("the channel survives the failure — the next method still answers")
    void failureDoesNotPoisonTheSession() {
        DiagnosticsCli failing = new DiagnosticsCli(new ExplodingDiagnostics(), DiagnosticsCli.newMapper());

        failing.handle("{\"method\":\"listProviders\"}");

        assertThat(failing.handle("{\"method\":\"getBootstrapDag\"}"))
                .startsWith("{\"schemaVersion\":\"1.0\"")
                .doesNotContain("\"error\"");
    }

    /** Deterministic in-memory {@link KernelDiagnostics} for dispatch tests. */
    private static class FakeDiagnostics implements KernelDiagnostics {
        @Override
        public ProvidersSnapshot listProviders() {
            return ProvidersSnapshot.capture(List.of(
                    new ProviderDescriptor("ExerisTest/Telemetry", "telemetry", 0, Optional.empty())));
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

    /**
     * A {@link KernelDiagnostics} whose {@code listProviders} fails the way a real one does.
     *
     * <p>{@link ServiceConfigurationError} is an {@code Error}, and that is the whole point: the
     * real failure mode is a provider whose class initialiser throws, which {@code ServiceLoader}
     * wraps and rethrows as this. A dispatch guard that only caught exceptions would let it end the
     * process, and the consumer would lose every later request on the same child, not just this one.
     */
    private static final class ExplodingDiagnostics extends FakeDiagnostics {
        @Override
        public ProvidersSnapshot listProviders() {
            throw new ServiceConfigurationError(
                    "Provider Broken/Provider could not be instantiated",
                    new NoClassDefFoundError("com/example/Missing"));
        }
    }
}
