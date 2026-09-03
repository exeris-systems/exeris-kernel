/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.diagnostics;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.diagnostics.BootstrapDagSnapshot;
import eu.exeris.kernel.spi.diagnostics.DagNode;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnostics;
import eu.exeris.kernel.spi.diagnostics.ProviderDescriptor;
import eu.exeris.kernel.spi.diagnostics.ProvidersSnapshot;
import eu.exeris.kernel.spi.diagnostics.RuntimeErgonomicsSnapshot;
import eu.exeris.kernel.spi.diagnostics.SubsystemDescriptor;
import eu.exeris.kernel.spi.diagnostics.SubsystemSnapshot;
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
 * <p>Subclasses supply a provider's {@link KernelDiagnostics} via {@link #diagnostics()}. The TCK binds a
 * known subsystem inventory through the public {@link KernelProviders#SUBSYSTEMS} {@link java.lang.ScopedValue}
 * slot and asserts the subsystem-derived surface ({@link KernelDiagnostics#getBootstrapDag()},
 * {@link KernelDiagnostics#describeSubsystem(String)}) against
 * it (records shape, {@code schemaVersion}, {@code Optional} semantics, immutability, degraded behaviour
 * when unbound). {@link KernelDiagnostics#listProviders()} is provider discovery (implementation-defined,
 * not slot-bound), so it is asserted only for well-formedness; {@link KernelDiagnostics#getJvmErgonomics()}
 * is environment-derived (also not slot-bound) and is asserted for well-formedness, the
 * {@code Optional.empty()} degradation contract, and {@code default}-method binary compatibility. A pinned
 * JSON wire-schema fixture guards the append-only contract (ADR-033 Obligation 5).
 *
 * <p><b>Snapshot non-atomicity</b> (ADR-033 Obligation 7) is acknowledged, not tested: each call stamps its
 * own {@code capturedAt} and a multi-call view may straddle a transition by design.
 *
 * @since 0.9.0
 */
public abstract class AbstractKernelDiagnosticsTck {

    private static final String SCHEMA_FIXTURE = "/diagnostics/kernel-diagnostics-schema-1.0.json";

    /** Ordered set of wire-contract record types, used to regenerate and check the schema fixture. */
    private static final List<Class<?>> WIRE_RECORDS = List.of(
            ProvidersSnapshot.class, ProviderDescriptor.class,
            BootstrapDagSnapshot.class, DagNode.class,
            SubsystemSnapshot.class, SubsystemDescriptor.class,
            RuntimeErgonomicsSnapshot.class);

    private static final Subsystem MEMORY =
            new FakeSubsystem("memory", BootstrapPhase.FOUNDATION, List.of(), true, false);
    private static final Subsystem TRANSPORT =
            new FakeSubsystem("transport", BootstrapPhase.SERVICES, List.of("memory"), true, true);
    private static final List<Subsystem> SUBSYSTEMS = List.of(MEMORY, TRANSPORT);

    /**
     * @return the {@link KernelDiagnostics} under test (stateless; reads ScopedValue slots per call)
     */
    protected abstract KernelDiagnostics diagnostics();

    /** Runs {@code body} inside a scope binding the known subsystem inventory. */
    private void inBoundScope(Runnable body) {
        ScopedValue.where(KernelProviders.SUBSYSTEMS, SUBSYSTEMS).run(body);
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
                assertThat(d.getBootstrapDag().schemaVersion()).isEqualTo(KernelDiagnostics.SCHEMA_VERSION);
                assertThat(d.describeSubsystem("memory").schemaVersion()).isEqualTo(KernelDiagnostics.SCHEMA_VERSION);
                assertThat(d.getJvmErgonomics().schemaVersion()).isEqualTo(KernelDiagnostics.SCHEMA_VERSION);

                assertThat(d.listProviders().capturedAt()).isNotNull();
                assertThat(d.getBootstrapDag().capturedAt()).isNotNull();
                assertThat(d.describeSubsystem("memory").capturedAt()).isNotNull();
                assertThat(d.getJvmErgonomics().capturedAt()).isNotNull();
            });
        }
    }

    @Nested
    @DisplayName("Bound subsystem inventory drives the snapshots")
    class BoundScope {

        @Test
        @DisplayName("getBootstrapDag exposes nodes with phase, dependencies and running flag")
        void bootstrapDag() {
            inBoundScope(() -> {
                BootstrapDagSnapshot dag = diagnostics().getBootstrapDag();
                assertThat(dag.nodes()).extracting(DagNode::name)
                        .containsExactlyInAnyOrder("memory", "transport");
                DagNode transport = dag.nodes().stream()
                        .filter(n -> "transport".equals(n.name())).findFirst().orElseThrow();
                assertThat(transport.phase()).isEqualTo("SERVICES");
                assertThat(transport.dependsOn()).containsExactly("memory");
                assertThat(transport.running()).isTrue();
                assertThat(transport.optional()).isTrue();
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
    }

    @Nested
    @DisplayName("Provider discovery")
    class Providers {

        @Test
        @DisplayName("listProviders returns well-formed descriptors via discovery")
        void wellFormed() {
            ProvidersSnapshot snap = diagnostics().listProviders();
            assertThat(snap.schemaVersion()).isEqualTo(KernelDiagnostics.SCHEMA_VERSION);
            assertThat(snap.providers()).allSatisfy(p -> {
                assertThat(p.providerName()).isNotBlank();
                assertThat(p.spiType()).isNotBlank();
                assertThat(p.priority()).isGreaterThanOrEqualTo(0);
                assertThat(p.displayName()).isNotNull();
            });
        }
    }

    @Nested
    @DisplayName("Degraded behaviour outside a kernel scope")
    class Unbound {

        @Test
        @DisplayName("subsystem-derived snapshots are empty when the inventory slot is unbound")
        void emptyWhenUnbound() {
            KernelDiagnostics d = diagnostics();
            assertThat(d.getBootstrapDag().nodes()).isEmpty();
            assertThat(d.describeSubsystem("memory").subsystem()).isEmpty();
            // listProviders is discovery-based, not slot-bound: still non-null regardless of scope.
            assertThat(d.listProviders().providers()).isNotNull();
            assertThat(d.getBootstrapDag().schemaVersion()).isEqualTo(KernelDiagnostics.SCHEMA_VERSION);
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
            });
        }
    }

    @Nested
    @DisplayName("JVM runtime ergonomics snapshot")
    class JvmErgonomics {

        @Test
        @DisplayName("getJvmErgonomics returns a well-formed snapshot; absent data is Optional.empty(), never null")
        void wellFormed() {
            RuntimeErgonomicsSnapshot ergo = diagnostics().getJvmErgonomics();
            assertThat(ergo.gcName()).isNotBlank();
            assertThat(ergo.availableProcessors()).isPositive();
            // Every environment-sensitive field is an Optional and is never null (empty when undeterminable).
            assertThat(ergo.cpuQuotaMicros()).isNotNull();
            assertThat(ergo.cpuPeriodMicros()).isNotNull();
            assertThat(ergo.memoryMaxBytes()).isNotNull();
            assertThat(ergo.cpusetEffective()).isNotNull();
            assertThat(ergo.largePagesEnabled()).isNotNull();
            assertThat(ergo.transparentHugePages()).isNotNull();
            assertThat(ergo.classDataSharingActive()).isNotNull();
            assertThat(ergo.aotCacheActive()).isNotNull();
        }

        @Test
        @DisplayName("a provider that does not override getJvmErgonomics() stays binary-compatible (default method)")
        void defaultMethodCompat() {
            // A KernelDiagnostics that implements only the three abstract methods and inherits the default
            // getJvmErgonomics() must still satisfy the contract: non-null, fully-degraded snapshot.
            KernelDiagnostics legacyThreeMethod = new KernelDiagnostics() {
                @Override
                public ProvidersSnapshot listProviders() {
                    return ProvidersSnapshot.capture(List.of());
                }

                @Override
                public BootstrapDagSnapshot getBootstrapDag() {
                    return BootstrapDagSnapshot.capture(List.of());
                }

                @Override
                public SubsystemSnapshot describeSubsystem(String name) {
                    return SubsystemSnapshot.capture(name, java.util.Optional.empty());
                }
            };
            RuntimeErgonomicsSnapshot ergo = legacyThreeMethod.getJvmErgonomics();
            assertThat(ergo).isNotNull();
            assertThat(ergo.schemaVersion()).isEqualTo(KernelDiagnostics.SCHEMA_VERSION);
            assertThat(ergo.capturedAt()).isNotNull();
            assertThat(ergo.gcName()).isNotBlank();
            assertThat(ergo.availableProcessors()).isPositive();
            assertThat(ergo.cpuQuotaMicros()).isEmpty();
            assertThat(ergo.memoryMaxBytes()).isEmpty();
            assertThat(ergo.cpusetEffective()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Pinned JSON wire-schema fixture (append-only contract)")
    class SchemaFixture {

        @Test
        @DisplayName("live record components match the pinned schema fixture, schemaVersion first")
        void fixtureMatchesRecords() {
            // schemaVersion MUST be the first field of every top-level snapshot (Obligation 5).
            for (Class<?> top : List.of(ProvidersSnapshot.class,
                    BootstrapDagSnapshot.class, SubsystemSnapshot.class, RuntimeErgonomicsSnapshot.class)) {
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
}
