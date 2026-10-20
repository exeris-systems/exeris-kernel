/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK: an {@link HttpExchange} reports the immediate transport peer of its connection through
 * {@link HttpExchange#remoteAddress()} and {@link HttpExchange#remotePort()} (ADR-009).
 *
 * <p>The value is the address and port the peer's socket has on the wire. Each case opens a raw JDK
 * socket explicitly bound to a loopback address and port {@code 0}, connects it to the server, and
 * requires the handler to report exactly that socket's {@code getLocalAddress().getHostAddress()} and
 * {@code getLocalPort()}. Loopback clients all share one address, so a case that could not tell two
 * sockets apart by address relies on the port; the Linux-only case binds a second loopback address
 * so that the address itself is discriminated.
 *
 * <h2>What the suite pins</h2>
 * <ul>
 *   <li>Plaintext HTTP/1.1 reports the connecting socket.</li>
 *   <li>Two live plaintext connections each report their own port, which rules out a value captured
 *       once and reused.</li>
 *   <li>Two requests on one keep-alive connection report the same peer.</li>
 *   <li>HTTP/1.1 and HTTP/2 over TLS report the connecting socket, with the protocol confirmed as
 *       negotiated through ALPN before the observation is judged.</li>
 *   <li>Cleartext HTTP/2 with prior knowledge reports the connecting socket.</li>
 *   <li>On Linux, a client bound to {@code 127.0.0.2} is reported as {@code 127.0.0.2}.</li>
 * </ul>
 *
 * <p>The suite is separate from {@link AbstractHttpProviderLoopbackTck} so a provider binds it when it
 * implements the contract, without the plaintext loopback contract going red first.
 *
 * <h2>Binding</h2>
 * <p>A binding starts a server engine for each case through {@link #startServer}, for both
 * {@link Transport#PLAINTEXT} and {@link Transport#TLS}. The TLS form is mandatory: a binding that
 * cannot serve TLS fails here rather than skipping the three TLS cases. The server must accept
 * cleartext HTTP/2 with prior knowledge and must advertise {@code h2} and {@code http/1.1} through
 * ALPN, unless {@link #supportsCleartextHttp2()} is overridden to say otherwise.
 *
 * @since 0.13
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public abstract class AbstractHttpServerPeerAddressTck {

    /** The transport a server fixture is started on. */
    public enum Transport {
        /** Cleartext: no TLS on the listener. */
        PLAINTEXT,
        /** TLS on the listener, with ALPN offering {@code h2} and {@code http/1.1}. */
        TLS
    }

    /**
     * A started server a case drives: the engine, the literal address it listens on and, for TLS, a
     * client context that trusts the server's certificate.
     *
     * @param engine      the started server engine; closed by the case
     * @param host        an IP address literal the server accepts on, reachable on the loopback
     *                    interface
     * @param port        the port the server accepts on
     * @param clientTrust a client context trusting the server, for {@link Transport#TLS}; {@code null}
     *                    for {@link Transport#PLAINTEXT}
     */
    public record StartedServer(HttpServerEngine engine, String host, int port, SSLContext clientTrust)
            implements AutoCloseable {

        @Override
        public void close() {
            engine.close();
        }
    }

    private static final String LOOPBACK = "127.0.0.1";
    private static final String ALTERNATE_LOOPBACK = "127.0.0.2";
    private static final int CONNECT_LIMIT_MILLIS = 5_000;
    private static final int RESPONSE_LIMIT_MILLIS = 10_000;

    private static final byte[] CLIENT_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int FRAME_HEADER_BYTES = 9;
    private static final int TYPE_HEADERS = 0x1;
    private static final int TYPE_RST_STREAM = 0x3;
    private static final int TYPE_SETTINGS = 0x4;
    private static final int TYPE_GOAWAY = 0x7;
    private static final int FLAG_END_STREAM = 0x1;
    private static final int FLAG_ACK = 0x1;
    private static final int FLAG_END_HEADERS = 0x4;
    private static final int STREAM_ID = 1;

    // HPACK static table indices (RFC 7541 Appendix A).
    private static final int STATIC_AUTHORITY = 1;
    private static final int STATIC_METHOD_GET = 2;
    private static final int STATIC_SCHEME_HTTP = 6;
    private static final int STATIC_SCHEME_HTTPS = 7;
    private static final int STATIC_PATH = 4;

    /** Constructor for subclasses. */
    protected AbstractHttpServerPeerAddressTck() {
        // binding classes start the server engine under test
    }

    /**
     * Starts a server engine that hands every request to {@code handler}, on {@code transport}.
     *
     * @param handler   the handler the engine dispatches to; the case reads what it observes from it
     * @param transport the transport to listen on
     * @return a started server; the case closes it
     * @implSpec A {@link Transport#TLS} server presents a certificate the returned
     *           {@link StartedServer#clientTrust()} trusts, and negotiates {@code h2} and
     *           {@code http/1.1} through ALPN. A binding that cannot serve TLS fails here rather than
     *           returning a plaintext server.
     */
    protected abstract StartedServer startServer(HttpHandler handler, Transport transport);

    /**
     * Whether the server accepts cleartext HTTP/2 with prior knowledge (RFC 9113 §3.3).
     *
     * @return {@code true} by default; an override returns {@code false} only for a server that
     *         cannot serve cleartext HTTP/2, and the cleartext HTTP/2 case is then reported as skipped
     */
    protected boolean supportsCleartextHttp2() {
        return true;
    }

    @Test
    @DisplayName("(a) HTTP/1.1 over plaintext reports the connecting socket's address and port")
    void plaintextHttp1ReportsTheConnectingSocket() throws Exception {
        Recorder recorder = new Recorder();
        try (StartedServer server = startServer(recorder, Transport.PLAINTEXT);
             Socket client = connectPlaintext(LOOPBACK, server.host(), server.port())) {
            exchangeHttp1(client, "/a");

            assertPeer(recorder.awaitOne(), client, HttpVersion.HTTP_1_1, "plaintext HTTP/1.1");
        }
    }

    @Test
    @DisplayName("(b) two live plaintext connections each report their own port, and the ports differ")
    void twoPlaintextConnectionsReportTheirOwnPorts() throws Exception {
        Recorder recorder = new Recorder();
        try (StartedServer server = startServer(recorder, Transport.PLAINTEXT);
             Socket first = connectPlaintext(LOOPBACK, server.host(), server.port());
             Socket second = connectPlaintext(LOOPBACK, server.host(), server.port())) {
            exchangeHttp1(first, "/b1");
            assertPeer(recorder.awaitOne(), first, HttpVersion.HTTP_1_1, "the first plaintext connection");

            exchangeHttp1(second, "/b2");
            assertPeer(recorder.awaitOne(), second, HttpVersion.HTTP_1_1, "the second plaintext connection");

            assertThat(second.getLocalPort())
                    .as("both connections are live, so their ephemeral ports differ")
                    .isNotEqualTo(first.getLocalPort());
        }
    }

    @Test
    @DisplayName("(c) two requests on one keep-alive connection report the same peer")
    void secondRequestOnAKeepAliveConnectionReportsTheSamePeer() throws Exception {
        Recorder recorder = new Recorder();
        try (StartedServer server = startServer(recorder, Transport.PLAINTEXT);
             Socket client = connectPlaintext(LOOPBACK, server.host(), server.port())) {
            exchangeHttp1(client, "/c1");
            Observation first = recorder.awaitOne();

            exchangeHttp1(client, "/c2");
            Observation second = recorder.awaitOne();

            assertPeer(first, client, HttpVersion.HTTP_1_1, "the first request on a keep-alive connection");
            assertPeer(second, client, HttpVersion.HTTP_1_1, "the second request on a keep-alive connection");
        }
    }

    @Test
    @DisplayName("(d) HTTP/1.1 over TLS reports the connecting socket's address and port")
    void tlsHttp1ReportsTheConnectingSocket() throws Exception {
        Recorder recorder = new Recorder();
        try (StartedServer server = startServer(recorder, Transport.TLS)) {
            try (SSLSocket client = connectTls(server, LOOPBACK, "http/1.1")) {
                assertThat(client.getApplicationProtocol())
                        .as("the ALPN protocol negotiated over TLS")
                        .isEqualTo("http/1.1");
                exchangeHttp1(client, "/d");

                assertPeer(recorder.awaitOne(), client, HttpVersion.HTTP_1_1, "HTTP/1.1 over TLS");
            }
        }
    }

    @Test
    @DisplayName("(e) HTTP/2 over TLS reports the connecting socket's address and port")
    void tlsHttp2ReportsTheConnectingSocket() throws Exception {
        Recorder recorder = new Recorder();
        try (StartedServer server = startServer(recorder, Transport.TLS)) {
            try (SSLSocket client = connectTls(server, LOOPBACK, "h2")) {
                assertThat(client.getApplicationProtocol())
                        .as("the ALPN protocol negotiated over TLS must be h2 for an HTTP/2 case")
                        .isEqualTo("h2");
                sendHttp2Request(client.getOutputStream(), "https", "/e");
                awaitResponseHeaders(new DataInputStream(client.getInputStream()), client.getOutputStream());

                assertPeer(recorder.awaitOne(), client, HttpVersion.HTTP_2, "HTTP/2 over TLS");
            }
        }
    }

    @Test
    @DisplayName("(f) cleartext HTTP/2 with prior knowledge reports the connecting socket's address and port")
    void cleartextHttp2ReportsTheConnectingSocket() throws Exception {
        assumeTrue(supportsCleartextHttp2(), "this binding does not serve cleartext HTTP/2");
        Recorder recorder = new Recorder();
        try (StartedServer server = startServer(recorder, Transport.PLAINTEXT);
             Socket client = connectPlaintext(LOOPBACK, server.host(), server.port())) {
            sendHttp2Request(client.getOutputStream(), "http", "/f");
            awaitResponseHeaders(new DataInputStream(client.getInputStream()), client.getOutputStream());

            assertPeer(recorder.awaitOne(), client, HttpVersion.HTTP_2, "cleartext HTTP/2 with prior knowledge");
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @DisplayName("(g) a client bound to 127.0.0.2 is reported as 127.0.0.2, not as the server's own loopback")
    void sourceAddressOtherThanTheServerLoopbackIsReported() throws Exception {
        Recorder recorder = new Recorder();
        try (StartedServer server = startServer(recorder, Transport.PLAINTEXT);
             Socket client = connectPlaintext(ALTERNATE_LOOPBACK, server.host(), server.port())) {
            exchangeHttp1(client, "/g");

            Observation seen = recorder.awaitOne();
            assertThat(seen.address())
                    .as("remoteAddress() of a client bound to 127.0.0.2 and dialling the loopback server")
                    .isEqualTo(ALTERNATE_LOOPBACK);
            assertThat(seen.port())
                    .as("remotePort() of the same client")
                    .isEqualTo(client.getLocalPort());
        }
    }

    /** What the handler saw of one request: the peer it arrived from, and the version it arrived on. */
    private record Observation(String address, int port, HttpVersion version) {
    }

    /**
     * Records each request's peer and answers 200. The observation is taken before the response is
     * written, so it is queued by the time the client reads the response.
     */
    private static final class Recorder implements HttpHandler {

        private final BlockingQueue<Observation> observed = new LinkedBlockingQueue<>();

        @Override
        public void handle(HttpExchange exchange) {
            observed.add(new Observation(
                    exchange.remoteAddress(),
                    exchange.remotePort(),
                    exchange.request().version()));
            exchange.respond(HttpStatus.OK);
        }

        Observation awaitOne() throws InterruptedException {
            Observation seen = observed.poll(RESPONSE_LIMIT_MILLIS, TimeUnit.MILLISECONDS);
            assertThat(seen).as("the handler ran for the request within the response limit").isNotNull();
            return seen;
        }
    }

    private static void assertPeer(Observation seen, Socket client, HttpVersion version, String what) {
        assertThat(seen.address())
                .as("remoteAddress() of %s", what)
                .isEqualTo(client.getLocalAddress().getHostAddress());
        assertThat(seen.port())
                .as("remotePort() of %s", what)
                .isEqualTo(client.getLocalPort());
        assertThat(seen.version())
                .as("the version %s arrived on", what)
                .isEqualTo(version);
    }

    private static Socket connectPlaintext(String sourceHost, String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.bind(new InetSocketAddress(sourceHost, 0));
            socket.connect(new InetSocketAddress(host, port), CONNECT_LIMIT_MILLIS);
            socket.setSoTimeout(RESPONSE_LIMIT_MILLIS);
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    private static SSLSocket connectTls(StartedServer server, String sourceHost, String alpn) throws IOException {
        if (server.clientTrust() == null) {
            fail("a TLS case needs a server fixture with a client trust context");
        }
        SSLSocket socket = (SSLSocket) server.clientTrust().getSocketFactory().createSocket();
        try {
            socket.bind(new InetSocketAddress(sourceHost, 0));
            socket.connect(new InetSocketAddress(server.host(), server.port()), CONNECT_LIMIT_MILLIS);
            socket.setSoTimeout(RESPONSE_LIMIT_MILLIS);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setApplicationProtocols(new String[] {alpn});
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /** Sends one HTTP/1.1 GET and requires the 200 status line the handler answers with. */
    private static void exchangeHttp1(Socket client, String path) throws IOException {
        OutputStream out = client.getOutputStream();
        out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String statusLine = readHttp1ResponseStatusLine(client.getInputStream());
        assertThat(statusLine).as("the status line of the response to %s", path).startsWith("HTTP/1.1 200");
    }

    /**
     * Reads one HTTP/1.1 response and returns its status line. The body is consumed by the declared
     * {@code Content-Length}, so a following request on the same connection starts at its own head.
     */
    private static String readHttp1ResponseStatusLine(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int next = in.read();
            if (next < 0) {
                throw new EOFException("the connection closed before the response head ended");
            }
            head.append((char) next);
        }
        String text = head.toString();
        int length = declaredContentLength(text);
        byte[] body = in.readNBytes(length);
        if (body.length != length) {
            throw new EOFException("the connection closed inside the response body");
        }
        return text.substring(0, text.indexOf("\r\n"));
    }

    private static int declaredContentLength(String head) {
        String lower = head.toLowerCase(Locale.ROOT);
        int field = lower.indexOf("\r\ncontent-length:");
        if (field < 0) {
            return 0;
        }
        int valueStart = field + "\r\ncontent-length:".length();
        int valueEnd = lower.indexOf("\r\n", valueStart);
        return Integer.parseInt(lower.substring(valueStart, valueEnd).trim());
    }

    /**
     * Writes the client preface, an empty SETTINGS frame and a single HEADERS frame on stream 1 that
     * ends the stream. The request block is literal without indexing and without Huffman coding, so
     * it needs no HPACK state from the server.
     */
    private static void sendHttp2Request(OutputStream out, String scheme, String path) throws IOException {
        out.write(CLIENT_PREFACE);
        writeFrame(out, TYPE_SETTINGS, 0, 0, new byte[0]);
        writeFrame(out, TYPE_HEADERS, FLAG_END_STREAM | FLAG_END_HEADERS, STREAM_ID,
                requestBlock(scheme, path));
        out.flush();
    }

    /**
     * Reads frames until the response HEADERS for stream 1 arrives. The handler has already run when
     * it does, so the observation is waiting. A server SETTINGS is acknowledged; a refusal of the
     * stream or a GOAWAY fails the case, because a refused request never reached the handler.
     */
    private static void awaitResponseHeaders(DataInputStream in, OutputStream out) throws IOException {
        while (true) {
            int lengthAndType = in.readInt();
            int length = lengthAndType >>> 8;
            int type = lengthAndType & 0xFF;
            int flags = in.readUnsignedByte();
            int streamId = in.readInt() & 0x7FFF_FFFF;
            byte[] payload = in.readNBytes(length);
            if (payload.length != length) {
                throw new EOFException("the connection closed inside an HTTP/2 frame");
            }
            if (type == TYPE_HEADERS && streamId == STREAM_ID) {
                return;
            }
            if (type == TYPE_SETTINGS && (flags & FLAG_ACK) == 0) {
                writeFrame(out, TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
                out.flush();
            } else if (type == TYPE_GOAWAY || (type == TYPE_RST_STREAM && streamId == STREAM_ID)) {
                fail("the server refused the request: frame type 0x%x on stream %d", type, streamId);
            }
        }
    }

    /** A request header block: four fields from the static table and one literal authority. */
    private static byte[] requestBlock(String scheme, String path) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInteger(out, 0x80, 7, STATIC_METHOD_GET);
        writeInteger(out, 0x80, 7, "https".equals(scheme) ? STATIC_SCHEME_HTTPS : STATIC_SCHEME_HTTP);
        // RFC 7541 §6.2.2: literal without indexing, name from the static table.
        writeInteger(out, 0x00, 4, STATIC_PATH);
        writeString(out, path);
        writeInteger(out, 0x00, 4, STATIC_AUTHORITY);
        writeString(out, "localhost");
        return out.toByteArray();
    }

    /** RFC 7541 §5.2: a string literal with the Huffman bit clear. */
    private static void writeString(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        writeInteger(out, 0x00, 7, bytes.length);
        out.writeBytes(bytes);
    }

    /** RFC 7541 §5.1: an integer on an N-bit prefix, the high bits of the first octet given. */
    private static void writeInteger(ByteArrayOutputStream out, int highBits, int prefixBits, int value) {
        int maxPrefix = (1 << prefixBits) - 1;
        if (value < maxPrefix) {
            out.write(highBits | value);
            return;
        }
        out.write(highBits | maxPrefix);
        int remaining = value - maxPrefix;
        while (remaining >= 0x80) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    /** RFC 9113 §4.1: a frame header of length, type, flags and stream identifier, then the payload. */
    private static void writeFrame(OutputStream out, int type, int flags, int streamId, byte[] payload)
            throws IOException {
        ByteBuffer header = ByteBuffer.allocate(FRAME_HEADER_BYTES)
                .putInt((payload.length << 8) | type)
                .put((byte) flags)
                .putInt(streamId & 0x7FFF_FFFF);
        out.write(header.array());
        out.write(payload);
    }
}
