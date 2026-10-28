/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.testing.HeldTimeSource;
import eu.exeris.kernel.core.contract.DeveloperWorkloadAdmission;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.contract.ContractKernelProviders;
import eu.exeris.kernel.spi.contract.WorkloadAdmission;
import eu.exeris.kernel.spi.contract.WorkloadEnvelope;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.StreamHandler;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.tck.contract.transport.AbstractTransportWorkloadAdmissionTck;
import org.junit.jupiter.api.AfterAll;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/** Binds {@link AbstractTransportWorkloadAdmissionTck} to the Community native TCP carrier. */
@DisplayName("Community: NativeTcpCarrier workload admission TCK")
class CommunityTransportWorkloadAdmissionTckTest extends AbstractTransportWorkloadAdmissionTck {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    @Override
    protected TransportEngine createEngine(int port, StreamHandler handler) {
        TransportConfig config = new TransportConfig(
                TransportMode.SERVER, "127.0.0.1", port, 1, null, null, 1_024, 30_000);
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .run(() -> holder[0] = new NativeTcpTransportProvider().createEngine(config));
        holder[0].setStreamHandler(handler);
        return holder[0];
    }

    @Test
    @DisplayName("a connection the gate refuses counts into totalRejected and is not reported as a maxConnections refusal")
    void gateRefusalIsNotAMaxConnectionsRefusal(@TempDir Path dir) throws Exception {
        int port = freePort();
        CountDownLatch release = new CountDownLatch(1);
        TransportEngine engine = createEngine(port, stream -> {
            try (stream) {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        WorkloadAdmission gate = new DeveloperWorkloadAdmission(
                "EXR-GATE-REFUSAL", new WorkloadEnvelope(1_000, 1, 0), new HeldTimeSource());
        Path dump = dir.resolve("refusal.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("eu.exeris.kernel.transport.CommunityConnectionRefused");
            recording.start();
            ScopedValue.where(ContractKernelProviders.WORKLOAD_ADMISSION, gate).run(engine::start);
            try (var _ = connect(port); Socket refused = connect(port)) {
                refused.setSoTimeout(10_000);
                assertThat(refused.getInputStream().read()).as("refused connection is closed").isEqualTo(-1);
                assertThat(engine.stats().totalRejected()).as("the gate's refusal counts as rejected").isEqualTo(1);
            } finally {
                release.countDown();
                engine.close();
            }
            recording.stop();
            recording.dump(dump);
        }
        assertThat(RecordingFile.readAllEvents(dump))
                .filteredOn(event -> "eu.exeris.kernel.transport.CommunityConnectionRefused"
                        .equals(event.getEventType().getName()))
                .as("CommunityConnectionRefused describes the maxConnections ceiling only")
                .isEmpty();
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        return socket;
    }

    @Override
    protected int freePort() {
        return CommunityTransportTestHarness.nextFreePort();
    }
}
