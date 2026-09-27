/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.testing.http;

import eu.exeris.kernel.community.testkit.http.EmbeddedHttpEngineFixture;
import eu.exeris.kernel.community.testkit.http.EmbeddedHttpEngineFixtures;
import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.core.http.routing.StreamMatch;
import eu.exeris.kernel.core.http.routing.StreamRouteResolver;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequestBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpStreamExchange;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.StreamEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stream route behind the forwarder a generated application binds is served as a stream over a
 * real kernel boot (ADR-043 obligation 7, amendment A1).
 *
 * <p>A generated application builds its router inside the boot callback, after the HTTP subsystem
 * has read {@link HttpKernelProviders#HTTP_SERVER_HANDLER}, so what it binds there is a forwarder over
 * a slot the callback fills later. {@link ForwardingHandler} has that shape: an
 * {@link AtomicReference} slot, {@code 503} while the slot is empty, delegation once it is set, and
 * stream resolution carried through {@link StreamRouteResolver}. The kernel boots with the forwarder
 * bound and never sees the router, so a driver that recognises stream routes only on the router's
 * class serves every stream route here respond-once.
 *
 * <p>Every stream route has a respond-once twin: {@code GET /orders/{id}} also matches
 * {@code /orders/stream}, so a stream request the driver does not resolve as a stream answers
 * {@code 200} with {@code X-Path-Id: stream}. A status code alone therefore proves nothing; every
 * stream case asserts the SSE content type, the absence of the twin's header, and the event itself.
 *
 * <p>The scope cases read the nesting from both sides. Around a stream the kernel binds
 * {@link KernelProviders#MEMORY_ALLOCATOR} and
 * {@link HttpKernelProviders#HTTP_REQUEST_BODY_DECODER_REGISTRY}; a wrapper rebinds the registry to
 * {@link #SENTINEL}. A route that reads the sentinel shows the wrapper's binding is the inner one,
 * and the delegate-only case, whose route reads the kernel's registry, shows the kernel binds one at
 * all, which is what makes the first reading an ordering rather than a coincidence. The wrapper also
 * records whether the allocator is bound where the wrapper itself runs: it binds no allocator, so
 * {@code true} there means the kernel's scope encloses it.
 *
 * <p>The boot case pins what the nesting leaves out: nothing the kernel binds at boot reaches a stream
 * handler. It reads {@link KernelProviders#CURRENT_CONFIG}, bound around the whole boot, and
 * {@link HttpKernelProviders#HTTP_SERVER_ENGINE}, bound by the HTTP subsystem, with one probe in two
 * places: inside the boot, through the fixture's {@code runInKernelScope}, where both must read bound,
 * and on the thread a stream handler runs on, which inherits neither. The first reading is what makes
 * the second one a finding, and the second is why a stream handler receives the providers it needs
 * through its constructor.
 *
 * <p>One boot serves every case, and each case sets the slot before it sends anything. Every request
 * sends {@code Connection: close} and the client reads to end of stream, so an SSE response is read
 * whole once the route has emitted its one event and closed; no case holds a stream open or waits on
 * a timing window.
 *
 * <p>The class carries no tag, so the default build and CI run it; the Community POM's
 * {@code excludedGroups} comment records why it must not take {@code stream-loopback} or
 * {@code integration}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Community real boot: a stream route behind a generated-app forwarder is served as a stream")
class GeneratedAppStreamRouteReachabilityIntegrationTest {

    /** A binding of the wrapper's own, standing in for a tenant. */
    private static final ScopedValue<String> TENANT = ScopedValue.newInstance();

    /** The registry a wrapper binds over the kernel's; compared by identity. */
    private static final HttpRequestBodyDecoderRegistry SENTINEL = (targetType, contentType) -> null;

    private static final String SSE_CONTENT_TYPE = "text/event-stream";
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 5_000;

    private final AtomicReference<HttpHandler> slot = new AtomicReference<>();
    private final HttpRouter router = HttpRouter.builder()
            .route(HttpMethod.GET, "/orders/{id}", GeneratedAppStreamRouteReachabilityIntegrationTest::byId)
            .streamRoute(HttpMethod.GET, "/orders/stream", exchange -> emitAndClose(exchange, "live"))
            .streamRoute(HttpMethod.POST, "/orders/{id}/actions/ship",
                    exchange -> emitAndClose(exchange, exchange.pathParams().getOrDefault("id", "<none>")))
            .streamRoute(HttpMethod.GET, "/orders/scope/stream",
                    exchange -> emitAndClose(exchange, "tenant=" + tenant()
                            + " allocator=" + KernelProviders.MEMORY_ALLOCATOR.isBound()
                            + " registry=" + registryOrigin()))
            .streamRoute(HttpMethod.GET, "/orders/boot/stream",
                    exchange -> emitAndClose(exchange, bootSlots()))
            .build();

    private EmbeddedHttpEngineFixture fixture;
    private int port;

    @BeforeAll
    void bootWithTheForwarderBound() {
        fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture();
        fixture.start(new ForwardingHandler(slot));
        port = fixture.boundPort();
    }

    @AfterAll
    void shutDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The router in the slot: the generated application's own shape
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(a) the router in the slot: an exact stream route answers with an SSE head and its event")
    void exactStreamRouteIsServedAsAStream() {
        slot.set(router);

        Response response = send("GET", "/orders/stream");

        assertServedAsStream(response, "data: live\n\n");
    }

    @Test
    @DisplayName("(a) the router in the slot: a template stream route answers with its captured parameter")
    void templateStreamRouteIsServedWithItsParameter() {
        slot.set(router);

        Response response = send("POST", "/orders/42/actions/ship");

        assertServedAsStream(response, "data: 42\n\n");
    }

    @Test
    @DisplayName("(a2) the router in the slot: a query string takes no part in stream resolution")
    void queryStringTakesNoPartInStreamResolution() {
        slot.set(router);

        Response response = send("GET", "/orders/stream?since=5");

        assertServedAsStream(response, "data: live\n\n");
    }

    @Test
    @DisplayName("(a) the router in the slot: the by-id twin is still served respond-once")
    void byIdTwinIsServedRespondOnce() {
        slot.set(router);

        Response response = send("GET", "/orders/42");

        assertThat(response.statusLine()).as(response.raw()).startsWith("HTTP/1.1 200");
        assertThat(response.header("X-Path-Id")).as(response.raw()).isEqualTo("42");
        assertThat(response.isSse())
                .as("a respond-once answer carries no SSE head: %s", response.raw())
                .isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // Scope: a wrapper's binding reaches the stream, inside the kernel's
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(b) a wrapper that wraps the returned handler: its binding reaches the route, inside the kernel's")
    void wrapperBindingReachesTheStreamRouteInsideTheKernelBindings() {
        slot.set(new BindingWrapper(router, "acme"));

        Response response = send("GET", "/orders/scope/stream");

        // registry=wrapper: the wrapper's registry shadows the kernel's, so the wrapper's binding is
        // the inner one; allocator=true: the kernel's binding still reaches the route through it.
        assertServedAsStream(response, "data: tenant=acme allocator=true registry=wrapper\n\n");
    }

    @Test
    @DisplayName("(b) a wrapper that wraps the returned handler runs inside the kernel's stream scope")
    void wrapperStreamHandlerRunsInsideTheKernelStreamScope() {
        BindingWrapper wrapper = new BindingWrapper(router, "acme");
        slot.set(wrapper);

        send("GET", "/orders/scope/stream");

        Boolean allocatorBoundWhereTheWrapperRan = wrapper.allocatorBoundWhereItRan.get();
        assertThat(allocatorBoundWhereTheWrapperRan)
                .as("ran-guard: the wrapper's stream handler ran")
                .isNotNull();
        assertThat(allocatorBoundWhereTheWrapperRan)
                .as("the wrapper binds no allocator, so a bound one where it runs is the kernel's scope "
                        + "enclosing it")
                .isTrue();
    }

    @Test
    @DisplayName("(b) the same wrapper serves a respond-once route with its binding, inside the kernel's")
    void wrapperBindingReachesTheRespondOnceRoute() {
        slot.set(new BindingWrapper(router, "acme"));

        Response response = send("GET", "/orders/42");

        assertThat(response.statusLine()).as(response.raw()).startsWith("HTTP/1.1 200");
        assertThat(response.header("X-Path-Id")).as(response.raw()).isEqualTo("42");
        assertThat(response.header("X-Tenant")).as(response.raw()).isEqualTo("acme");
        assertThat(response.header("X-Registry")).as(response.raw()).isEqualTo("wrapper");
    }

    @Test
    @DisplayName("(c) a wrapper that only delegates: the route sees the kernel's bindings and none of its own")
    void delegateOnlyWrapperLeavesTheKernelBindingsInPlace() {
        slot.set(new DelegatingWrapper(router));

        Response response = send("GET", "/orders/scope/stream");

        // registry=kernel is the precondition that makes (b)'s registry=wrapper an ordering: the
        // kernel binds a registry around a stream, and the wrapper's binding shadows it.
        assertServedAsStream(response, "data: tenant=none allocator=true registry=kernel\n\n");
    }

    // ---------------------------------------------------------------------------------------------
    // Boot bindings: none reaches a stream handler
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(f) a slot bound at boot does not reach a stream handler")
    void slotBoundAtBootDoesNotReachAStreamHandler() {
        slot.set(router);
        AtomicReference<String> insideTheBoot = new AtomicReference<>();
        fixture.runInKernelScope(() -> insideTheBoot.set(bootSlots()));
        assertThat(insideTheBoot.get())
                .as("control: the same probe inside the boot the engine runs in reads both slots bound")
                .isEqualTo("config=true server=true");

        Response response = send("GET", "/orders/boot/stream");

        assertServedAsStream(response, "data: config=false server=false\n\n");
    }

    // ---------------------------------------------------------------------------------------------
    // Controls: nothing resolves a stream
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(d) a handler that does not resolve streams: the stream path reaches the by-id twin")
    void handlerWithoutResolverServesTheStreamPathRespondOnce() {
        slot.set(router::handle);

        Response response = send("GET", "/orders/stream");

        assertThat(response.statusLine()).as(response.raw()).startsWith("HTTP/1.1 200");
        assertThat(response.header("X-Path-Id"))
                .as("the by-id twin answered, with the id 'stream': %s", response.raw())
                .isEqualTo("stream");
        assertThat(response.isSse())
                .as("no SSE head: %s", response.raw())
                .isFalse();
    }

    @Test
    @DisplayName("(d) a handler that does not resolve streams: a template stream POST answers 404")
    void handlerWithoutResolverAnswers404ForATemplateStream() {
        slot.set(router::handle);

        Response response = send("POST", "/orders/42/actions/ship");

        assertThat(response.statusLine()).as(response.raw()).startsWith("HTTP/1.1 404");
    }

    @Test
    @DisplayName("(e) an empty slot: the forwarder answers 503 and resolves no stream")
    void emptySlotAnswers503() {
        slot.set(null);

        Response response = send("GET", "/orders/stream");

        assertThat(response.statusLine()).as(response.raw()).startsWith("HTTP/1.1 503");
    }

    // ---------------------------------------------------------------------------------------------
    // Routes
    // ---------------------------------------------------------------------------------------------

    private static void byId(HttpExchange exchange) {
        exchange.respond(HttpResponse.noBody(
                HttpStatus.OK,
                exchange.request().version(),
                List.of(new HttpHeader("X-Path-Id", exchange.pathParams().getOrDefault("id", "")),
                        new HttpHeader("X-Tenant", tenant()),
                        new HttpHeader("X-Registry", registryOrigin()))));
    }

    private static void emitAndClose(HttpStreamExchange exchange, String data) {
        exchange.emit(StreamEvent.of(data));
        exchange.close();
    }

    /** One probe for both sides of the boot case, so the two readings cannot differ in what they ask. */
    private static String bootSlots() {
        return "config=" + KernelProviders.CURRENT_CONFIG.isBound()
                + " server=" + HttpKernelProviders.HTTP_SERVER_ENGINE.isBound();
    }

    private static String tenant() {
        return TENANT.isBound() ? TENANT.get() : "none";
    }

    private static String registryOrigin() {
        if (!HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY.isBound()) {
            return "unbound";
        }
        return HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY.get() == SENTINEL ? "wrapper" : "kernel";
    }

    // ---------------------------------------------------------------------------------------------
    // Handlers bound to the slot
    // ---------------------------------------------------------------------------------------------

    /**
     * The handler a generated application binds to {@code HTTP_SERVER_HANDLER}: a slot the boot
     * callback fills once the router is built, {@code 503} until then, and stream resolution carried
     * through to whatever the slot holds.
     */
    private static final class ForwardingHandler implements HttpHandler, StreamRouteResolver {

        private final AtomicReference<HttpHandler> slot;

        ForwardingHandler(AtomicReference<HttpHandler> slot) {
            this.slot = slot;
        }

        @Override
        public void handle(HttpExchange exchange) {
            HttpHandler h = slot.get();
            if (h != null) {
                h.handle(exchange);
            } else {
                exchange.respond(HttpStatus.SERVICE_UNAVAILABLE);
            }
        }

        @Override
        public StreamMatch resolveStream(HttpMethod method, String path) {
            return slot.get() instanceof StreamRouteResolver resolver
                    ? resolver.resolveStream(method, path)
                    : null;
        }
    }

    /**
     * The wrapper recipe from {@link StreamRouteResolver}: it delegates, wraps the handler it gets
     * back, and forwards the parameters. It binds a tenant and the {@link #SENTINEL} registry around
     * both halves, and records whether the allocator is bound where its stream wrapper runs.
     */
    private static final class BindingWrapper implements HttpHandler, StreamRouteResolver {

        private final HttpRouter router;
        private final String tenant;
        private final AtomicReference<Boolean> allocatorBoundWhereItRan = new AtomicReference<>();

        BindingWrapper(HttpRouter router, String tenant) {
            this.router = router;
            this.tenant = tenant;
        }

        @Override
        public void handle(HttpExchange exchange) {
            bindings().run(() -> router.handle(exchange));
        }

        @Override
        public StreamMatch resolveStream(HttpMethod method, String path) {
            StreamMatch match = router.resolveStream(method, path);
            if (match == null) {
                return null;
            }
            HttpStreamHandler route = match.handler();
            return new StreamMatch(exchange -> {
                allocatorBoundWhereItRan.set(KernelProviders.MEMORY_ALLOCATOR.isBound());
                bindings().run(() -> route.handle(exchange));
            }, match.params());
        }

        private ScopedValue.Carrier bindings() {
            return ScopedValue.where(TENANT, tenant)
                    .where(HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY, SENTINEL);
        }
    }

    /** A wrapper with nothing to bind: it returns its delegate's answers unchanged. */
    private static final class DelegatingWrapper implements HttpHandler, StreamRouteResolver {

        private final HttpRouter router;

        DelegatingWrapper(HttpRouter router) {
            this.router = router;
        }

        @Override
        public void handle(HttpExchange exchange) {
            router.handle(exchange);
        }

        @Override
        public StreamMatch resolveStream(HttpMethod method, String path) {
            return router.resolveStream(method, path);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Client
    // ---------------------------------------------------------------------------------------------

    private static void assertServedAsStream(Response response, String expectedBody) {
        assertThat(response.statusLine()).as(response.raw()).startsWith("HTTP/1.1 200");
        assertThat(response.header("Content-Type"))
                .as("an SSE head: %s", response.raw())
                .isNotNull()
                .startsWith(SSE_CONTENT_TYPE);
        assertThat(response.header("X-Path-Id"))
                .as("the by-id twin did not answer: %s", response.raw())
                .isNull();
        assertThat(response.body()).as(response.raw()).isEqualTo(expectedBody);
    }

    /** Sends one request with {@code Connection: close} and reads the response to end of stream. */
    private Response send(String method, String target) {
        String request = method + " " + target + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + ("POST".equals(method) ? "Content-Length: 0\r\n" : "")
                + "Connection: close\r\n\r\n";
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] chunk = new byte[1024];
            int read = in.read(chunk);
            while (read >= 0) {
                sink.write(chunk, 0, read);
                read = in.read(chunk);
            }
        } catch (SocketTimeoutException timeout) {
            throw new AssertionError("no end of stream within " + READ_TIMEOUT_MILLIS + " ms for "
                    + method + " " + target + "; read so far: "
                    + sink.toString(StandardCharsets.UTF_8), timeout);
        } catch (IOException exception) {
            throw new AssertionError("exchange failed for " + method + " " + target + "; read so far: "
                    + sink.toString(StandardCharsets.UTF_8), exception);
        }
        return Response.parse(sink.toString(StandardCharsets.UTF_8));
    }

    /** A raw HTTP/1.1 response split into its status line, headers (case-insensitive) and body. */
    private record Response(String raw, String statusLine, Map<String, String> headers, String body) {

        static Response parse(String raw) {
            int headEnd = raw.indexOf("\r\n\r\n");
            assertThat(headEnd).as("a complete response head in: %s", raw).isNotNegative();
            String[] headLines = raw.substring(0, headEnd).split("\r\n");
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 1; i < headLines.length; i++) {
                int colon = headLines[i].indexOf(':');
                if (colon > 0) {
                    headers.put(headLines[i].substring(0, colon).trim(), headLines[i].substring(colon + 1).trim());
                }
            }
            return new Response(raw, headLines[0], headers, raw.substring(headEnd + 4));
        }

        String header(String name) {
            return headers.get(name);
        }

        boolean isSse() {
            String contentType = headers.get("Content-Type");
            return contentType != null && contentType.startsWith(SSE_CONTENT_TYPE);
        }
    }
}
