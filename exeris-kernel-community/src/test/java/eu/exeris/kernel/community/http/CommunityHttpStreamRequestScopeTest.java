/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.security.SecurityInterceptor;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a stream open gets bound on the reactor thread — and what it deliberately does not.
 *
 * <p>The respond-once path re-establishes its bindings in {@code handleWithinRequestSession};
 * {@code dispatchStream} established none, so a streaming handler ran with no allocator, no decoder
 * registry and no session. The allocator matters most: a per-action streaming route is a POST with a
 * body, which is the same combination that made write-over-HTTP answer 400 on the respond-once path.
 */
@DisplayName("Community: request-scoped bindings for a stream open")
class CommunityHttpStreamRequestScopeTest {

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private static final String BEARER_TOKEN = "Bearer stream-token";
    private static final ImmutableStorageContext STREAM_TENANT = ImmutableStorageContext.shared("tenant-stream");

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    private static HttpRequest streamRequest() {
        return new HttpRequest(HttpMethod.GET, "/era/stream", HttpVersion.HTTP_1_1, List.of(), null);
    }

    /** No route policy and no interceptor: the requirement is permit-all, so the open is admitted. */
    private static CommunityHttpRequestDispatcher dispatcher() {
        return new CommunityHttpRequestDispatcher(ALLOCATOR, null, null, null, null);
    }

    @Test
    @DisplayName("the allocator is bound for the stream open, and is the engine's own")
    void allocatorIsBoundForTheStreamOpen() {
        AtomicBoolean bound = new AtomicBoolean();
        AtomicBoolean sameInstance = new AtomicBoolean();

        assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound())
                .as("precondition: unbound on this thread before the open")
                .isFalse();

        dispatcher().dispatchStream(streamRequest(), () -> {
            throw new AssertionError("permit-all must not deny");
        }, () -> {
            bound.set(KernelProviders.MEMORY_ALLOCATOR.isBound());
            if (bound.get()) {
                sameInstance.set(KernelProviders.MEMORY_ALLOCATOR.get() == ALLOCATOR);
            }
        });

        assertThat(bound.get())
                .as("a stream handler must be able to resolve the allocator, as a respond-once one can")
                .isTrue();
        assertThat(sameInstance.get())
                .as("and it must be the engine's allocator, not a second one")
                .isTrue();
        assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound())
                .as("the binding does not outlive the open")
                .isFalse();
    }

    @Test
    @DisplayName("the persistence session is deliberately NOT bound — a stream would hold it for its lifetime")
    void persistenceSessionIsDeliberatelyAbsent() {
        // Not an oversight, and pinned so it cannot become one. A stream handler runs inline for the
        // whole life of the stream, and PersistenceSessionBox lazily takes a pooled JDBC connection
        // and holds it for the scope's duration -- so binding it here would let one read inside a live
        // feed pin a connection for as long as the client stays connected. A streaming handler that
        // needs the database wants a short-lived session per emit, which is a design, not a binding
        // copied across from the respond-once path.
        AtomicBoolean sessionBound = new AtomicBoolean(true);

        dispatcher().dispatchStream(streamRequest(), () -> {
            throw new AssertionError("permit-all must not deny");
        }, () -> sessionBound.set(CommunityHttpRequestProcessor.REQUEST_SESSION.isBound()));

        assertThat(sessionBound.get())
                .as("binding the request session here would outlive any sane connection hold")
                .isFalse();
    }

    @Test
    @DisplayName("an authenticated stream opens inside the principal and storage the interceptor bound")
    void authenticatedStreamOpenRunsInsideTheInterceptorBindings() {
        AtomicReference<Boolean> principalBound = new AtomicReference<>();
        AtomicReference<StorageContext> storageSeen = new AtomicReference<>();

        authenticatingDispatcher(RouteRequirement.authenticated()).dispatchStream(
                streamRequestWithToken(), () -> {
                    throw new AssertionError("a valid token on an authenticated route must not be denied");
                }, () -> {
                    principalBound.set(KernelProviders.PRINCIPAL_CONTEXT.isBound());
                    storageSeen.set(KernelProviders.STORAGE_CONTEXT.isBound()
                            ? KernelProviders.STORAGE_CONTEXT.get()
                            : null);
                });

        assertThat(principalBound.get()).as("ran-guard: the stream opened").isNotNull();
        assertThat(principalBound.get())
                .as("the stream opens inside the scope the interceptor established, so its handler "
                        + "sees the principal a respond-once handler on the same route would")
                .isTrue();
        assertThat(storageSeen.get())
                .as("and the storage context the provider resolved for it")
                .isSameAs(STREAM_TENANT);
        assertThat(KernelProviders.PRINCIPAL_CONTEXT.isBound())
                .as("the binding does not outlive the open")
                .isFalse();
    }

    @Test
    @DisplayName("a permit-all stream opens with no principal, even when the request carries a valid token")
    void permitAllStreamOpenRunsOutsideEveryPrincipal() {
        AtomicReference<Boolean> principalBound = new AtomicReference<>();
        AtomicReference<Boolean> storageBound = new AtomicReference<>();

        authenticatingDispatcher(RouteRequirement.permitAll()).dispatchStream(
                streamRequestWithToken(), () -> {
                    throw new AssertionError("permit-all must not deny");
                }, () -> {
                    principalBound.set(KernelProviders.PRINCIPAL_CONTEXT.isBound());
                    storageBound.set(KernelProviders.STORAGE_CONTEXT.isBound());
                });

        assertThat(principalBound.get()).as("ran-guard: the stream opened").isNotNull();
        assertThat(principalBound.get())
                .as("a permit-all route runs no interceptor, so a token the provider would accept "
                        + "still binds no principal")
                .isFalse();
        assertThat(storageBound.get()).as("and no storage context").isFalse();
    }

    private static HttpRequest streamRequestWithToken() {
        return new HttpRequest(HttpMethod.GET, "/era/stream", HttpVersion.HTTP_1_1,
                List.of(new HttpHeader("Authorization", BEARER_TOKEN)), null);
    }

    /** A real interceptor over a provider that accepts every token, and a policy answering {@code requirement}. */
    private static CommunityHttpRequestDispatcher authenticatingDispatcher(RouteRequirement requirement) {
        return new CommunityHttpRequestDispatcher(
                ALLOCATOR, new SecurityInterceptor(new AcceptingProvider()), null, null,
                (method, path) -> requirement);
    }

    /** Authenticates every token as a tenant principal bound to {@link #STREAM_TENANT}. */
    private static final class AcceptingProvider implements SecurityProvider {

        @Override
        public String providerId() {
            return "stream-scope-test-provider";
        }

        @Override
        public String providerName() {
            return "Stream Scope Test Provider";
        }

        @Override
        public AuthenticationResult authenticate(LoanedBuffer rawToken) {
            return new AuthenticationResult(
                    ImmutablePrincipal.ofTenant(UUID.randomUUID(), UUID.randomUUID(), Set.of()),
                    STREAM_TENANT);
        }

        @Override
        public StorageContext systemStorageContext() {
            return ImmutableStorageContext.GLOBAL;
        }
    }
}
