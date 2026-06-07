/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.diagnostics.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnostics;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnosticsProvider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Stdio CLI for the {@link KernelDiagnostics} SPI (ADR-033).
 *
 * <h2>Model</h2>
 * <p>A consumer (e.g. {@code exeris-ai-bridge}) {@code spawn()}s this process. It boots the kernel in
 * <b>read-only inspect mode</b> ({@link KernelBootstrap#inspect(Runnable)} — initialize +
 * {@code buildKernelScope}, never {@code start()}; no ports, no connections), resolves the highest-priority
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
 *   ← {"error":"…"}          (on unknown method / malformed request / missing name)
 * </pre>
 *
 * @since 0.9.0
 */
public final class DiagnosticsCli {

    private final KernelDiagnostics diagnostics;
    private final ObjectMapper mapper;

    DiagnosticsCli(KernelDiagnostics diagnostics, ObjectMapper mapper) {
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

    /** Jackson mapper configured for the wire schema: ISO-8601 instants, {@code Optional} support. */
    static ObjectMapper newMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new Jdk8Module())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Resolves the winning provider (highest {@link KernelDiagnosticsProvider#priority()}). */
    static KernelDiagnostics loadDiagnostics() {
        return ServiceLoader.load(KernelDiagnosticsProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(KernelDiagnosticsProvider::priority))
                .orElseThrow(() -> new IllegalStateException(
                        "No KernelDiagnosticsProvider found on the classpath"))
                .create();
    }

    /** Reads NDJSON requests from {@code in} and writes NDJSON responses to {@code out} until EOF. */
    void serve(InputStream in, OutputStream out) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                writer.write(handle(line));
                writer.write('\n');
                writer.flush();
            }
        }
    }

    /**
     * Handles one request line and returns the JSON response line. Never throws — malformed input,
     * unknown methods, and serialization failures are returned as {@code {"error":"…"}}.
     */
    String handle(String requestLine) {
        try {
            JsonNode request = mapper.readTree(requestLine);
            String method = request.path("method").asText("");
            return switch (method) {
                case "listProviders" -> mapper.writeValueAsString(diagnostics.listProviders());
                case "listCapabilities" -> mapper.writeValueAsString(diagnostics.listCapabilities());
                case "getBootstrapDag" -> mapper.writeValueAsString(diagnostics.getBootstrapDag());
                case "describeSubsystem" -> describeSubsystem(request);
                case "" -> error("missing 'method'");
                default -> error("unknown method: " + method);
            };
        } catch (JsonProcessingException e) {
            return error("malformed request: " + e.getOriginalMessage());
        }
    }

    private String describeSubsystem(JsonNode request) throws JsonProcessingException {
        String name = request.path("name").asText("");
        if (name.isBlank()) {
            return error("describeSubsystem requires a non-blank 'name'");
        }
        return mapper.writeValueAsString(diagnostics.describeSubsystem(name));
    }

    private String error(String message) {
        try {
            return mapper.writeValueAsString(Map.of("error", message));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization failure\"}";
        }
    }
}
