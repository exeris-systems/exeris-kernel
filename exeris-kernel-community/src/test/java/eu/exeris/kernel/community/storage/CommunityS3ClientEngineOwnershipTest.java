/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.community.transport.CommunityOutboundTls;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * {@link CommunityS3Client} owns its private engine from the moment the engine is built: an engine
 * whose {@code start()} throws is closed before the constructor's failure reaches the caller, and an
 * engine that starts stays open until the client is closed.
 *
 * <p>The engine is a recording stand-in handed to the client's engine-factory constructor, so each
 * case chooses whether {@code start()} throws and counts every {@code close()}.
 */
@DisplayName("Community S3: the client closes the engine it built")
class CommunityS3ClientEngineOwnershipTest {

    private static final CommunityS3Settings SETTINGS = CommunityS3Settings.from(new BlobStorageConfig(
            "https://s3.example.com", BlobStorageConfig.DEFAULT_MAX_SIGNED_URL_TTL,
            Map.of(CommunityS3Settings.BUCKET, "bucket",
                    CommunityS3Settings.ACCESS_KEY, "access",
                    CommunityS3Settings.SECRET_KEY, "secret")));

    @Test
    @DisplayName("an engine whose start throws is closed once, and the start failure propagates")
    void engineWhoseStartThrowsIsClosed() {
        IllegalStateException startFailure = new IllegalStateException("start refused");
        RecordingEngine engine = new RecordingEngine(startFailure, null);

        Throwable thrown = catchThrowable(() -> newClient(engine));

        assertThat(thrown).isSameAs(startFailure);
        assertThat(engine.starts).hasValue(1);
        assertThat(engine.closes)
                .as("no client is returned to close it, so the constructor must")
                .hasValue(1);
    }

    @Test
    @DisplayName("a close that also fails is suppressed on the start failure, not raised instead of it")
    void closeFailureIsSuppressedOnTheStartFailure() {
        IllegalStateException startFailure = new IllegalStateException("start refused");
        IllegalStateException closeFailure = new IllegalStateException("close refused");
        RecordingEngine engine = new RecordingEngine(startFailure, closeFailure);

        Throwable thrown = catchThrowable(() -> newClient(engine));

        assertThat(thrown).isSameAs(startFailure);
        assertThat(thrown.getSuppressed()).containsExactly(closeFailure);
        assertThat(engine.closes).hasValue(1);
    }

    @Test
    @DisplayName("an engine that starts stays open until the client is closed")
    void startedEngineClosesWithTheClient() {
        RecordingEngine engine = new RecordingEngine(null, null);

        CommunityS3Client client = newClient(engine);
        assertThat(engine.starts).hasValue(1);
        assertThat(engine.closes).as("the client owns a started engine").hasValue(0);

        client.close();
        assertThat(engine.closes).hasValue(1);
    }

    @Test
    @DisplayName("the engine is built for the endpoint's authority and scheme")
    void engineIsBuiltForTheEndpoint() {
        AtomicReference<HttpConfig> config = new AtomicReference<>();
        AtomicReference<CommunityOutboundTls> outboundTls = new AtomicReference<>();
        RecordingEngine engine = new RecordingEngine(null, null);

        try (var _ = new CommunityS3Client(SETTINGS,
                BlobStorageConfig.DEFAULT_MAX_SIGNED_URL_TTL, Clock.systemUTC(), (built, tls) -> {
                    config.set(built);
                    outboundTls.set(tls);
                    return engine;
                })) {
            assertThat(config.get().defaultAuthority()).isEqualTo("s3.example.com:443");
            assertThat(outboundTls.get()).isEqualTo(SETTINGS.scheme().outboundTls());
        }
    }

    private static CommunityS3Client newClient(HttpClientEngine engine) {
        return new CommunityS3Client(SETTINGS, BlobStorageConfig.DEFAULT_MAX_SIGNED_URL_TTL,
                Clock.systemUTC(), (config, tls) -> engine);
    }

    /** An engine that counts {@code start()} and {@code close()}, and throws what it is given. */
    private static final class RecordingEngine implements HttpClientEngine {

        private final RuntimeException startFailure;
        private final RuntimeException closeFailure;
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        RecordingEngine(RuntimeException startFailure, RuntimeException closeFailure) {
            this.startFailure = startFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public void start() {
            starts.incrementAndGet();
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public HttpResponse send(HttpRequest request) {
            throw new UnsupportedOperationException("no request is sent in these cases");
        }

        @Override
        public boolean isRunning() {
            return starts.get() > 0 && closes.get() == 0;
        }

        @Override
        public String engineName() {
            return "RecordingEngine";
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
