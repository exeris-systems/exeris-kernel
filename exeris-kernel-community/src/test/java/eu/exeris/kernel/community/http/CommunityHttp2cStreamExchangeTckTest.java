/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.core.http.sse.StreamAdmissionController;
import eu.exeris.kernel.core.memory.ResourceArbiter;
import eu.exeris.kernel.core.http.sse.StreamEgress;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.http.StreamMatch;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.tck.contract.http.AbstractHttpStreamExchangeTck;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import java.util.List;

/**
 * Community binding of {@link AbstractHttpStreamExchangeTck} over HTTP/2 with cleartext prior knowledge:
 * each scenario is a real NIO server whose request loop serves the stream as a stream route, and an
 * HTTP/2 client that reads it. A client that withholds flow-control credit while it keeps reading makes
 * the emitter park on the stream window, which is the backpressure the HTTP/2 path has in place of a
 * full socket buffer. Runs on demand with the {@code stream-loopback} group; the untagged stream-route
 * integration test is the merge gate.
 */
@Tag("stream-loopback")
@DisplayName("Community: HttpStreamExchange TCK over HTTP/2 prior knowledge")
class CommunityHttp2cStreamExchangeTckTest extends AbstractHttpStreamExchangeTck {

    private static final long AUTH_EXPIRY_DELAY_MILLIS = 300L;

    static {
        // The same two properties, with the same values, as the HTTP/1.1 binding, so that the order in
        // which the two bindings initialise the dispatcher in one JVM does not matter.
        System.setProperty("exeris.http.stream.creditWindowBytes", Integer.toString(16 * 1024));
        System.setProperty("exeris.transport.acceptedSendBufferBytes", Integer.toString(8 * 1024));
    }

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    @Override
    protected StreamScenario openStream(HttpStreamHandler handler) {
        return new LoopbackScenario(Http2StreamLoopback.start(handler, 0L));
    }

    @Override
    protected StreamScenario openExpiringStream(HttpStreamHandler handler) {
        return new LoopbackScenario(Http2StreamLoopback.start(
                handler, System.currentTimeMillis() + AUTH_EXPIRY_DELAY_MILLIS));
    }

    @Override
    protected boolean supportsBackpressureProbe() {
        return true;
    }

    @Override
    protected boolean supportsAuthExpiryProbe() {
        return true;
    }

    @Override
    protected boolean supportsShedProbe() {
        return true;
    }

    @Override
    protected ExerisKernelException openUnderShed(HttpStreamHandler handler) {
        // Shed is decided at admission, before the head is written, so the probe needs no connection: a
        // forced-shed decision rejects the open with EX-NET-4006 over an egress with a stub transport.
        StreamAdmissionController shed =
                new StreamAdmissionController(() -> ResourceArbiter.Action.SHED_LOAD, "tck-stream");
        CommunityHttpStreamDispatcher dispatcher =
                new CommunityHttpStreamDispatcher(new LeakTrackingAllocator(ALLOCATOR), shed);
        HttpRequest request = new HttpRequest(HttpMethod.GET, "/stream", HttpVersion.HTTP_2, List.of(), null);
        try {
            dispatcher.dispatchHttp2Stream(request, new StubEgress(), StreamMatch.exact(handler));
            return null;
        } catch (ExerisKernelException rejection) {
            return rejection;
        }
    }

    /** Adapts the loopback to the TCK's scenario interface, which only a subclass can name. */
    private static final class LoopbackScenario implements StreamScenario {
        private final Http2StreamLoopback loopback;

        private LoopbackScenario(Http2StreamLoopback loopback) {
            this.loopback = loopback;
        }

        @Override
        public List<StreamEvent> awaitEvents(int count) {
            return loopback.awaitEvents(count);
        }

        @Override
        public boolean awaitEndOfStream() {
            return loopback.awaitEndOfStream();
        }

        @Override
        public void disconnectClient() {
            loopback.disconnectClient();
        }

        @Override
        public Throwable awaitHandlerUnwind() {
            return loopback.awaitHandlerUnwind();
        }

        @Override
        public Throwable handlerError() {
            return loopback.handlerError();
        }

        @Override
        public long outstandingLoans() {
            return loopback.outstandingLoans();
        }

        @Override
        public void stallClient() {
            loopback.stallClient();
        }

        @Override
        public void drainClient() {
            loopback.drainClient();
        }

        @Override
        public boolean awaitEmitterParked() {
            return loopback.awaitEmitterParked();
        }

        @Override
        public void close() {
            loopback.close();
        }
    }

    /** An egress nothing reaches: admission rejects the open before the head would be written. */
    private static final class StubEgress implements StreamEgress {
        private final TransportStream transport = new StubTransport();

        @Override
        public TransportStream transport() {
            return transport;
        }

        @Override
        public int protocolStreamId() {
            return 1;
        }

        @Override
        public boolean writeHead() {
            return true;
        }

        @Override
        public Outcome writeEvent(StreamEvent event, int encodedLength, long authDeadlineEpochMillis,
                                  long eventsEmitted) {
            return Outcome.WRITTEN;
        }

        @Override
        public void finish() {
            // never reached
        }

        @Override
        public void abort() {
            // never reached
        }
    }

    private static final class StubTransport implements TransportStream {
        @Override
        public int read(java.lang.foreign.MemorySegment target, int maxBytes) {
            return -1;
        }

        @Override
        public void write(java.lang.foreign.MemorySegment source, int length) {
            // never reached
        }

        @Override
        public void queueWrite(eu.exeris.kernel.spi.memory.LoanedBuffer buffer, int length) {
            buffer.close();
        }

        @Override
        public long streamId() {
            return 0L;
        }

        @Override
        public boolean isBidirectional() {
            return true;
        }

        @Override
        public boolean isClientInitiated() {
            return true;
        }

        @Override
        public TransportConnection connection() {
            return null;
        }

        @Override
        public boolean hasPendingData() {
            return false;
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
