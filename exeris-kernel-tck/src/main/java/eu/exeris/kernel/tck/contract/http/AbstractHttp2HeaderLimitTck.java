/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * TCK: the three HTTP/2 header limits of {@link HttpConfig}, judged on the wire (ADR-071).
 *
 * <p>The keys under test are {@link HttpConfig#maxHeaderBlockSize()} ({@code http.maxHeaderBlockSize}),
 * {@link HttpConfig#maxHeaderListSize()} ({@code http.maxHeaderListSize}) and
 * {@link HttpConfig#maxStringLiteralSize()} ({@code http.maxStringLiteralSize}). Each case starts the
 * provider's server engine with small explicit limits, opens a raw TCP connection speaking cleartext
 * HTTP/2 with prior knowledge (RFC 9113 §3.3), and sends hand-built frames carrying a literal HPACK
 * block. Nothing in the suite looks inside the engine: a limit is honoured when the bytes on the
 * socket and the handler's invocations say so.
 *
 * <p>Each key is measured the way the configuration names it:
 * <ul>
 *   <li>{@code maxHeaderBlockSize} — the byte length of the assembled header block as it arrives
 *       compressed, HEADERS plus every CONTINUATION. The cases split the block across both frames, so
 *       a bound applied per frame rather than to the assembled block lets the over-limit case
 *       through.</li>
 *   <li>{@code maxHeaderListSize} — the decoded field section, sized as RFC 9113 §6.5.2 defines it:
 *       name octets plus value octets plus 32 per field. The server advertises this bound as
 *       {@code SETTINGS_MAX_HEADER_LIST_SIZE}.</li>
 *   <li>{@code maxStringLiteralSize} — one decoded name or one decoded value. A name and a value are
 *       separate directions of the bound, and each has an over-limit case.</li>
 * </ul>
 *
 * <p>For every key a request whose measured quantity <em>equals</em> the limit is served — the
 * handler runs and the response is {@code :status 200} — and a request one octet over it is refused
 * and never reaches the handler. Equality is the discriminating edge in both directions: a bound
 * applied one octet early refuses the first case, a bound not applied serves the second. In each
 * nested group the key under test is the smallest of the three and the other two are set well above
 * anything the request carries, all three distinct, so a bound wired to the wrong key serves the
 * over-limit request.
 *
 * <h2>What "refused" means</h2>
 * <p>RFC 9113 lets a server turn away an oversized field section in more than one way, and the
 * contract accepts each of them, provided the refusal is said on the wire and the request is not
 * processed:
 * <ul>
 *   <li>a response on the request's stream with {@code :status 400} or {@code :status 431}
 *       (RFC 9113 §8.2.3, RFC 6585 §5);</li>
 *   <li>{@code RST_STREAM} on the request's stream carrying an error code other than
 *       {@code NO_ERROR} (a stream error, RFC 9113 §5.4.2);</li>
 *   <li>{@code GOAWAY} carrying an error code other than {@code NO_ERROR} whose last-stream-id is
 *       below the request's stream, which per RFC 9113 §6.8 says the request was not and will not be
 *       processed (a connection error, RFC 9113 §5.4.1).</li>
 * </ul>
 * A connection closed with none of these is not a refusal: it is indistinguishable from a crash. A
 * {@code 2xx} is a failure whatever else happens. When the refusal leaves the connection open the
 * case sends one more well-formed request on the next stream and waits for its outcome before judging,
 * which narrows the window in which a late handler invocation for the refused request could go unseen.
 * Whether that later request is served is not part of the contract.
 *
 * <h2>Binding obligations</h2>
 * <p>The server engine must accept cleartext HTTP/2 with prior knowledge when
 * {@link HttpConfig#maxVersion()} is {@link HttpVersion#HTTP_2} and
 * {@link HttpConfig#h2cUpgradeEnabled()} is set — the configuration {@link #serverConfig} builds. The
 * client announces {@code SETTINGS_HEADER_TABLE_SIZE = 0}, so the server's response blocks reference
 * the static table only; the suite decodes {@code :status} from such a block, Huffman-coded or not.
 *
 * @since 0.13
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public abstract class AbstractHttp2HeaderLimitTck {

    private static final byte[] CLIENT_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final int FRAME_HEADER_BYTES = 9;
    private static final int TYPE_HEADERS = 0x1;
    private static final int TYPE_RST_STREAM = 0x3;
    private static final int TYPE_SETTINGS = 0x4;
    private static final int TYPE_PING = 0x6;
    private static final int TYPE_GOAWAY = 0x7;
    private static final int TYPE_CONTINUATION = 0x9;
    private static final int FLAG_END_STREAM = 0x1;
    private static final int FLAG_ACK = 0x1;
    private static final int FLAG_END_HEADERS = 0x4;
    private static final int FLAG_PADDED = 0x8;
    private static final int FLAG_PRIORITY = 0x20;
    private static final int SETTINGS_HEADER_TABLE_SIZE = 0x1;
    private static final int SETTINGS_ENABLE_PUSH = 0x2;
    private static final int SETTINGS_MAX_HEADER_LIST_SIZE = 0x6;
    private static final long NO_ERROR = 0x0;

    /** RFC 9113 §6.5.2: the per-field overhead counted into the decoded field-section size. */
    private static final int FIELD_OVERHEAD = 32;

    /** RFC 7541 Appendix A: static-table indices of the request pseudo-headers the cases send. */
    private static final int STATIC_AUTHORITY = 1;
    private static final int STATIC_METHOD_GET = 2;
    private static final int STATIC_PATH = 4;
    private static final int STATIC_SCHEME_HTTP = 6;
    /** RFC 7541 Appendix A: indices 8 to 14 are {@code :status}, each with a fixed value. */
    private static final int STATIC_STATUS_FIRST = 8;
    private static final int STATIC_STATUS_LAST = 14;
    private static final int[] STATIC_STATUS_VALUES = {200, 204, 206, 304, 400, 404, 500};

    private static final String AT_LIMIT_PATH = "/at-limit";
    private static final String OVER_LIMIT_PATH = "/over-limit";
    private static final String FOLLOW_UP_PATH = "/follow-up";
    private static final int FIRST_STREAM = 1;
    private static final int FOLLOW_UP_STREAM = 3;

    /**
     * Creates the contract; subclasses supply the provider under test via {@link #createProvider()}.
     */
    public AbstractHttp2HeaderLimitTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Creates the {@link HttpProvider} under test.
     *
     * @return the provider under test; never {@code null}
     */
    protected abstract HttpProvider createProvider();

    /**
     * Returns the loopback address the server binds to and the raw client dials.
     *
     * @return a loopback host address; defaults to {@code "127.0.0.1"}
     * @implSpec An override exercises a different loopback interface; the value must resolve locally.
     */
    protected String loopbackHost() {
        return "127.0.0.1";
    }

    /**
     * Returns the bound on how long the raw client waits for the server's answer to one request.
     *
     * @return a positive duration; defaults to five seconds
     * @apiNote The bound is a deadline, not a delay: a case reads until the frame it is waiting for
     *          arrives and fails when the deadline passes first.
     */
    protected Duration responseTimeout() {
        return Duration.ofSeconds(5);
    }

    /**
     * Returns the server configuration a case starts the engine with.
     *
     * @param host                 the address the server binds to
     * @param port                 the port the server binds to
     * @param maxHeaderBlockSize   the {@code http.maxHeaderBlockSize} under test
     * @param maxHeaderListSize    the {@code http.maxHeaderListSize} under test
     * @param maxStringLiteralSize the {@code http.maxStringLiteralSize} under test
     * @return a server-mode configuration negotiating up to {@link HttpVersion#HTTP_2} with cleartext
     *         HTTP/2 enabled, the given header limits and the module defaults for everything else
     * @implSpec An override keeps the three header limits exactly as given: the cases size their
     *           requests against them octet for octet.
     */
    protected HttpConfig serverConfig(String host, int port, int maxHeaderBlockSize,
                                      int maxHeaderListSize, int maxStringLiteralSize) {
        return new HttpConfig(
                HttpMode.SERVER,
                host,
                port,
                HttpConfig.DEFAULT_MAX_CONNECTIONS,
                HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                true,
                HttpVersion.HTTP_2,
                null,
                maxHeaderBlockSize,
                maxHeaderListSize,
                maxStringLiteralSize);
    }

    /**
     * Creates the server engine a case runs against.
     *
     * @param provider the provider under test
     * @param config   the server configuration to create the engine from
     * @return a fresh, not-yet-started server engine; never {@code null}
     * @apiNote Delegates to {@link HttpProvider#createServerEngine(HttpConfig)}.
     * @implSpec An override only establishes what a driver needs bound around engine creation.
     */
    protected HttpServerEngine createServerEngine(HttpProvider provider, HttpConfig config) {
        return provider.createServerEngine(config);
    }

    @Nested
    @DisplayName("http.maxHeaderBlockSize bounds the assembled compressed block")
    class HeaderBlockSize {

        private static final int BLOCK = 512;
        private static final int LIST = 16_384;
        private static final int LITERAL = 8_192;

        @Test
        @DisplayName("A block of exactly maxHeaderBlockSize octets, split over HEADERS + CONTINUATION, is served")
        void blockAtTheLimitIsServed() {
            byte[] block = RequestBlock.ofEncodedSize(AT_LIMIT_PATH, BLOCK).encoded();
            assertThat(block).hasSize(BLOCK);

            Probe probe = probe(BLOCK, LIST, LITERAL, block, true);

            assertServed(probe, AT_LIMIT_PATH, "a block of exactly maxHeaderBlockSize octets");
        }

        @Test
        @DisplayName("A block one octet over maxHeaderBlockSize, no frame of which is over alone, is refused")
        void blockOverTheLimitIsRefused() {
            byte[] block = RequestBlock.ofEncodedSize(OVER_LIMIT_PATH, BLOCK + 1).encoded();
            assertThat(block).hasSize(BLOCK + 1);

            Probe probe = probe(BLOCK, LIST, LITERAL, block, true);

            assertRefused(probe, "a block one octet over maxHeaderBlockSize");
        }
    }

    @Nested
    @DisplayName("http.maxHeaderListSize bounds the decoded field section and is advertised")
    class HeaderListSize {

        private static final int BLOCK = 12_288;
        private static final int LIST = 640;
        private static final int LITERAL = 4_096;

        @Test
        @DisplayName("The server advertises maxHeaderListSize as SETTINGS_MAX_HEADER_LIST_SIZE")
        void serverAdvertisesTheListBound() {
            byte[] block = RequestBlock.ofDecodedSize(AT_LIMIT_PATH, LIST).encoded();

            Probe probe = probe(BLOCK, LIST, LITERAL, block, false);

            assertThat(probe.advertisedMaxHeaderListSize())
                    .as("the server must advertise the decoded-field-section bound it enforces as "
                            + "SETTINGS_MAX_HEADER_LIST_SIZE (RFC 9113 §6.5.2) — and that bound, not "
                            + "the compressed-block bound (%d) or the literal bound (%d)", BLOCK, LITERAL)
                    .isEqualTo((long) LIST);
        }

        @Test
        @DisplayName("A field section of exactly maxHeaderListSize decoded octets is served")
        void listAtTheLimitIsServed() {
            RequestBlock request = RequestBlock.ofDecodedSize(AT_LIMIT_PATH, LIST);
            assertThat(request.decodedSize()).isEqualTo(LIST);

            Probe probe = probe(BLOCK, LIST, LITERAL, request.encoded(), false);

            assertServed(probe, AT_LIMIT_PATH, "a field section of exactly maxHeaderListSize octets");
        }

        @Test
        @DisplayName("A field section one decoded octet over maxHeaderListSize is refused")
        void listOverTheLimitIsRefused() {
            RequestBlock request = RequestBlock.ofDecodedSize(OVER_LIMIT_PATH, LIST + 1);
            assertThat(request.decodedSize()).isEqualTo(LIST + 1);

            Probe probe = probe(BLOCK, LIST, LITERAL, request.encoded(), false);

            assertRefused(probe, "a field section one octet over maxHeaderListSize");
        }
    }

    @Nested
    @DisplayName("http.maxStringLiteralSize bounds one decoded name or value")
    class StringLiteralSize {

        private static final int BLOCK = 10_240;
        private static final int LIST = 20_480;
        private static final int LITERAL = 256;

        @Test
        @DisplayName("A name and a value of exactly maxStringLiteralSize octets each are served")
        void literalsAtTheLimitAreServed() {
            RequestBlock request = RequestBlock.withField(AT_LIMIT_PATH, name(LITERAL), value(LITERAL));

            Probe probe = probe(BLOCK, LIST, LITERAL, request.encoded(), false);

            assertServed(probe, AT_LIMIT_PATH, "a name and a value of exactly maxStringLiteralSize octets");
        }

        @Test
        @DisplayName("A value one octet over maxStringLiteralSize is refused")
        void valueOverTheLimitIsRefused() {
            RequestBlock request = RequestBlock.withField(OVER_LIMIT_PATH, "x-limit", value(LITERAL + 1));

            Probe probe = probe(BLOCK, LIST, LITERAL, request.encoded(), false);

            assertRefused(probe, "a value one octet over maxStringLiteralSize");
        }

        @Test
        @DisplayName("A name one octet over maxStringLiteralSize is refused")
        void nameOverTheLimitIsRefused() {
            RequestBlock request = RequestBlock.withField(OVER_LIMIT_PATH, name(LITERAL + 1), "v");

            Probe probe = probe(BLOCK, LIST, LITERAL, request.encoded(), false);

            assertRefused(probe, "a name one octet over maxStringLiteralSize");
        }

        /** A lowercase field name of exactly {@code length} octets. */
        private static String name(int length) {
            return "x-" + "n".repeat(length - 2);
        }

        /** A field value of exactly {@code length} octets. */
        private static String value(int length) {
            return "v".repeat(length);
        }
    }

    // =========================================================================
    // Judgement
    // =========================================================================

    private static void assertServed(Probe probe, String path, String what) {
        assertThat(probe.outcome().kind())
                .as("%s must be answered on its stream, got %s", what, probe.outcome())
                .isEqualTo(Kind.STATUS);
        assertThat(probe.outcome().status())
                .as("%s is within the limit, so the handler's 200 must come back", what)
                .isEqualTo(HttpStatus.OK.code());
        assertThat(probe.servedPaths())
                .as("%s is within the limit, so the handler must run for it", what)
                .containsExactly(path);
    }

    private static void assertRefused(Probe probe, String what) {
        Outcome outcome = probe.outcome();
        switch (outcome.kind()) {
            case STATUS -> assertThat(outcome.status())
                    .as("%s may be answered on its stream only with 400 or 431, got %s", what, outcome)
                    .isIn(HttpStatus.BAD_REQUEST.code(), 431);
            case RESET -> assertThat(outcome.errorCode())
                    .as("%s refused by RST_STREAM must carry an error code, got %s", what, outcome)
                    .isNotEqualTo(NO_ERROR);
            case GOAWAY -> {
                assertThat(outcome.errorCode())
                        .as("%s refused by GOAWAY must carry an error code, got %s", what, outcome)
                        .isNotEqualTo(NO_ERROR);
                assertThat(outcome.lastStreamId())
                        .as("GOAWAY's last-stream-id must say the refused stream was not processed "
                                + "(RFC 9113 §6.8), got %s", outcome)
                        .isLessThan(FIRST_STREAM);
            }
            case CLOSED -> fail("%s: the server closed the connection without saying why — a refusal "
                    + "must be a 400/431 response, an RST_STREAM or a GOAWAY with an error code", what);
        }
        assertThat(probe.servedPaths())
                .as("%s is over the limit, so the handler must never run for it", what)
                .doesNotContain(OVER_LIMIT_PATH);
    }

    // =========================================================================
    // Fixture
    // =========================================================================

    /**
     * Starts a server with the given limits, sends one request on stream 1 and returns what the
     * server answered, what it advertised, and which paths the handler ran for.
     */
    private Probe probe(int maxHeaderBlockSize, int maxHeaderListSize, int maxStringLiteralSize,
                        byte[] block, boolean splitBlock) {
        HttpProvider provider = createProvider();
        String host = loopbackHost();
        int port = nextFreePort();
        List<String> servedPaths = new CopyOnWriteArrayList<>();
        HttpHandler handler = exchange -> {
            servedPaths.add(exchange.request().path());
            exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
        };

        HttpConfig config = serverConfig(host, port, maxHeaderBlockSize, maxHeaderListSize,
                maxStringLiteralSize);
        try (HttpServerEngine engine = createServerEngine(provider, config)) {
            engine.setHandler(handler);
            engine.start();
            try (RawH2Connection connection = RawH2Connection.open(host, port, responseTimeout())) {
                connection.sendRequest(FIRST_STREAM, block, splitBlock);
                Outcome outcome = connection.awaitOutcome(FIRST_STREAM);
                boolean streamLevelRefusal = outcome.kind() == Kind.RESET
                        || outcome.kind() == Kind.STATUS && outcome.status() != HttpStatus.OK.code();
                if (streamLevelRefusal) {
                    // A refusal that keeps the connection open is followed by a well-formed request:
                    // the server reads the refused stream's frames before the later stream's, so a
                    // dispatch of the refused request is under way before the later one is answered.
                    // The handler runs on a thread of its own, so this narrows the window in which a
                    // late invocation could escape the judgement; it does not close it.
                    connection.sendRequest(FOLLOW_UP_STREAM,
                            RequestBlock.withField(FOLLOW_UP_PATH, "x-limit", "v").encoded(), false);
                    awaitFollowUp(connection);
                }
                return new Probe(outcome, connection.advertisedMaxHeaderListSize(), List.copyOf(servedPaths));
            }
        }
    }

    /**
     * Waits for the follow-up stream's outcome, bounded by the response timeout.
     *
     * <p>The wait is what narrows the window for a late invocation of the refused request; whether
     * the follow-up is answered is not part of the contract, so an unanswered follow-up does not fail
     * the case.
     *
     * @param connection the connection the follow-up was sent on
     * @return whether the server answered the follow-up stream within the timeout
     */
    private static boolean awaitFollowUp(RawH2Connection connection) {
        try {
            connection.awaitOutcome(FOLLOW_UP_STREAM);
            return true;
        } catch (AssertionError unanswered) {
            return false;
        }
    }

    /**
     * Allocates an ephemeral TCP port for the server to bind.
     *
     * <p>Opening and closing a {@link ServerSocket} on port 0 races another process for the port;
     * the SPI has no ephemeral-bind accessor that would close the window.
     */
    private static int nextFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }

    /** What the server did about one request. */
    private enum Kind { STATUS, RESET, GOAWAY, CLOSED }

    /** The server's answer to one stream; fields not meaningful for the kind are {@code -1}. */
    private record Outcome(Kind kind, int status, long errorCode, int lastStreamId) {
        static Outcome status(int status) {
            return new Outcome(Kind.STATUS, status, -1, -1);
        }

        static Outcome reset(long errorCode) {
            return new Outcome(Kind.RESET, -1, errorCode, -1);
        }

        static Outcome goAway(int lastStreamId, long errorCode) {
            return new Outcome(Kind.GOAWAY, -1, errorCode, lastStreamId);
        }

        static Outcome closed() {
            return new Outcome(Kind.CLOSED, -1, -1, -1);
        }
    }

    /** One case's observations: the first stream's outcome, the advertised bound, the handler's runs. */
    private record Probe(Outcome outcome, Long advertisedMaxHeaderListSize, List<String> servedPaths) {
    }

    // =========================================================================
    // HPACK request blocks (RFC 7541), literal and uncompressed by construction
    // =========================================================================

    /**
     * A request header block: {@code :method GET}, {@code :scheme http} and {@code :path} from the
     * static table, {@code :authority} as a literal, and at most one extra literal field. Every literal
     * is emitted without indexing and without Huffman coding, so the block's encoded length and its
     * decoded size are both exact functions of the strings it carries.
     */
    private record RequestBlock(byte[] encoded, int decodedSize) {

        private static final String AUTHORITY = "localhost";
        private static final String PAD_NAME = "x-pad";

        static RequestBlock withField(String path, String name, String value) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int decoded = writePseudoHeaders(out, path);
            writeLiteralNewName(out, name, value);
            decoded += fieldSize(name, value);
            return new RequestBlock(out.toByteArray(), decoded);
        }

        /** A block whose decoded field section is exactly {@code target} octets. */
        static RequestBlock ofDecodedSize(String path, int target) {
            int base = pseudoHeaderSize(path);
            int valueLength = target - base - fieldSize(PAD_NAME, "");
            if (valueLength < 0) {
                throw new IllegalStateException("decoded size " + target + " is below the request's floor");
            }
            return withField(path, PAD_NAME, "p".repeat(valueLength));
        }

        /** A block whose encoded length is exactly {@code target} octets. */
        static RequestBlock ofEncodedSize(String path, int target) {
            for (int valueLength = 0; valueLength <= target; valueLength++) {
                RequestBlock candidate = withField(path, PAD_NAME, "p".repeat(valueLength));
                if (candidate.encoded().length == target) {
                    return candidate;
                }
                if (candidate.encoded().length > target) {
                    break;
                }
            }
            throw new IllegalStateException("no padding reaches an encoded block of " + target + " octets");
        }

        private static int writePseudoHeaders(ByteArrayOutputStream out, String path) {
            writeInteger(out, 0x80, 7, STATIC_METHOD_GET);
            writeInteger(out, 0x80, 7, STATIC_SCHEME_HTTP);
            writeLiteralIndexedName(out, STATIC_PATH, path);
            writeLiteralIndexedName(out, STATIC_AUTHORITY, AUTHORITY);
            return pseudoHeaderSize(path);
        }

        private static int pseudoHeaderSize(String path) {
            return fieldSize(":method", "GET") + fieldSize(":scheme", "http")
                    + fieldSize(":path", path) + fieldSize(":authority", AUTHORITY);
        }

        private static int fieldSize(String name, String value) {
            return name.getBytes(StandardCharsets.UTF_8).length
                    + value.getBytes(StandardCharsets.UTF_8).length + FIELD_OVERHEAD;
        }

        /** RFC 7541 §6.2.2: literal without indexing, name from the static table. */
        private static void writeLiteralIndexedName(ByteArrayOutputStream out, int nameIndex, String value) {
            writeInteger(out, 0x00, 4, nameIndex);
            writeString(out, value);
        }

        /** RFC 7541 §6.2.2: literal without indexing, literal name. */
        private static void writeLiteralNewName(ByteArrayOutputStream out, String name, String value) {
            out.write(0x00);
            writeString(out, name);
            writeString(out, value);
        }

        /** RFC 7541 §5.2: a string literal, Huffman bit clear. */
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
    }

    // =========================================================================
    // Raw HTTP/2 client (RFC 9113), prior knowledge over cleartext TCP
    // =========================================================================

    /**
     * One cleartext HTTP/2 connection driven frame by frame. It acknowledges the server's SETTINGS
     * and PINGs, records the last advertised {@code SETTINGS_MAX_HEADER_LIST_SIZE}, and reads every
     * frame under a deadline.
     */
    private static final class RawH2Connection implements AutoCloseable {

        private final FrameChannel channel;
        private Long advertisedMaxHeaderListSize;

        private RawH2Connection(FrameChannel channel) {
            this.channel = channel;
        }

        static RawH2Connection open(String host, int port, Duration timeout) {
            FrameChannel channel = FrameChannel.connect(host, port, timeout);
            // SETTINGS_HEADER_TABLE_SIZE = 0 keeps the server's response blocks on the static table,
            // which is all the status decoder reads.
            byte[] settings = new byte[12];
            putSetting(settings, 0, SETTINGS_HEADER_TABLE_SIZE, 0);
            putSetting(settings, 6, SETTINGS_ENABLE_PUSH, 0);
            ByteArrayOutputStream preface = new ByteArrayOutputStream();
            preface.writeBytes(CLIENT_PREFACE);
            writeFrame(preface, TYPE_SETTINGS, 0, 0, settings, 0, settings.length);
            channel.write(preface.toByteArray());
            return new RawH2Connection(channel);
        }

        Long advertisedMaxHeaderListSize() {
            return advertisedMaxHeaderListSize;
        }

        void sendRequest(int streamId, byte[] block, boolean split) {
            ByteArrayOutputStream frames = new ByteArrayOutputStream();
            if (split) {
                int first = block.length / 2;
                writeFrame(frames, TYPE_HEADERS, FLAG_END_STREAM, streamId, block, 0, first);
                writeFrame(frames, TYPE_CONTINUATION, FLAG_END_HEADERS, streamId, block, first,
                        block.length - first);
            } else {
                writeFrame(frames, TYPE_HEADERS, FLAG_END_STREAM | FLAG_END_HEADERS, streamId, block, 0,
                        block.length);
            }
            channel.write(frames.toByteArray());
        }

        /**
         * Reads frames until the server answers {@code streamId}: a complete response header block,
         * an {@code RST_STREAM} on it, a {@code GOAWAY}, or the end of the connection.
         */
        Outcome awaitOutcome(int streamId) {
            long deadline = System.nanoTime() + channel.timeout().toNanos();
            ByteArrayOutputStream responseBlock = null;
            while (true) {
                Frame frame = channel.readFrame(deadline, streamId);
                if (frame == null) {
                    return Outcome.closed();
                }
                int type = frame.type();
                if (type == TYPE_SETTINGS) {
                    onSettings(frame);
                    continue;
                }
                if (type == TYPE_PING) {
                    onPing(frame);
                    continue;
                }
                if (type == TYPE_GOAWAY) {
                    return Outcome.goAway(readInt31(frame.payload(), 0), readUint32(frame.payload(), 4));
                }
                if (frame.streamId() == streamId) {
                    if (type == TYPE_RST_STREAM) {
                        return Outcome.reset(readUint32(frame.payload(), 0));
                    }
                    if (type == TYPE_HEADERS) {
                        responseBlock = new ByteArrayOutputStream();
                        responseBlock.writeBytes(headersFragment(frame));
                    } else if (type == TYPE_CONTINUATION && responseBlock != null) {
                        responseBlock.writeBytes(frame.payload());
                    }
                    // DATA, WINDOW_UPDATE, PRIORITY and unknown types say nothing about the outcome.
                    if (responseBlock != null && isHeaderBlockEnd(frame)) {
                        return Outcome.status(StatusDecoder.decode(responseBlock.toByteArray()));
                    }
                }
            }
        }

        private void onSettings(Frame frame) {
            if ((frame.flags() & FLAG_ACK) != 0) {
                return;
            }
            byte[] payload = frame.payload();
            for (int offset = 0; offset + 6 <= payload.length; offset += 6) {
                int id = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
                if (id == SETTINGS_MAX_HEADER_LIST_SIZE) {
                    advertisedMaxHeaderListSize = readUint32(payload, offset + 2);
                }
            }
            sendControl(TYPE_SETTINGS, FLAG_ACK, new byte[0]);
        }

        private void onPing(Frame frame) {
            if ((frame.flags() & FLAG_ACK) == 0) {
                sendControl(TYPE_PING, FLAG_ACK, frame.payload());
            }
        }

        private void sendControl(int type, int flags, byte[] payload) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            writeFrame(frame, type, flags, 0, payload, 0, payload.length);
            channel.writeIfOpen(frame.toByteArray());
        }

        @Override
        public void close() {
            channel.close();
        }
    }

    /** The bytes of one TCP connection, read a frame at a time under a deadline. */
    private static final class FrameChannel implements AutoCloseable {

        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;
        private final Duration timeout;

        private FrameChannel(Socket socket, Duration timeout) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
            this.timeout = timeout;
        }

        static FrameChannel connect(String host, int port, Duration timeout) {
            Socket socket = new Socket();
            try {
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
                return new FrameChannel(socket, timeout);
            } catch (IOException ex) {
                closeQuietly(socket);
                throw new IllegalStateException("cannot open a TCP connection to " + host + ":" + port, ex);
            }
        }

        Duration timeout() {
            return timeout;
        }

        void write(byte[] bytes) {
            try {
                out.write(bytes);
                out.flush();
            } catch (IOException ex) {
                throw new IllegalStateException("cannot write to the server", ex);
            }
        }

        /** Writes {@code bytes} unless the server has already closed its side. */
        void writeIfOpen(byte[] bytes) {
            try {
                out.write(bytes);
                out.flush();
            } catch (IOException _) {
                // The outcome read that follows reports the closed connection.
            }
        }

        /** Reads one frame, or returns {@code null} at the end of the connection. */
        Frame readFrame(long deadline, int streamId) {
            byte[] header = new byte[FRAME_HEADER_BYTES];
            if (!readFully(header, deadline, streamId)) {
                return null;
            }
            int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
            byte[] payload = new byte[length];
            if (!readFully(payload, deadline, streamId)) {
                return null;
            }
            return new Frame(header[3] & 0xFF, header[4] & 0xFF, readInt31(header, 5), payload);
        }

        private boolean readFully(byte[] target, long deadline, int streamId) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0) {
                throw noAnswer(streamId);
            }
            try {
                socket.setSoTimeout((int) remainingMillis);
                in.readFully(target);
                return true;
            } catch (SocketTimeoutException _) {
                throw noAnswer(streamId);
            } catch (IOException _) {
                // End of stream or a reset connection: both are the end of the connection.
                return false;
            }
        }

        private AssertionError noAnswer(int streamId) {
            return new AssertionError("the server did not answer stream " + streamId + " within "
                    + timeout + ": no response, RST_STREAM or GOAWAY arrived");
        }

        @Override
        public void close() {
            closeQuietly(socket);
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException _) {
                // Closing a test socket: nothing left to report.
            }
        }
    }

    private static boolean isHeaderBlockEnd(Frame frame) {
        return (frame.type() == TYPE_HEADERS || frame.type() == TYPE_CONTINUATION)
                && (frame.flags() & FLAG_END_HEADERS) != 0;
    }

    /** The header-block fragment of a HEADERS frame, padding and priority fields stripped. */
    private static byte[] headersFragment(Frame frame) {
        byte[] payload = frame.payload();
        int start = 0;
        int end = payload.length;
        if ((frame.flags() & FLAG_PADDED) != 0) {
            end -= payload[0] & 0xFF;
            start = 1;
        }
        if ((frame.flags() & FLAG_PRIORITY) != 0) {
            start += 5;
        }
        return Arrays.copyOfRange(payload, start, end);
    }

    /** One inbound frame. */
    private record Frame(int type, int flags, int streamId, byte[] payload) {
    }

    private static void writeFrame(ByteArrayOutputStream out, int type, int flags, int streamId,
                                   byte[] payload, int offset, int length) {
        out.write((length >>> 16) & 0xFF);
        out.write((length >>> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(type);
        out.write(flags);
        out.write((streamId >>> 24) & 0x7F);
        out.write((streamId >>> 16) & 0xFF);
        out.write((streamId >>> 8) & 0xFF);
        out.write(streamId & 0xFF);
        out.write(payload, offset, length);
    }

    private static void putSetting(byte[] target, int offset, int id, long value) {
        target[offset] = (byte) (id >>> 8);
        target[offset + 1] = (byte) id;
        target[offset + 2] = (byte) (value >>> 24);
        target[offset + 3] = (byte) (value >>> 16);
        target[offset + 4] = (byte) (value >>> 8);
        target[offset + 5] = (byte) value;
    }

    private static int readInt31(byte[] source, int offset) {
        return (int) (readUint32(source, offset) & 0x7FFF_FFFFL);
    }

    private static long readUint32(byte[] source, int offset) {
        return ((source[offset] & 0xFFL) << 24) | ((source[offset + 1] & 0xFFL) << 16)
                | ((source[offset + 2] & 0xFFL) << 8) | (source[offset + 3] & 0xFFL);
    }

    // =========================================================================
    // :status decoding from a static-table-only response block (RFC 7541)
    // =========================================================================

    /**
     * Reads {@code :status} — the first field of every response block (RFC 9113 §8.3) — from a block
     * that references no dynamic-table entry. Leading dynamic-table size updates are skipped; the
     * status is an indexed static entry, or a literal whose name is a static {@code :status} entry
     * or the literal name {@code :status}, Huffman-coded or not.
     */
    private static final class StatusDecoder {

        private final byte[] block;
        private int position;

        private StatusDecoder(byte[] block) {
            this.block = block;
        }

        static int decode(byte[] block) {
            return new StatusDecoder(block).status();
        }

        private int status() {
            while (position < block.length && (block[position] & 0xE0) == 0x20) {
                readInteger(5);
            }
            if (position >= block.length) {
                throw new AssertionError("the response header block carries no :status field");
            }
            int first = block[position] & 0xFF;
            if ((first & 0x80) != 0) {
                int index = readInteger(7);
                if (index < STATIC_STATUS_FIRST || index > STATIC_STATUS_LAST) {
                    throw new AssertionError("the response's first field is not :status (index " + index + ")");
                }
                return STATIC_STATUS_VALUES[index - STATIC_STATUS_FIRST];
            }
            int nameIndex = (first & 0xC0) == 0x40 ? readInteger(6) : readInteger(4);
            if (nameIndex == 0) {
                String name = readString();
                if (!":status".equals(name)) {
                    throw new AssertionError("the response's first field is " + name + ", not :status");
                }
            } else if (nameIndex < STATIC_STATUS_FIRST || nameIndex > STATIC_STATUS_LAST) {
                throw new AssertionError("the response's first field is not :status (name index " + nameIndex + ")");
            }
            return Integer.parseInt(readString());
        }

        private int readInteger(int prefixBits) {
            int maxPrefix = (1 << prefixBits) - 1;
            int value = block[position++] & maxPrefix;
            if (value < maxPrefix) {
                return value;
            }
            int shift = 0;
            int next;
            do {
                next = block[position++] & 0xFF;
                value += (next & 0x7F) << shift;
                shift += 7;
            } while ((next & 0x80) != 0);
            return value;
        }

        private String readString() {
            boolean huffman = (block[position] & 0x80) != 0;
            int length = readInteger(7);
            byte[] raw = Arrays.copyOfRange(block, position, position + length);
            position += length;
            return huffman ? decodeHuffmanDigits(raw) : new String(raw, StandardCharsets.US_ASCII);
        }

        /**
         * Decodes a Huffman string made of decimal digits only (RFC 7541 Appendix B): '0' to '2' are
         * the 5-bit codes {@code 00000} to {@code 00010}, '3' to '9' the 6-bit codes {@code 011001}
         * to {@code 011111}, and the tail is padded with up to seven 1-bits.
         */
        private static String decodeHuffmanDigits(byte[] encoded) {
            StringBuilder digits = new StringBuilder();
            int totalBits = encoded.length * 8;
            int bit = 0;
            while (bit < totalBits) {
                int remaining = totalBits - bit;
                if (remaining < 8 && bits(encoded, bit, remaining) == (1 << remaining) - 1) {
                    break;
                }
                int five = remaining >= 5 ? bits(encoded, bit, 5) : -1;
                if (five >= 0 && five <= 2) {
                    digits.append((char) ('0' + five));
                    bit += 5;
                    continue;
                }
                int six = remaining >= 6 ? bits(encoded, bit, 6) : -1;
                if (six >= 0x19 && six <= 0x1F) {
                    digits.append((char) ('3' + six - 0x19));
                    bit += 6;
                    continue;
                }
                throw new AssertionError("the :status value is not a Huffman-coded decimal number");
            }
            return digits.toString();
        }

        private static int bits(byte[] source, int fromBit, int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                int bitIndex = fromBit + i;
                int bitValue = (source[bitIndex >>> 3] >>> (7 - (bitIndex & 7))) & 1;
                value = (value << 1) | bitValue;
            }
            return value;
        }
    }
}
