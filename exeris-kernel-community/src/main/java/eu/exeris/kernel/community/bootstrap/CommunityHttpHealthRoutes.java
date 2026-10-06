/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.core.persistence.TransactionOrchestrator;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

import java.util.List;
import java.util.function.Supplier;

/**
 * Builds the default {@link HttpHandler} {@link CommunityHttpSubsystem} installs when the
 * application boots the kernel without supplying its own {@code HTTP_SERVER_HANDLER} —
 * {@code /health}, {@code /health/live}, {@code /health/ready} and {@code /db/ping}, routed through
 * {@link HttpRouter}.
 *
 * <p>{@code /db/ping} runs {@code SELECT 1} through a {@link TransactionOrchestrator} built over the
 * supplied {@link PersistenceEngine} and reports the outcome in the
 * {@value #HEADER_PERSISTENCE} response header ({@code "unbound"} when no engine was supplied,
 * {@code "ready"}, {@code "unexpected-result"}, or {@code "error"} on a thrown
 * {@link RuntimeException}) — a real round trip to the database, not a liveness fiction.
 */
final class CommunityHttpHealthRoutes {

    private static final String HEADER_PERSISTENCE = "X-Exeris-Persistence";

    private CommunityHttpHealthRoutes() {
    }

    /* default */ static HttpHandler healthHandler(Supplier<HttpStatus> readinessStatusSupplier,
                                                   PersistenceEngine persistenceEngine) {
        TransactionalExecutor transactionalExecutor = persistenceEngine == null
            ? null
            : new TransactionOrchestrator(persistenceEngine);
        return HttpRouter.builder()
            .route(HttpMethod.GET, "/health",
                e -> e.respond(HttpResponse.noBody(HttpStatus.OK, e.request().version())))
            .route(HttpMethod.GET, "/health/live",
                e -> e.respond(HttpResponse.noBody(HttpStatus.OK, e.request().version())))
            .route(HttpMethod.GET, "/health/ready",
                e -> e.respond(HttpResponse.noBody(readinessStatusSupplier.get(), e.request().version())))
            .route(HttpMethod.GET, "/db/ping",
                e -> e.respond(persistenceProbe(transactionalExecutor, e.request().version())))
            .notFound(e -> e.respond(HttpResponse.noBody(HttpStatus.NOT_FOUND, e.request().version())))
            .build();
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static HttpResponse persistenceProbe(TransactionalExecutor transactionalExecutor,
                                                 HttpVersion version) {
        if (transactionalExecutor == null) {
            return HttpResponse.noBody(
                HttpStatus.SERVICE_UNAVAILABLE,
                version,
                List.of(new HttpHeader(HEADER_PERSISTENCE, "unbound"))
            );
        }

        try {
            Integer resultValue = transactionalExecutor.query(connection -> {
                try (QueryResult result = connection.executeQuery("SELECT 1")) {
                    if (!result.next()) {
                        return null;
                    }
                    return result.row().getInt(0);
                }
            });
            if (resultValue != null && resultValue == 1) {
                return HttpResponse.noBody(
                    HttpStatus.OK,
                    version,
                    List.of(new HttpHeader(HEADER_PERSISTENCE, "ready"))
                );
            }
            return HttpResponse.noBody(
                HttpStatus.INTERNAL_SERVER_ERROR,
                version,
                List.of(new HttpHeader(HEADER_PERSISTENCE, "unexpected-result"))
            );
        } catch (RuntimeException _) {
            return HttpResponse.noBody(
                HttpStatus.INTERNAL_SERVER_ERROR,
                version,
                List.of(new HttpHeader(HEADER_PERSISTENCE, "error"))
            );
        }
    }
}
