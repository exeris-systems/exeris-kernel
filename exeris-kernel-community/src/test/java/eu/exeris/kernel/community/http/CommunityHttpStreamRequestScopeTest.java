/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
}
