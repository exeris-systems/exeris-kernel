/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.contract.ContractKernelProviders;
import eu.exeris.kernel.spi.contract.WorkloadAdmission;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A connection returns its admission to the gate that admitted it, not to the gate the carrier holds when
 * the connection closes. The carrier takes its gate from the kernel scope on each {@code start()}, so a
 * carrier restarted under another gate must leave the earlier gate's admissions to the earlier gate.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@DisplayName("NativeTcpCarrier — a connection releases against the gate that admitted it")
class NativeTcpCarrierAdmissionGateCaptureTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void releaseAllocator() {
        ALLOCATOR.close();
    }

    /** An enforced gate that admits everything and counts what it was asked, with a latch per release. */
    private static final class CountingGate implements WorkloadAdmission {
        private final AtomicInteger admitted = new AtomicInteger();
        private final AtomicInteger released = new AtomicInteger();
        private final CountDownLatch firstRelease = new CountDownLatch(1);

        @Override
        public boolean enforced() {
            return true;
        }

        @Override
        public boolean tryAdmitConnection() {
            admitted.incrementAndGet();
            return true;
        }

        @Override
        public void connectionClosed() {
            released.incrementAndGet();
            firstRelease.countDown();
        }

        @Override
        public boolean tryAdmitRequest() {
            return true;
        }
    }

    private static NativeTcpCarrier carrier(int port, CountDownLatch handled) {
        TransportConfig config = new TransportConfig(
                TransportMode.SERVER, "127.0.0.1", port, 1, null, null, 1_024, 30_000);
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR)
                .run(() -> holder[0] = new NativeTcpTransportProvider().createEngine(config));
        holder[0].setStreamHandler(stream -> handled.countDown());
        return (NativeTcpCarrier) holder[0];
    }

    @Test
    @DisplayName("a connection admitted before a restart under another gate is released to the gate that admitted it")
    void restartUnderAnotherGateKeepsTheReleaseWithTheAdmittingGate() throws Exception {
        int port = CommunityTransportTestHarness.nextFreePort();
        CountDownLatch admittedAndHeld = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountingGate first = new CountingGate();
        CountingGate second = new CountingGate();
        CountDownLatch handled = new CountDownLatch(1);
        NativeTcpCarrier carrier = carrier(port, handled);
        AtomicBoolean held = new AtomicBoolean();
        carrier.beforeAcceptedRegistration(() -> {
            if (held.compareAndSet(false, true)) {
                admittedAndHeld.countDown();
                awaitUninterrupted(resume);
            }
        });
        try {
            ScopedValue.where(ContractKernelProviders.WORKLOAD_ADMISSION, first).run(carrier::start);
            try (Socket peer = open(port)) {
                assertThat(admittedAndHeld.await(10, TimeUnit.SECONDS))
                        .as("the first gate admitted the connection, which is held before its registration").isTrue();
                assertThat(first.admitted).hasValue(1);

                // stop() waits out its acceptor join, and the carrier then starts under the second gate while
                // the held accept still carries the first gate's admission.
                carrier.stop();
                ScopedValue.where(ContractKernelProviders.WORKLOAD_ADMISSION, second).run(carrier::start);
                resume.countDown();
                assertThat(handled.await(10, TimeUnit.SECONDS))
                        .as("the held accept registers and is handed to its handler once it resumes").isTrue();
            }
        } finally {
            resume.countDown();
            carrier.close();
        }

        assertThat(first.firstRelease.await(10, TimeUnit.SECONDS))
                .as("the connection's release reaches the gate that admitted it").isTrue();
        assertThat(first.released).hasValue(1);
        assertThat(second.admitted).as("the second gate admitted nothing").hasValue(0);
        assertThat(second.released).as("so it received no release").hasValue(0);
    }

    private static void awaitUninterrupted(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static Socket open(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        return socket;
    }
}
