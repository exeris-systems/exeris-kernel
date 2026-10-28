/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import java.util.Objects;

/**
 * The pair of dispatchers one HTTP request loop runs its requests through: the respond-once dispatcher
 * and the stream dispatcher. They are built together, share one allocator and one workload gate, and
 * travel together to the HTTP/2 session.
 *
 * @param request the respond-once request dispatcher
 * @param stream  the stream dispatcher
 */
/* default */ value record CommunityHttpDispatchers(CommunityHttpRequestDispatcher request,
                                              CommunityHttpStreamDispatcher stream) {

    /** Rejects a missing dispatcher. */
    /* default */ CommunityHttpDispatchers {
        Objects.requireNonNull(request, "request dispatcher must not be null");
        Objects.requireNonNull(stream, "stream dispatcher must not be null");
    }
}
