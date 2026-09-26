/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.persistence.CommunityPersistenceProvider;
import eu.exeris.kernel.community.persistence.PersistenceSessionBox;
import eu.exeris.kernel.core.security.SecurityInterceptor;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.security.StorageContextMissingException;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@code permitAll()} route that reaches persistence gets a connection scoped to the system
 * context, and the dispatcher reports it: {@code eu.exeris.kernel.security.UnscopedRequestSession}.
 *
 * <p>The behaviour is asserted alongside the report, because the report is only honest if the
 * behaviour is what it describes: the connection interceptor sees {@code GLOBAL}, nothing throws,
 * and the strict accessor still refuses on the same route. Each silent case is dispatched ahead of a
 * control that does emit, on the same recording, so waiting for the control is also waiting past
 * the point where the silent case's event would have been delivered.
 */
@DisplayName("Community: a request session acquired with no StorageContext bound is recorded")
class CommunityUnscopedRequestSessionEventTest {

    private static final String EVENT_NAME = "eu.exeris.kernel.security.UnscopedRequestSession";
    private static final String CONTROL_PATH = "/control-public-read";
    private static final String TOKEN = "Bearer test-token";

    // A real allocator: the authenticated leg copies the bearer token into a loaned buffer.
    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private final List<StorageContext> interceptorSaw = new CopyOnWriteArrayList<>();
    private PersistenceEngine engine;

    private static final class CapturingExchange implements HttpExchange {
        private final HttpRequest request;
        private final AtomicReference<HttpResponse> captured = new AtomicReference<>();

        private CapturingExchange(HttpRequest request) {
            this.request = request;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public void respond(HttpResponse response) {
            captured.compareAndSet(null, response);
        }
    }

    /**
     * Authenticates every token and binds the given context: a tenant principal for a context that
     * names a tenant, a tenant-less one for the system context.
     */
    private static final class TenantProvider implements SecurityProvider {
        private final ImmutableStorageContext storage;

        private TenantProvider(ImmutableStorageContext storage) {
            this.storage = storage;
        }

        @Override
        public String providerId() {
            return "tenant-test-provider";
        }

        @Override
        public String providerName() {
            return "Tenant Test Provider";
        }

        @Override
        public AuthenticationResult authenticate(LoanedBuffer rawToken) {
            ImmutablePrincipal principal = storage.isolationKey().isPresent()
                    ? ImmutablePrincipal.ofTenant(UUID.randomUUID(), UUID.randomUUID(), Set.of())
                    : ImmutablePrincipal.ofScopes(UUID.randomUUID(), Set.of());
            return new AuthenticationResult(principal, storage);
        }

        @Override
        public StorageContext systemStorageContext() {
            return ImmutableStorageContext.GLOBAL;
        }
    }

    @BeforeEach
    void startEngine() {
        engine = new CommunityPersistenceProvider().createEngine(new PersistenceConfig(
                "jdbc:h2:mem:community_unscoped_session_" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "", 4, 1, 5_000L, 60_000L, 600_000L,
                false, false, false, 0, Map.of()));
        engine.registerInterceptor((connection, storageContext) -> interceptorSaw.add(storageContext));
    }

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    private HttpHandler readsThroughTheAmbientContext() {
        return exchange -> {
            try (PersistenceConnection _ = engine.openConnection()) {
                exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
            }
        };
    }

    private static HttpResponse dispatch(CommunityHttpRequestDispatcher dispatcher, String path,
                                         List<HttpHeader> headers, HttpHandler handler) {
        HttpRequest request = new HttpRequest(HttpMethod.GET, path, HttpVersion.HTTP_1_1, headers, null);
        CapturingExchange exchange = new CapturingExchange(request);
        dispatcher.dispatch(request, exchange, handler);
        return exchange.captured.get();
    }

    private CommunityHttpRequestDispatcher dispatcherFor(RouteRequirement requirement,
                                                         SecurityInterceptor interceptor) {
        return new CommunityHttpRequestDispatcher(
                ALLOCATOR, interceptor, engine, null, (method, path) -> requirement);
    }

    /**
     * Runs {@code silentCase}, then the control, and returns every path the event reported. The
     * control is last, so a list that holds only the control means the silent case emitted nothing.
     */
    private List<String> pathsReportedAround(Runnable silentCase) throws InterruptedException {
        List<String> paths = new CopyOnWriteArrayList<>();
        CountDownLatch controlArrived = new CountDownLatch(1);

        try (RecordingStream stream = new RecordingStream()) {
            stream.enable(EVENT_NAME);
            stream.onEvent(EVENT_NAME, event -> {
                paths.add(event.getString("path"));
                if (CONTROL_PATH.equals(event.getString("path"))) {
                    controlArrived.countDown();
                }
            });
            stream.startAsync();

            silentCase.run();
            dispatch(dispatcherFor(RouteRequirement.permitAll(), null), CONTROL_PATH, List.of(),
                    readsThroughTheAmbientContext());

            assertThat(controlArrived.await(20, TimeUnit.SECONDS))
                    .as("the control must arrive, or this test cannot tell silence from deafness")
                    .isTrue();
        }
        return paths;
    }

    /** Runs {@code reportedCase} and returns the first event the recording delivers for it. */
    private static RecordedEvent firstEventFrom(Runnable reportedCase) throws InterruptedException {
        AtomicReference<RecordedEvent> seen = new AtomicReference<>();
        CountDownLatch arrived = new CountDownLatch(1);

        try (RecordingStream stream = new RecordingStream()) {
            stream.enable(EVENT_NAME);
            stream.onEvent(EVENT_NAME, event -> {
                if (seen.compareAndSet(null, event)) {
                    arrived.countDown();
                }
            });
            stream.startAsync();

            reportedCase.run();

            assertThat(arrived.await(20, TimeUnit.SECONDS))
                    .as("a request session with no tenant scope must reach the recording")
                    .isTrue();
        }
        return seen.get();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("permitAll + openConnection(): GLOBAL reaches the interceptor, nothing throws, and it is recorded")
    void permitAllPersistenceIsRecorded() throws Exception {
        AtomicReference<HttpResponse> response = new AtomicReference<>();

        RecordedEvent event = firstEventFrom(() -> response.set(dispatch(
                dispatcherFor(RouteRequirement.permitAll(), null), "/public-read", List.of(),
                readsThroughTheAmbientContext())));

        assertThat(response.get().status())
                .as("the handler completed: no exception on the ambient-context path, by contract")
                .isEqualTo(HttpStatus.OK);
        assertThat(interceptorSaw).containsExactly(ImmutableStorageContext.GLOBAL);

        assertThat(event.getString("method")).isEqualTo("GET");
        assertThat(event.getString("path")).isEqualTo("/public-read");
        assertThat(event.getBoolean("readOnly")).isTrue();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("permitAll that never touches persistence is not recorded")
    void permitAllWithoutPersistenceIsSilent() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();

        List<String> paths = pathsReportedAround(() -> dispatch(
                dispatcherFor(RouteRequirement.permitAll(), null), "/public-static", List.of(),
                exchange -> {
                    ran.set(true);
                    exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
                }));

        assertThat(ran).as("the silent case must actually run").isTrue();
        assertThat(paths).containsExactly(CONTROL_PATH);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("permitAll whose handler binds a tenant STORAGE_CONTEXT itself is not recorded")
    void selfScopedPermitAllIsSilent() throws Exception {
        ImmutableStorageContext tenant = ImmutableStorageContext.shared("tenant-self");

        List<String> paths = pathsReportedAround(() -> dispatch(
                dispatcherFor(RouteRequirement.permitAll(), null), "/public-self-scoped", List.of(),
                exchange -> ScopedValue.where(KernelProviders.STORAGE_CONTEXT, tenant)
                        .run(() -> readsThroughTheAmbientContext().handle(exchange))));

        assertThat(interceptorSaw)
                .as("the handler reached persistence under its own context, then the control under GLOBAL")
                .containsExactly(tenant, ImmutableStorageContext.GLOBAL);
        assertThat(paths).containsExactly(CONTROL_PATH);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("permitAll whose handler passes a tenant context explicitly is not recorded, slot unbound")
    void explicitTenantContextIsSilent() throws Exception {
        ImmutableStorageContext tenant = ImmutableStorageContext.shared("tenant-explicit");

        List<String> paths = pathsReportedAround(() -> dispatch(
                dispatcherFor(RouteRequirement.permitAll(), null), "/public-explicit-tenant", List.of(),
                exchange -> {
                    try (PersistenceConnection _ = engine.openConnection(tenant)) {
                        exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
                    }
                }));

        assertThat(interceptorSaw)
                .as("the connection was configured for the tenant the handler named")
                .containsExactly(tenant, ImmutableStorageContext.GLOBAL);
        assertThat(paths)
                .as("what reaches the database is the tenant context; an unbound slot is not a report")
                .containsExactly(CONTROL_PATH);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a handler that chooses the system context is recorded, even with a tenant bound")
    void explicitSystemContextIsRecorded() throws Exception {
        ImmutableStorageContext tenant = ImmutableStorageContext.shared("tenant-bound");

        RecordedEvent event = firstEventFrom(() -> dispatch(
                dispatcherFor(RouteRequirement.permitAll(), null), "/public-explicit-system", List.of(),
                exchange -> ScopedValue.where(KernelProviders.STORAGE_CONTEXT, tenant).run(() -> {
                    try (PersistenceConnection _ = engine.openConnection(ImmutableStorageContext.system())) {
                        exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
                    }
                })));

        assertThat(interceptorSaw).containsExactly(ImmutableStorageContext.GLOBAL);
        assertThat(event.getString("path")).isEqualTo("/public-explicit-system");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("an authenticated route whose provider resolves the system context is not recorded: "
            + "that is a tenant-less deployment's own answer, not a public route")
    void authenticatedSystemScopeIsSilent() throws Exception {
        SecurityInterceptor interceptor =
                new SecurityInterceptor(new TenantProvider(ImmutableStorageContext.GLOBAL));
        AtomicReference<HttpResponse> response = new AtomicReference<>();

        List<String> paths = pathsReportedAround(() -> response.set(dispatch(
                dispatcherFor(RouteRequirement.authenticated(), interceptor), "/authenticated-system",
                List.of(new HttpHeader("Authorization", TOKEN)), readsThroughTheAmbientContext())));

        assertThat(response.get().status())
                .as("the authenticated handler must actually run, or its silence proves nothing")
                .isEqualTo(HttpStatus.OK);
        assertThat(interceptorSaw)
                .as("its session was scoped to the system context, exactly as the control's")
                .containsExactly(ImmutableStorageContext.GLOBAL, ImmutableStorageContext.GLOBAL);
        assertThat(paths)
                .as("only the permitAll control may emit")
                .containsExactly(CONTROL_PATH);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("an authenticated route bound to a tenant is not recorded")
    void authenticatedTenantRouteIsSilent() throws Exception {
        ImmutableStorageContext tenant = ImmutableStorageContext.shared("tenant-auth");
        SecurityInterceptor interceptor = new SecurityInterceptor(new TenantProvider(tenant));
        AtomicReference<HttpResponse> response = new AtomicReference<>();

        List<String> paths = pathsReportedAround(() -> response.set(dispatch(
                dispatcherFor(RouteRequirement.authenticated(), interceptor), "/authenticated-read",
                List.of(new HttpHeader("Authorization", TOKEN)), readsThroughTheAmbientContext())));

        assertThat(response.get().status())
                .as("the authenticated handler must actually run, or its silence proves nothing")
                .isEqualTo(HttpStatus.OK);
        assertThat(interceptorSaw).containsExactly(tenant, ImmutableStorageContext.GLOBAL);
        assertThat(paths).containsExactly(CONTROL_PATH);
    }

    @Test
    @DisplayName("the strict accessor still refuses inside a permitAll handler: no context is bound there")
    void strictAccessorStillThrowsOnPermitAll() {
        AtomicReference<StorageContextMissingException> thrown = new AtomicReference<>();

        dispatch(dispatcherFor(RouteRequirement.permitAll(), null), "/public-strict", List.of(),
                exchange -> {
                    try {
                        KernelProviders.storageContext();
                    } catch (StorageContextMissingException e) {
                        thrown.set(e);
                    }
                    exchange.respond(HttpResponse.noBody(HttpStatus.OK, exchange.request().version()));
                });

        assertThat(thrown.get())
                .as("storageContext() must refuse on a public route; binding GLOBAL there would silence it")
                .isNotNull();
        assertThat(thrown.get().errorCode()).isEqualTo("EX-SEC-2004");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a failure while releasing the session does not suppress the report")
    void releaseFailureDoesNotHideTheEvent() throws Exception {
        PersistenceConnection failsOnClose = mock(PersistenceConnection.class);
        doThrow(new IllegalStateException("release failed")).when(failsOnClose).close();
        PersistenceEngine failingEngine = mock(PersistenceEngine.class);
        when(failingEngine.canServiceRequest()).thenReturn(true);
        when(failingEngine.openConnection()).thenAnswer(invocation -> {
            PersistenceSessionBox box = PersistenceSessionBox.currentOrNull();
            return box.requestScopedConnection(box.getOrAcquireIfScopeMatches(
                    "shared", ImmutableStorageContext.GLOBAL, () -> failsOnClose));
        });
        CommunityHttpRequestDispatcher dispatcher = new CommunityHttpRequestDispatcher(
                ALLOCATOR, null, failingEngine, null, (method, path) -> RouteRequirement.permitAll());
        HttpRequest request = new HttpRequest(
                HttpMethod.GET, "/public-release-fails", HttpVersion.HTTP_1_1, List.of(), null);
        CapturingExchange exchange = new CapturingExchange(request);
        HttpHandler handler = ex -> {
            try (PersistenceConnection _ = failingEngine.openConnection()) {
                ex.respond(HttpResponse.noBody(HttpStatus.OK, request.version()));
            }
        };
        RecordedEvent event = firstEventFrom(() ->
                assertThatThrownBy(() -> dispatcher.dispatch(request, exchange, handler))
                        .as("the release failure propagates; the test is about what was recorded before it")
                        .isInstanceOf(IllegalStateException.class));

        assertThat(event.getString("path"))
                .as("the report is emitted before release, so a throwing release cannot hide it")
                .isEqualTo("/public-release-fails");
    }
}
