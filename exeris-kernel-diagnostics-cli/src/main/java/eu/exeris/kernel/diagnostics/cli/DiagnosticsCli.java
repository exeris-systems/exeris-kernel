/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.diagnostics.cli;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnostics;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnosticsProvider;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Stdio CLI for the {@link KernelDiagnostics} SPI (ADR-033).
 *
 * <h2>Model</h2>
 * <p>A consumer (e.g. {@code exeris-ai-bridge}) {@code spawn()}s this process. It boots the kernel in
 * <b>read-only inspect mode</b> ({@link KernelBootstrap#inspect(Runnable)} — resolves the subsystem
 * topology only: load + selector closure + topological sort, never {@code initialize()} or
 * {@code start()}; no ports, no connections, no infrastructure), resolves the highest-priority
 * {@link KernelDiagnosticsProvider} via {@link ServiceLoader} (Community = 0, Enterprise overlay = 100 on
 * the same binary), and serves newline-delimited JSON: one request object per line on stdin, one response
 * object per line on stdout. No network surface; trusts the spawning process (auth-free local mode).
 *
 * <h2>Protocol (NDJSON)</h2>
 * <pre>
 *   → {"method":"listProviders"}
 *   ← {"schemaVersion":"1.0","capturedAt":"…","providers":[…]}
 *   → {"method":"describeSubsystem","name":"transport"}
 *   ← {"schemaVersion":"1.0",…,"subsystem":{…}}
 *   → {"method":"getJvmErgonomics"}
 *   ← {"schemaVersion":"1.0",…,"gcName":"…","availableProcessors":…,"cpuQuotaMicros":null}
 *   ← {"error":"…"}          (on unknown method / malformed request / missing name)
 * </pre>
 *
 * <p><b>Optional fields serialise as JSON {@code null}, not absent keys</b> (default
 * {@code tools.jackson} databind config): an empty {@code Optional} on a snapshot (e.g.
 * {@code cpuQuotaMicros} on a non-container host) appears as {@code "cpuQuotaMicros":null}.
 * Consumers must treat {@code null} and an absent key alike.
 *
 * @since 0.9.0
 */
public final class DiagnosticsCli {

    /** Protocol goes to stdout; diagnosis goes here, which {@code System.Logger} routes to stderr. */
    private static final Logger LOG = System.getLogger(DiagnosticsCli.class.getName());

    private final KernelDiagnostics diagnostics;
    private final ObjectMapper mapper;

    /* default */ DiagnosticsCli(KernelDiagnostics diagnostics, ObjectMapper mapper) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public static void main(String[] args) throws KernelBootstrap.BootstrapException {
        ObjectMapper mapper = newMapper();
        KernelBootstrap.builder()
                .selector(BootstrapSelector.all())
                .build()
                .inspect(() -> {
                    DiagnosticsCli cli = new DiagnosticsCli(loadDiagnostics(), mapper);
                    try {
                        cli.serve(System.in, System.out);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    /**
     * Jackson mapper for the wire schema, and it needs no configuration to produce it.
     * {@code tools.jackson} (Jackson 3) has {@code java.time} and {@code Optional} support built into
     * databind rather than in separate modules, and {@code DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS}
     * is off by default there, so a bare mapper already writes ISO-8601 instants and an empty
     * {@code Optional} as JSON {@code null} — the two properties this CLI's protocol promises.
     *
     * <p>Bare constructor rather than a {@code JsonMapper.builder()} round-trip, for the reason
     * {@code CommunityJsonMappers} gives: a builder can drift from the bare-constructor defaults,
     * and here those defaults ARE the contract.
     */
    /* default */ static ObjectMapper newMapper() {
        return new ObjectMapper();
    }

    /** Resolves the winning provider (highest {@link KernelDiagnosticsProvider#priority()}). */
    /* default */ static KernelDiagnostics loadDiagnostics() {
        return ServiceLoader.load(KernelDiagnosticsProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(KernelDiagnosticsProvider::priority))
                .orElseThrow(() -> new IllegalStateException(
                        "No KernelDiagnosticsProvider found on the classpath"))
                .create();
    }

    /** Reads NDJSON requests from {@code input} and writes NDJSON responses to {@code output} until EOF. */
    /* default */ void serve(InputStream input, OutputStream output) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while (line != null) {
                if (!line.isBlank()) {
                    writer.write(handle(line));
                    writer.write('\n');
                    writer.flush();
                }
                line = reader.readLine();
            }
        }
    }

    /**
     * Handles one request line and returns the JSON response line. Never throws: malformed input,
     * unknown methods, and a failure inside the diagnostics implementation all come back as
     * {@code {"error":"…"}} on the same line the caller is waiting for.
     *
     * <p>The dispatch catch takes {@link LinkageError} and {@link ServiceConfigurationError}
     * alongside {@link RuntimeException}, and that is deliberate rather than defensive breadth.
     * {@code listProviders} instantiates every provider on the classpath through
     * {@link ServiceLoader}, so a provider whose class initialiser fails arrives here as an
     * {@code Error}, not an exception — and letting it escape ends the process, which costs the
     * caller the whole NDJSON channel rather than one answer. The protocol has no request ids and a
     * consumer caches the child across calls, so a dead process is a dead session. One broken
     * provider must degrade one method.
     */
    // PMD.AvoidCatchingGenericException — the generic catch IS the contract here: this method is the
    // process boundary, and its documented promise is that nothing escapes it. Narrowing the catch
    // to the exception types seen so far would mean the next unforeseen one ends the session, which
    // is exactly the failure this guard exists to prevent. Everything caught is reported, not
    // swallowed: the caller gets an error response and the operator gets the stack on stderr.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    /* default */ String handle(String requestLine) {
        JsonNode request;
        try {
            request = mapper.readTree(requestLine);
        } catch (JacksonException e) {
            return error("malformed request: " + e.getOriginalMessage());
        }
        String method = request.path("method").asString("");
        try {
            return switch (method) {
                case "listProviders" -> mapper.writeValueAsString(diagnostics.listProviders());
                case "getBootstrapDag" -> mapper.writeValueAsString(diagnostics.getBootstrapDag());
                case "getJvmErgonomics" -> mapper.writeValueAsString(diagnostics.getJvmErgonomics());
                case "describeSubsystem" -> describeSubsystem(request);
                case "" -> error("missing 'method'");
                default -> error("unknown method: " + method);
            };
        } catch (RuntimeException | LinkageError | ServiceConfigurationError e) {
            LOG.log(Logger.Level.ERROR, () -> "diagnostics method '" + method + "' failed", e);
            // The throwable's own text goes back over the wire, which is a decision and not an
            // oversight: this process has no network surface and trusts whoever spawned it (see
            // the class javadoc), the caller is the only party that can act on the failure, and a
            // response saying only "it failed" would send an operator to a stderr they may not be
            // capturing. That reasoning is local to a trusted stdio tool. A subsystem-facing error
            // path does NOT inherit it — Throwable.toString() carries wrapped-cause text, which is
            // where connection strings and principal names surface.
            return error("method '" + method + "' failed: " + e);
        }
    }

    private String describeSubsystem(JsonNode request) {
        String name = request.path("name").asString("");
        if (name.isBlank()) {
            return error("describeSubsystem requires a non-blank 'name'");
        }
        return mapper.writeValueAsString(diagnostics.describeSubsystem(name));
    }

    private String error(String message) {
        try {
            return mapper.writeValueAsString(Map.of("error", message));
        } catch (JacksonException _) {
            return "{\"error\":\"serialization failure\"}";
        }
    }
}
