/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.context;

import eu.exeris.kernel.spi.contract.ContractKernelProviders;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphKernelProviders;
import eu.exeris.kernel.spi.graph.GraphProvider;
import eu.exeris.kernel.spi.time.TimeKernelProviders;
import eu.exeris.kernel.spi.time.TimeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deprecated {@link KernelProviders} graph and time members are the owning holders' slots, not
 * second slots (ADR-100 §3): a binding made through either name is visible through both.
 *
 * <p>A bridge that declared its own {@code ScopedValue} would pass every test that binds and reads
 * through one name, so each case binds through one name and reads through the other, in both
 * directions.
 */
@DisplayName("KernelProviders bridges to the preview-slot holders")
@SuppressWarnings("removal")
class KernelProvidersBridgeTest {

    @Test
    @DisplayName("the bridge fields are the holders' ScopedValue instances")
    void bridgeFieldsAreTheSameInstances() {
        assertThat(KernelProviders.GRAPH_PROVIDER).isSameAs(GraphKernelProviders.GRAPH_PROVIDER);
        assertThat(KernelProviders.GRAPH_ENGINE).isSameAs(GraphKernelProviders.GRAPH_ENGINE);
        assertThat(KernelProviders.TIME_SOURCE).isSameAs(TimeKernelProviders.TIME_SOURCE);
    }

    @Test
    @DisplayName("GRAPH_ENGINE: bound through the bridge is read through the holder, and back")
    void graphEngineSharedBothWays() {
        GraphEngine engine = stub(GraphEngine.class);
        ScopedValue.where(KernelProviders.GRAPH_ENGINE, engine).run(() -> {
            assertThat(GraphKernelProviders.GRAPH_ENGINE.get()).isSameAs(engine);
            assertThat(GraphKernelProviders.graphEngine()).isSameAs(engine);
            assertThat(KernelProviders.graphEngine()).isSameAs(engine);
        });
        ScopedValue.where(GraphKernelProviders.GRAPH_ENGINE, engine).run(() -> {
            assertThat(KernelProviders.GRAPH_ENGINE.get()).isSameAs(engine);
            assertThat(KernelProviders.graphEngine()).isSameAs(engine);
        });
    }

    @Test
    @DisplayName("GRAPH_PROVIDER: bound through the bridge is read through the holder, and back")
    void graphProviderSharedBothWays() {
        GraphProvider provider = stub(GraphProvider.class);
        ScopedValue.where(KernelProviders.GRAPH_PROVIDER, provider).run(
                () -> assertThat(GraphKernelProviders.GRAPH_PROVIDER.get()).isSameAs(provider));
        ScopedValue.where(GraphKernelProviders.GRAPH_PROVIDER, provider).run(
                () -> assertThat(KernelProviders.GRAPH_PROVIDER.get()).isSameAs(provider));
    }

    @Test
    @DisplayName("graphEngine() unbound throws NoSuchElementException through both names")
    void graphEngineUnbound() {
        assertThatThrownBy(KernelProviders::graphEngine).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(GraphKernelProviders::graphEngine).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("TIME_SOURCE: bound through the bridge is read through the holder, and back")
    void timeSourceSharedBothWays() {
        TimeSource source = fixedSource();
        ScopedValue.where(KernelProviders.TIME_SOURCE, source).run(() -> {
            assertThat(TimeKernelProviders.TIME_SOURCE.get()).isSameAs(source);
            assertThat(TimeKernelProviders.timeSource()).isSameAs(source);
            assertThat(KernelProviders.timeSource()).isSameAs(source);
        });
        ScopedValue.where(TimeKernelProviders.TIME_SOURCE, source).run(() -> {
            assertThat(KernelProviders.TIME_SOURCE.get()).isSameAs(source);
            assertThat(KernelProviders.timeSource()).isSameAs(source);
        });
    }

    @Test
    @DisplayName("timeSource() unbound is the platform clock through both names")
    void timeSourceUnbound() {
        assertThat(KernelProviders.timeSource()).isSameAs(TimeSource.SYSTEM);
        assertThat(TimeKernelProviders.timeSource()).isSameAs(TimeSource.SYSTEM);
    }

    @Test
    @DisplayName("executionContract() is held by the holder alone and unbound outside a kernel scope")
    void executionContractUnbound() {
        assertThat(ContractKernelProviders.EXECUTION_CONTRACT.isBound()).isFalse();
        assertThatThrownBy(ContractKernelProviders::executionContract)
                .isInstanceOf(NoSuchElementException.class);
    }

    private static TimeSource fixedSource() {
        return new TimeSource() {
            @Override
            public long nanoTime() {
                return 42L;
            }

            @Override
            public Instant wallTime() {
                return Instant.EPOCH;
            }
        };
    }

    private static <T> T stub(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                }));
    }
}
