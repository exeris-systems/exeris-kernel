/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.diagnostics;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.diagnostics.BootstrapDagSnapshot;
import eu.exeris.kernel.spi.diagnostics.CapabilityDescriptor;
import eu.exeris.kernel.spi.diagnostics.CompositionSnapshot;
import eu.exeris.kernel.spi.diagnostics.DagNode;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnostics;
import eu.exeris.kernel.spi.diagnostics.ProviderDescriptor;
import eu.exeris.kernel.spi.diagnostics.ProvidersSnapshot;
import eu.exeris.kernel.spi.diagnostics.SubsystemDescriptor;
import eu.exeris.kernel.spi.diagnostics.SubsystemSnapshot;
import eu.exeris.kernel.spi.telemetry.TelemetryConfig;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK for the {@link KernelDiagnostics} SPI (ADR-033).
 *
 * <p>Subclasses supply a provider's {@link KernelDiagnostics} via {@link #diagnostics()}. The TCK binds
 * a known kernel state through the public {@code KernelProviders} {@link java.lang.ScopedValue} slots and
 * asserts the four-method surface against it (records shape, {@code schemaVersion}, {@code Optional}
 * semantics, immutability, and degraded behaviour outside a scope), plus a pinned JSON wire-schema
 * fixture that guards the append-only contract (ADR-033 Obligation 5).
 *
 * <p><b>Snapshot non-atomicity</b> (ADR-033 Obligation 7) is acknowledged, not tested: each call stamps
 * its own {@code capturedAt} and a multi-call view may straddle a transition by design.
 *
 * @since 0.9.0
 */
public abstract class AbstractKernelDiagnosticsTck {

    private static final String SCHEMA_FIXTURE = "/diagnostics/kernel-diagnostics-schema-1.0.json";

    /** Ordered set of wire-contract record types, used to regenerate and check the schema fixture. */
    private static final List<Class<?>> WIRE_RECORDS = List.of(
            ProvidersSnapshot.class, ProviderDescriptor.class,
            CompositionSnapshot.class, CapabilityDescriptor.class,
            BootstrapDagSnapshot.class, DagNode.class,
            SubsystemSnapshot.class, SubsystemDescriptor.class);

    private static final Subsystem MEMORY =
            new FakeSubsystem("memory", BootstrapPhase.FOUNDATION, List.of(), true, false);
    private static final Subsystem TRANSPORT =
            new FakeSubsystem("transport", BootstrapPhase.SERVICES, List.of("memory"), true, true);
    private static final List<Subsystem> SUBSYSTEMS = List.of(MEMORY, TRANSPORT);
    private static final TelemetryProvider FAKE_TELEMETRY = new FakeTelemetryProvider();

    /**
     * @return the {@link KernelDiagnostics} under test (stateless; reads ScopedValue slots per call)
     */
    protected abstract KernelDiagnostics diagnostics();

    /** Runs {@code body} inside a kernel scope binding the known subsystems + one telemetry provider. */
    private void inBoundScope(Runnable body) {
        ScopedValue.where(KernelProviders.SUBSYSTEMS, SUBSYSTEMS)
                .where(KernelProviders.TELEMETRY_PROVIDER, FAKE_TELEMETRY)
                .run(body);
    }

    @Nested
    @DisplayName("Schema invariants (every snapshot)")
    class SchemaInvariants {

        @Test
        @DisplayName("schemaVersion == 1.0 and capturedAt non-null on all four methods")
        void schemaVersionAndTimestamp() {
            inBoundScope(() -> {
                KernelDiagnostics d = diagnostics();
                assertThat(d.listProviders().schemaVersion()).isEqualTo(KernelDiagnostics.SCHEMA_VERSION);
                assertThat(d.listCapabilities().schemaVersion()).isEqualTo(KernelDiagnostics.SCHEMA_VERSION);
                assertThat(d.getBootstrapDag().schemaVersion()).isEqualTo(KernelDiagnostics.SCHEMA_VERSION);
                assertThat(d.describeSubsystem("memory").schemaVersion()).isEqualTo(KernelDiagnostics.SCHEMA_VERSION);

                assertThat(d.listProviders().capturedAt()).isNotNull();
                assertThat(d.listCapabilities().capturedAt()).isNotNull();
                assertThat(d.getBootstrapDag().capturedAt()).isNotNull();
                assertThat(d.describeSubsystem("memory").capturedAt()).isNotNull();
            });
        }
    }

    @Nested
    @DisplayName("Bound kernel scope reflects the running state")
    class BoundScope {

        @Test
        @DisplayName("getBootstrapDag exposes nodes with phase, dependencies and running flag")
        void bootstrapDag() {
            inBoundScope(() -> {
                BootstrapDagSnapshot dag = diagnostics().getBootstrapDag();
                assertThat(dag.nodes()).extracting(DagNode::name)
                        .containsExactlyInAnyOrder("memory", "transport");
                DagNode transport = dag.nodes().stream()
                        .filter(n -> n.name().equals("transport")).findFirst().orElseThrow();
                assertThat(transport.phase()).isEqualTo("SERVICES");
                assertThat(transport.dependsOn()).containsExactly("memory");
                assertThat(transport.running()).isTrue();
                assertThat(transport.optional()).isTrue();
            });
        }

        @Test
        @DisplayName("listCapabilities reflects each subsystem's provides/requires")
        void capabilities() {
            inBoundScope(() -> {
                CompositionSnapshot comp = diagnostics().listCapabilities();
                assertThat(comp.capabilities()).extracting(CapabilityDescriptor::name)
                        .containsExactlyInAnyOrder("memory", "transport");
                CapabilityDescriptor transport = comp.capabilities().stream()
                        .filter(c -> c.name().equals("transport")).findFirst().orElseThrow();
                assertThat(transport.provides()).containsExactly("transport");
                assertThat(transport.requires()).containsExactly("memory");
            });
        }

        @Test
        @DisplayName("describeSubsystem returns detail for a known name, empty for an unknown one")
        void describeSubsystem() {
            inBoundScope(() -> {
                SubsystemSnapshot known = diagnostics().describeSubsystem("transport");
                assertThat(known.requestedName()).isEqualTo("transport");
                assertThat(known.subsystem()).isPresent();
                SubsystemDescriptor detail = known.subsystem().orElseThrow();
                assertThat(detail.name()).isEqualTo("transport");
                assertThat(detail.dependsOn()).containsExactly("memory");
                assertThat(detail.phase()).isEqualTo("SERVICES");

                SubsystemSnapshot missing = diagnostics().describeSubsystem("does-not-exist");
                assertThat(missing.requestedName()).isEqualTo("does-not-exist");
                assertThat(missing.subsystem()).isEmpty();
            });
        }

        @Test
        @DisplayName("listProviders includes each bound provider with name, type and priority")
        void providers() {
            inBoundScope(() -> {
                ProvidersSnapshot snap = diagnostics().listProviders();
                ProviderDescriptor telemetry = snap.providers().stream()
                        .filter(p -> p.spiType().equals("telemetry")).findFirst().orElseThrow();
                assertThat(telemetry.providerName()).isEqualTo("ExerisTest/Telemetry");
                assertThat(telemetry.priority()).isEqualTo(7);
            });
        }
    }

    @Nested
    @DisplayName("Degraded behaviour outside a kernel scope")
    class Unbound {

        @Test
        @DisplayName("all methods return non-null, empty snapshots when slots are unbound")
        void emptyWhenUnbound() {
            KernelDiagnostics d = diagnostics();
            assertThat(d.listProviders().providers()).isEmpty();
            assertThat(d.listCapabilities().capabilities()).isEmpty();
            assertThat(d.getBootstrapDag().nodes()).isEmpty();
            assertThat(d.describeSubsystem("memory").subsystem()).isEmpty();
            assertThat(d.listProviders().schemaVersion()).isEqualTo(KernelDiagnostics.SCHEMA_VERSION);
        }
    }

    @Nested
    @DisplayName("Snapshot lists are immutable")
    class Immutability {

        @Test
        @DisplayName("returned collections reject mutation")
        void immutableLists() {
            inBoundScope(() -> {
                List<DagNode> nodes = diagnostics().getBootstrapDag().nodes();
                assertThatThrownBy(() -> nodes.add(null))
                        .isInstanceOf(UnsupportedOperationException.class);
                List<CapabilityDescriptor> caps = diagnostics().listCapabilities().capabilities();
                assertThatThrownBy(() -> caps.add(null))
                        .isInstanceOf(UnsupportedOperationException.class);
            });
        }
    }

    @Nested
    @DisplayName("Pinned JSON wire-schema fixture (append-only contract)")
    class SchemaFixture {

        @Test
        @DisplayName("live record components match the pinned schema fixture, schemaVersion first")
        void fixtureMatchesRecords() {
            // schemaVersion MUST be the first field of every top-level snapshot (Obligation 5).
            for (Class<?> top : List.of(ProvidersSnapshot.class, CompositionSnapshot.class,
                    BootstrapDagSnapshot.class, SubsystemSnapshot.class)) {
                assertThat(top.getRecordComponents()[0].getName())
                        .as("first field of %s", top.getSimpleName())
                        .isEqualTo("schemaVersion");
            }
            assertThat(normalize(generateSchema()))
                    .as("KernelDiagnostics wire schema drifted — if intentional, bump schemaVersion "
                            + "(append-only) and update %s", SCHEMA_FIXTURE)
                    .isEqualTo(normalize(readFixture()));
        }
    }

    private static String generateSchema() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        sb.append("  \"schemaVersion\": \"").append(KernelDiagnostics.SCHEMA_VERSION).append("\",\n");
        sb.append("  \"records\": {\n");
        for (int i = 0; i < WIRE_RECORDS.size(); i++) {
            Class<?> record = WIRE_RECORDS.get(i);
            String fields = Arrays.stream(record.getRecordComponents())
                    .map(RecordComponent::getName)
                    .map(name -> '"' + name + '"')
                    .collect(Collectors.joining(", "));
            sb.append("    \"").append(record.getSimpleName()).append("\": [").append(fields).append(']');
            sb.append(i == WIRE_RECORDS.size() - 1 ? "\n" : ",\n");
        }
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String readFixture() {
        try (InputStream in = AbstractKernelDiagnosticsTck.class.getResourceAsStream(SCHEMA_FIXTURE)) {
            if (in == null) {
                throw new IllegalStateException("Missing schema fixture: " + SCHEMA_FIXTURE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalize(String s) {
        return s.replace("\r\n", "\n").strip();
    }

    /** Minimal read-only {@link Subsystem} used to bind a known inventory into the kernel scope. */
    private record FakeSubsystem(String name, BootstrapPhase phase, List<String> dependsOn,
                                 boolean running, boolean optional) implements Subsystem {
        @Override
        public void initialize() {
            // no-op test fixture
        }

        @Override
        public void start() {
            // no-op test fixture
        }

        @Override
        public void stop() {
            // no-op test fixture
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public boolean isOptional() {
            return optional;
        }
    }

    /** Minimal {@link TelemetryProvider}; only name/priority are read by diagnostics. */
    private static final class FakeTelemetryProvider implements TelemetryProvider {
        @Override
        public List<TelemetrySink> createSinks(TelemetryConfig config) {
            return List.of();
        }

        @Override
        public String providerName() {
            return "ExerisTest/Telemetry";
        }

        @Override
        public int priority() {
            return 7;
        }
    }
}
