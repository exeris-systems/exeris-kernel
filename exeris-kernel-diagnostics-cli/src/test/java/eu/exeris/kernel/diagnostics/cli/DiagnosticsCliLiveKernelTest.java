/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.diagnostics.cli;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serves the whole protocol against a <b>live kernel</b>, in-process, on the module's real
 * classpath: {@link KernelBootstrap#inspect(Runnable)} exactly as {@code main()} calls it, the real
 * {@link DiagnosticsCli#loadDiagnostics() loadDiagnostics()} resolved through {@code ServiceLoader},
 * and the real Community providers behind it.
 *
 * <p>{@link DiagnosticsCliTest} covers dispatch against a fake and is the right shape for that. What
 * it structurally cannot see is everything between the CLI and a provider: it never boots a kernel,
 * so no provider class initialiser ever runs, so no dependency the providers need is ever loaded.
 * A module whose only test is that one can resolve a Jackson that its own kernel cannot use and
 * stay green — which is how {@code listProviders} came to kill the process in three consecutive
 * published versions while the suite passed.
 *
 * <p>This runs in the default build. It boots no infrastructure (inspect mode resolves the subsystem
 * topology and stops) and needs no container, no port, and no OpenSSL — provider instantiation does
 * not load libssl, which was verified rather than assumed.
 */
@DisplayName("DiagnosticsCli — live kernel, in-process")
class DiagnosticsCliLiveKernelTest {

    private static List<String> responses;

    @BeforeAll
    static void serveOneSession() throws KernelBootstrap.BootstrapException {
        ByteArrayInputStream in = new ByteArrayInputStream(
                String.join("\n", DiagnosticsProtocolContract.REQUESTS).getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        KernelBootstrap.builder()
                .selector(BootstrapSelector.all())
                .build()
                .inspect(() -> {
                    DiagnosticsCli cli = new DiagnosticsCli(
                            DiagnosticsCli.loadDiagnostics(), DiagnosticsCli.newMapper());
                    try {
                        cli.serve(in, out);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        responses = out.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    @DisplayName("one session answers every method — a broken one may not take the channel with it")
    void answersEveryRequest() {
        DiagnosticsProtocolContract.assertEveryRequestAnswered(responses, "(in-process; see the test log)");
    }

    @Test
    @DisplayName("listProviders instantiates all nine providers, class initialisers and all")
    void providerInventoryIsComplete() {
        DiagnosticsProtocolContract.assertProviderInventoryComplete(responses);
    }

    @Test
    @DisplayName("capturedAt is ISO-8601 with no module registered — the tools.jackson default")
    void instantsAreIso8601() {
        DiagnosticsProtocolContract.assertInstantsAreIso8601(responses);
    }

    @Test
    @DisplayName("an empty Optional is JSON null, not an absent key")
    void emptyOptionalIsNull() {
        DiagnosticsProtocolContract.assertEmptyOptionalIsNull(responses);
    }

    @Test
    @DisplayName("getBootstrapDag returns the resolved topology")
    void bootstrapDagIsPopulated() {
        DiagnosticsProtocolContract.assertBootstrapDagPopulated(responses);
    }

    @Test
    @DisplayName("getJvmErgonomics reports this JVM")
    void ergonomicsDescribeThisJvm() {
        DiagnosticsProtocolContract.assertErgonomicsDescribeThisJvm(responses);
    }

    @Test
    @DisplayName("describeSubsystem resolves a known name and nulls an unknown one")
    void describeSubsystemBothWays() {
        DiagnosticsProtocolContract.assertDescribeSubsystemBothWays(responses);
    }
}
