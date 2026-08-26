/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.http.tck;

import eu.exeris.kernel.spi.http.*;

import java.util.Objects;

public final class CoreHttpProviderFixture implements HttpProvider {

    @Override
    public HttpServerEngine createServerEngine(HttpConfig config) {
        return new CoreHttpServerEngineFixture(Objects.requireNonNull(config, "config must not be null"));
    }

    @Override
    public HttpClientEngine createClientEngine(HttpConfig config) {
        return new CoreHttpClientEngineFixture(Objects.requireNonNull(config, "config must not be null"));
    }

    @Override
    public String providerId() {
        return "core-test-http";
    }

    @Override
    public String providerName() {
        return "CoreTestHttpProvider";
    }

    @Override
    public int priority() {
        return 0;
    }

    static final class CoreHttpServerEngineFixture implements HttpServerEngine {

        private final HttpConfig config;
        private boolean running;
        private boolean closed;
        private HttpHandler handler;

        CoreHttpServerEngineFixture(HttpConfig config) {
            this.config = config;
        }

        @Override
        public void setHandler(HttpHandler handler) {
            Objects.requireNonNull(handler, "handler must not be null");
            if (running || closed) {
                throw new IllegalStateException("Engine cannot accept handler in current state");
            }
            this.handler = handler;
        }

        @Override
        public void start() {
            if (closed || running) {
                throw new IllegalStateException("Engine cannot be started in current state");
            }
            if (handler == null) {
                throw new IllegalStateException("Handler must be set before start");
            }
            running = true;
        }

        @Override
        public void stop() {
            if (!running) {
                throw new IllegalStateException("Engine is not running");
            }
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public String engineName() {
            return "core-test-http-server";
        }

        @Override
        public void close() {
            running = false;
            closed = true;
        }

        HttpConfig config() {
            return config;
        }
    }

    static final class CoreHttpClientEngineFixture implements HttpClientEngine {

        private final HttpConfig config;
        private boolean running;
        private boolean closed;

        CoreHttpClientEngineFixture(HttpConfig config) {
            this.config = config;
        }

        @Override
        public void start() {
            if (closed || running) {
                throw new IllegalStateException("Engine cannot be started in current state");
            }
            running = true;
        }

        @Override
        public HttpResponse send(HttpRequest request) {
            Objects.requireNonNull(request, "request must not be null");
            if (!running || closed) {
                throw new IllegalStateException("Engine is not running");
            }
            return HttpResponse.noBody(HttpStatus.OK, HttpVersion.HTTP_1_1);
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public String engineName() {
            return "core-test-http-client";
        }

        @Override
        public void close() {
            running = false;
            closed = true;
        }

        HttpConfig config() {
            return config;
        }
    }

    static final class CoreHttpExchangeFixture implements HttpExchange {

        private final HttpRequest request;
        private boolean responded;
        private HttpResponse response;

        CoreHttpExchangeFixture(HttpRequest request) {
            this.request = Objects.requireNonNull(request, "request must not be null");
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public void respond(HttpResponse response) {
            Objects.requireNonNull(response, "response must not be null");
            if (responded) {
                throw new IllegalStateException("Response already sent");
            }
            this.response = response;
            this.responded = true;
        }

        boolean responded() {
            return responded;
        }

        HttpResponse response() {
            return response;
        }
    }
}
