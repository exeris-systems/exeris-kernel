/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.FaultOrigin;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.http.HttpException;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.ConnectionHandler;
import eu.exeris.kernel.spi.transport.StreamHandler;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStats;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Community: HttpClientEngine exception mapping and dispatch")
class CommunityHttpClientEngineTest {

    private MemoryAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    @AfterEach
    void tearDown() {
        if (allocator != null) {
            allocator.close();
        }
    }

    @Test
    @DisplayName("Non-kernel runtime exception during connect wraps to HttpException.clientConnectFailure (EX-HTTP-4009)")
    void nonKernelRuntimeExceptionWrapsToClientConnectFailure() {
        UncheckedIOException connectCause = new UncheckedIOException(new IOException("Connection refused"));
        TransportEngine transport = new StubTransportEngine() {
            @Override
            public TransportConnection connect(String host, int port) {
                throw connectCause;
            }
        };

        HttpConfig config = HttpConfig.defaultClient();
        try (CommunityHttpClientEngine engine = new CommunityHttpClientEngine(config, allocator, transport, false, "localhost:8080")) {
            engine.start();

            HttpRequest request = HttpRequest.noBody(
                    HttpMethod.GET,
                    "/test",
                    HttpVersion.HTTP_1_1,
                    List.of());

            assertThatThrownBy(() -> engine.send(request))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> {
                        ExerisKernelException eke = (ExerisKernelException) ex;
                        assertThat(eke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4009);
                        assertThat(eke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                        assertThat(eke.getCause()).isSameAs(connectCause);
                    });
        }
    }

    @Test
    @DisplayName("TransportException during connect wraps to HttpException.clientConnectFailure (EX-HTTP-4009)")
    void transportExceptionDuringConnectWrapsToClientConnectFailure() {
        TransportException transportException = TransportException.bindFailure("test-transport", 8080, null);
        TransportEngine transport = new StubTransportEngine() {
            @Override
            public TransportConnection connect(String host, int port) {
                throw transportException;
            }
        };

        HttpConfig config = HttpConfig.defaultClient();
        try (CommunityHttpClientEngine engine = new CommunityHttpClientEngine(config, allocator, transport, false, "localhost:8080")) {
            engine.start();

            HttpRequest request = HttpRequest.noBody(
                    HttpMethod.GET,
                    "/test",
                    HttpVersion.HTTP_1_1,
                    List.of());

            assertThatThrownBy(() -> engine.send(request))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> {
                        ExerisKernelException eke = (ExerisKernelException) ex;
                        assertThat(eke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4009);
                        assertThat(eke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                        assertThat(eke.getCause()).isSameAs(transportException);
                    });
        }
    }

    @Test
    @DisplayName("HttpException during connect is rethrown directly without double wrapping")
    void httpExceptionDuringConnectRethrownDirectly() {
        HttpException httpException = HttpException.clientConnectFailure(
                CommunityHttpClientEngine.ENGINE_NAME, "localhost", 8080, null);
        TransportEngine transport = new StubTransportEngine() {
            @Override
            public TransportConnection connect(String host, int port) {
                throw httpException;
            }
        };

        HttpConfig config = HttpConfig.defaultClient();
        try (CommunityHttpClientEngine engine = new CommunityHttpClientEngine(
                config, allocator, transport, false, "localhost:8080")) {
            engine.start();

            HttpRequest request = HttpRequest.noBody(
                    HttpMethod.GET,
                    "/test",
                    HttpVersion.HTTP_1_1,
                    List.of());

            assertThatThrownBy(() -> engine.send(request))
                    .isSameAs(httpException);
        }
    }

    @Test
    @DisplayName("Malformed response during exchange throws Http1ParseException with FaultOrigin.SYSTEM (ADR-083)")
    void malformedResponseThrowsHttp1ParseExceptionWithSystemOrigin() {
        byte[] malformedResponse = "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        TransportEngine transport = new StubTransportEngine() {
            @Override
            public TransportConnection connect(String host, int port) {
                return new StubConnection(malformedResponse);
            }
        };

        HttpConfig config = HttpConfig.defaultClient();
        try (CommunityHttpClientEngine engine = new CommunityHttpClientEngine(config, allocator, transport, false, "localhost:8080")) {
            engine.start();

            HttpRequest request = HttpRequest.noBody(
                    HttpMethod.GET,
                    "/test",
                    HttpVersion.HTTP_1_1,
                    List.of());

            assertThatThrownBy(() -> engine.send(request))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> {
                        ExerisKernelException eke = (ExerisKernelException) ex;
                        assertThat(eke.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4004);
                        assertThat(eke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                    });
        }
    }

    @Test
    @DisplayName("Non-kernel exception during exchange maps to TransportException.sendFailure (EX-NET-4002), not connect failure")
    void nonKernelExceptionDuringExchangeThrowsTransportSendFailureNotConnectFailure() {
        UncheckedIOException exchangeCause = new UncheckedIOException(new IOException("Broken pipe"));
        TransportEngine transport = new StubTransportEngine() {
            @Override
            public TransportConnection connect(String host, int port) {
                return new StubConnection(new byte[0]) {
                    @Override
                    public TransportStream openStream() {
                        return new StubStream(this, new byte[0]) {
                            @Override
                            public void write(MemorySegment source, int length) {
                                throw exchangeCause;
                            }
                        };
                    }
                };
            }
        };

        HttpConfig config = HttpConfig.defaultClient();
        try (CommunityHttpClientEngine engine = new CommunityHttpClientEngine(config, allocator, transport, false, "localhost:8080")) {
            engine.start();

            HttpRequest request = HttpRequest.noBody(
                    HttpMethod.GET,
                    "/test",
                    HttpVersion.HTTP_1_1,
                    List.of());

            assertThatThrownBy(() -> engine.send(request))
                    .isInstanceOf(ExerisKernelException.class)
                    .satisfies(ex -> {
                        ExerisKernelException eke = (ExerisKernelException) ex;
                        assertThat(eke.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4002);
                        assertThat(eke.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                        assertThat(eke.getCause()).isSameAs(exchangeCause);
                    });
        }
    }

    @Test
    @DisplayName("Empty response from peer throws TransportException.sendFailure (EX-NET-4002), not EX-HTTP-4004")
    void emptyPeerResponseThrowsTransportSendFailure() {
        TransportEngine transport = new StubTransportEngine() {
            @Override
            public TransportConnection connect(String host, int port) {
                return new StubConnection(new byte[0]);
            }
        };

        HttpConfig config = HttpConfig.defaultClient();
        try (CommunityHttpClientEngine engine = new CommunityHttpClientEngine(
                config, allocator, transport, false, "localhost:8080")) {
            engine.start();

            HttpRequest request = HttpRequest.noBody(
                    HttpMethod.GET,
                    "/test",
                    HttpVersion.HTTP_1_1,
                    List.of());

            assertThatThrownBy(() -> engine.send(request))
                    .isInstanceOf(TransportException.class)
                    .satisfies(ex -> {
                        TransportException te = (TransportException) ex;
                        assertThat(te.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4002);
                        assertThat(te.faultOrigin()).isEqualTo(FaultOrigin.SYSTEM);
                        assertThat(te.transportName()).isEqualTo("community-http-client");
                    });
        }
    }

    private abstract static class StubTransportEngine implements TransportEngine {
        @Override
        public void setStreamHandler(StreamHandler handler) {
        }

        @Override
        public void setConnectionHandler(ConnectionHandler handler) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public TransportMode mode() {
            return TransportMode.CLIENT;
        }

        @Override
        public TransportStats stats() {
            return TransportStats.EMPTY;
        }

        @Override
        public String engineName() {
            return "stub-transport";
        }

        @Override
        public void close() {
        }
    }

    private static class StubConnection implements TransportConnection {
        private final byte[] wire;
        private boolean open = true;

        StubConnection(byte[] wire) {
            this.wire = wire;
        }

        @Override
        public TransportStream openStream() {
            return new StubStream(this, wire);
        }

        @Override
        public TransportStream openUnidirectionalStream() {
            return openStream();
        }

        @Override
        public String remoteAddress() {
            return "127.0.0.1";
        }

        @Override
        public int remotePort() {
            return 8080;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public Object attachment() {
            return null;
        }

        @Override
        public void setAttachment(Object attachment) {
        }

        @Override
        public boolean tick() {
            return false;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    private static class StubStream implements TransportStream {
        private final StubConnection connection;
        private final byte[] wire;
        private int offset;

        StubStream(StubConnection connection, byte[] wire) {
            this.connection = connection;
            this.wire = wire;
        }

        @Override
        public int read(MemorySegment target, int maxBytes) {
            if (offset >= wire.length) {
                return -1;
            }
            int toRead = Math.min(maxBytes, wire.length - offset);
            MemorySegment.copy(wire, offset, target, ValueLayout.JAVA_BYTE, 0, toRead);
            offset += toRead;
            return toRead;
        }

        @Override
        public void write(MemorySegment source, int length) {
        }

        @Override
        public void queueWrite(LoanedBuffer buffer, int length) {
            buffer.close();
        }

        @Override
        public long streamId() {
            return 1L;
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
            return connection;
        }

        @Override
        public boolean hasPendingData() {
            return offset < wire.length;
        }

        @Override
        public void close() {
            offset = wire.length;
        }
    }
}
