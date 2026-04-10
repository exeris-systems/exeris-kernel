/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.http.CommunityHttpProvider;
import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.core.persistence.TransactionOrchestrator;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Community: Persistence bootstrap integration")
class CommunityPersistenceBootstrapIntegrationTest {

    @Test
    @DisplayName("KernelBootstrap with persistence subsystem binds engine and executes SELECT 1")
    void persistenceSubsystemBindsEngineAndExecutesSimpleQuery() throws Exception {
        String databaseName = "exeris_bootstrap_" + System.nanoTime();
        String previousJdbcUrl = System.getProperty("exeris.persistence.jdbcUrl");
        String previousUsername = System.getProperty("exeris.persistence.username");
        String previousPassword = System.getProperty("exeris.persistence.password");
        String previousMaxPoolSize = System.getProperty("exeris.persistence.maxPoolSize");

        System.setProperty("exeris.persistence.jdbcUrl", h2Url(databaseName));
        System.setProperty("exeris.persistence.username", "sa");
        System.setProperty("exeris.persistence.password", "");
        System.setProperty("exeris.persistence.maxPoolSize", "4");

        try {
            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("persistence"))
                    .build()
                    .boot(() -> {
                        assertThat(KernelProviders.PERSISTENCE_PROVIDER.isBound()).isTrue();
                        assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isTrue();

                        try (PersistenceConnection connection = KernelProviders.persistenceEngine().openConnection();
                             QueryResult result = connection.executeQuery("SELECT 1")) {
                            assertThat(result.next()).isTrue();
                            assertThat(result.row().getInt(0)).isEqualTo(1);
                        }
                    });
        } finally {
            restoreProperty("exeris.persistence.jdbcUrl", previousJdbcUrl);
            restoreProperty("exeris.persistence.username", previousUsername);
            restoreProperty("exeris.persistence.password", previousPassword);
            restoreProperty("exeris.persistence.maxPoolSize", previousMaxPoolSize);
        }

        assertThat(KernelProviders.PERSISTENCE_PROVIDER.isBound()).isFalse();
        assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isFalse();
    }

    @Test
    @DisplayName("KernelBootstrap with HTTP and persistence serves /db/roundtrip over real TCP")
    void httpAndPersistenceServeDatabaseRoundtripOverRealTcp() throws Exception {
        int port = nextFreePort();
        String databaseName = "exeris_http_bootstrap_" + System.nanoTime();
        String roundtripKey = "key-" + System.nanoTime();
        String roundtripValue = "value-" + System.nanoTime();

        String previousHttpMode = System.getProperty("exeris.http.mode");
        String previousHttpBindHost = System.getProperty("exeris.http.bindHost");
        String previousHttpPort = System.getProperty("exeris.http.port");
        String previousJdbcUrl = System.getProperty("exeris.persistence.jdbcUrl");
        String previousUsername = System.getProperty("exeris.persistence.username");
        String previousPassword = System.getProperty("exeris.persistence.password");
        String previousMaxPoolSize = System.getProperty("exeris.persistence.maxPoolSize");

        AtomicBoolean boundInside = new AtomicBoolean(false);

        System.setProperty("exeris.http.mode", "SERVER");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));
        System.setProperty("exeris.persistence.jdbcUrl", h2Url(databaseName));
        System.setProperty("exeris.persistence.username", "sa");
        System.setProperty("exeris.persistence.password", "");
        System.setProperty("exeris.persistence.maxPoolSize", "4");

        try {
            AtomicReference<TransactionOrchestrator> txRef = new AtomicReference<>();
            ScopedValue.where(HttpKernelProviders.HTTP_SERVER_HANDLER, buildRoundtripHandler(txRef))
                    .call(() -> {
                        KernelBootstrap.builder()
                                .selector(BootstrapSelector.forNames("http", "persistence"))
                                .build()
                                .boot(() -> {
                                    txRef.set(new TransactionOrchestrator(KernelProviders.persistenceEngine()));
                                    boundInside.set(KernelProviders.PERSISTENCE_ENGINE.isBound());
                                    String response = sendRequest(port, roundtripPath(roundtripKey, roundtripValue));
                                    assertThat(response).contains("HTTP/1.1 200 OK");
                                    assertThat(response).contains("X-Exeris-Persistence: ready");
                                    assertThat(response).contains("X-Exeris-Roundtrip-Key: " + roundtripKey);
                                    assertThat(response).contains("X-Exeris-Roundtrip-Value: " + roundtripValue);
                                    assertPersistedValue(roundtripKey, roundtripValue);
                                });
                        return null;
                    });
        } finally {
            restoreProperty("exeris.http.mode", previousHttpMode);
            restoreProperty("exeris.http.bindHost", previousHttpBindHost);
            restoreProperty("exeris.http.port", previousHttpPort);
            restoreProperty("exeris.persistence.jdbcUrl", previousJdbcUrl);
            restoreProperty("exeris.persistence.username", previousUsername);
            restoreProperty("exeris.persistence.password", previousPassword);
            restoreProperty("exeris.persistence.maxPoolSize", previousMaxPoolSize);
        }

        assertThat(boundInside.get()).isTrue();
        assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isFalse();
    }

    @Test
    @DisplayName("Community HttpClientEngine reaches /db/roundtrip over real TCP during bootstrap")
    void httpClientReachesDatabaseRoundtrip() throws Exception {
        int port = nextFreePort();
        String databaseName = "exeris_http_client_bootstrap_" + System.nanoTime();
        String roundtripKey = "client-key-" + System.nanoTime();
        String roundtripValue = "client-value-" + System.nanoTime();

        String previousHttpMode = System.getProperty("exeris.http.mode");
        String previousHttpBindHost = System.getProperty("exeris.http.bindHost");
        String previousHttpPort = System.getProperty("exeris.http.port");
        String previousJdbcUrl = System.getProperty("exeris.persistence.jdbcUrl");
        String previousUsername = System.getProperty("exeris.persistence.username");
        String previousPassword = System.getProperty("exeris.persistence.password");
        String previousMaxPoolSize = System.getProperty("exeris.persistence.maxPoolSize");

        System.setProperty("exeris.http.mode", "SERVER");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));
        System.setProperty("exeris.persistence.jdbcUrl", h2Url(databaseName));
        System.setProperty("exeris.persistence.username", "sa");
        System.setProperty("exeris.persistence.password", "");
        System.setProperty("exeris.persistence.maxPoolSize", "4");

        try {
            AtomicReference<TransactionOrchestrator> txRef = new AtomicReference<>();
            ScopedValue.where(HttpKernelProviders.HTTP_SERVER_HANDLER, buildRoundtripHandler(txRef))
                    .call(() -> {
                        KernelBootstrap.builder()
                                .selector(BootstrapSelector.forNames("http", "persistence"))
                                .build()
                                .boot(() -> {
                                    txRef.set(new TransactionOrchestrator(KernelProviders.persistenceEngine()));
                                    HttpConfig clientConfig = new HttpConfig(
                                            eu.exeris.kernel.spi.http.HttpMode.CLIENT,
                                            "127.0.0.1",
                                            port,
                                            HttpConfig.DEFAULT_MAX_CONNECTIONS,
                                            HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                                            HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                                            HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                                            HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                                            false,
                                            HttpVersion.HTTP_1_1
                                    );
                                    try (HttpClientEngine client = new CommunityHttpProvider().createClientEngine(clientConfig)) {
                                        client.start();
                                        var response = client.send(HttpRequest.noBody(
                                                HttpMethod.GET,
                                                roundtripPath(roundtripKey, roundtripValue),
                                                HttpVersion.HTTP_1_1,
                                                java.util.List.of()));
                                        assertThat(response.status().code()).isEqualTo(200);
                                        assertThat(response.headers())
                                                .anyMatch(header -> header.nameEqualsIgnoreCase("X-Exeris-Persistence")
                                                        && header.value().equals("ready"));
                                        assertThat(response.headers())
                                                .anyMatch(header -> header.nameEqualsIgnoreCase("X-Exeris-Roundtrip-Key")
                                                        && header.value().equals(roundtripKey));
                                        assertThat(response.headers())
                                                .anyMatch(header -> header.nameEqualsIgnoreCase("X-Exeris-Roundtrip-Value")
                                                        && header.value().equals(roundtripValue));
                                        assertPersistedValue(roundtripKey, roundtripValue);
                                        if (response.body() != null) {
                                            response.body().close();
                                        }
                                    }
                                });
                        return null;
                    });
        } finally {
            restoreProperty("exeris.http.mode", previousHttpMode);
            restoreProperty("exeris.http.bindHost", previousHttpBindHost);
            restoreProperty("exeris.http.port", previousHttpPort);
            restoreProperty("exeris.persistence.jdbcUrl", previousJdbcUrl);
            restoreProperty("exeris.persistence.username", previousUsername);
            restoreProperty("exeris.persistence.password", previousPassword);
            restoreProperty("exeris.persistence.maxPoolSize", previousMaxPoolSize);
        }
    }

    @Test
    @DisplayName("KernelBootstrap with HTTP and persistence returns 400 for /db/roundtrip missing required parameters")
    void httpAndPersistenceRejectRoundtripWhenRequiredParametersAreMissing() throws Exception {
        int port = nextFreePort();
        String databaseName = "exeris_http_bootstrap_missing_params_" + System.nanoTime();
        String roundtripValue = "value-" + System.nanoTime();

        String previousHttpMode = System.getProperty("exeris.http.mode");
        String previousHttpBindHost = System.getProperty("exeris.http.bindHost");
        String previousHttpPort = System.getProperty("exeris.http.port");
        String previousJdbcUrl = System.getProperty("exeris.persistence.jdbcUrl");
        String previousUsername = System.getProperty("exeris.persistence.username");
        String previousPassword = System.getProperty("exeris.persistence.password");
        String previousMaxPoolSize = System.getProperty("exeris.persistence.maxPoolSize");

        System.setProperty("exeris.http.mode", "SERVER");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));
        System.setProperty("exeris.persistence.jdbcUrl", h2Url(databaseName));
        System.setProperty("exeris.persistence.username", "sa");
        System.setProperty("exeris.persistence.password", "");
        System.setProperty("exeris.persistence.maxPoolSize", "4");

        try {
            AtomicReference<TransactionOrchestrator> txRef = new AtomicReference<>();
            ScopedValue.where(HttpKernelProviders.HTTP_SERVER_HANDLER, buildRoundtripHandler(txRef))
                    .call(() -> {
                        KernelBootstrap.builder()
                                .selector(BootstrapSelector.forNames("http", "persistence"))
                                .build()
                                .boot(() -> {
                                    txRef.set(new TransactionOrchestrator(KernelProviders.persistenceEngine()));
                                    String response = sendRequest(port, "/db/roundtrip?value=" + urlEncode(roundtripValue));
                                    assertThat(response).contains("HTTP/1.1 400 Bad Request");
                                    assertThat(response).contains("X-Exeris-Persistence: missing-parameters");
                                });
                        return null;
                    });
        } finally {
            restoreProperty("exeris.http.mode", previousHttpMode);
            restoreProperty("exeris.http.bindHost", previousHttpBindHost);
            restoreProperty("exeris.http.port", previousHttpPort);
            restoreProperty("exeris.persistence.jdbcUrl", previousJdbcUrl);
            restoreProperty("exeris.persistence.username", previousUsername);
            restoreProperty("exeris.persistence.password", previousPassword);
            restoreProperty("exeris.persistence.maxPoolSize", previousMaxPoolSize);
        }
    }

    @Test
    @DisplayName("KernelBootstrap with HTTP and persistence serves read path /db/ping without manual transaction flow")
    void httpAndPersistenceServePingReadPath() throws Exception {
        int port = nextFreePort();
        String databaseName = "exeris_http_ping_bootstrap_" + System.nanoTime();

        String previousHttpMode = System.getProperty("exeris.http.mode");
        String previousHttpBindHost = System.getProperty("exeris.http.bindHost");
        String previousHttpPort = System.getProperty("exeris.http.port");
        String previousJdbcUrl = System.getProperty("exeris.persistence.jdbcUrl");
        String previousUsername = System.getProperty("exeris.persistence.username");
        String previousPassword = System.getProperty("exeris.persistence.password");
        String previousMaxPoolSize = System.getProperty("exeris.persistence.maxPoolSize");

        System.setProperty("exeris.http.mode", "SERVER");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));
        System.setProperty("exeris.persistence.jdbcUrl", h2Url(databaseName));
        System.setProperty("exeris.persistence.username", "sa");
        System.setProperty("exeris.persistence.password", "");
        System.setProperty("exeris.persistence.maxPoolSize", "4");

        try {
            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("http", "persistence"))
                    .build()
                    .boot(() -> {
                        String response = sendRequest(port, "/db/ping");
                        assertThat(response).contains("HTTP/1.1 200 OK");
                        assertThat(response).contains("X-Exeris-Persistence: ready");
                    });
        } finally {
            restoreProperty("exeris.http.mode", previousHttpMode);
            restoreProperty("exeris.http.bindHost", previousHttpBindHost);
            restoreProperty("exeris.http.port", previousHttpPort);
            restoreProperty("exeris.persistence.jdbcUrl", previousJdbcUrl);
            restoreProperty("exeris.persistence.username", previousUsername);
            restoreProperty("exeris.persistence.password", previousPassword);
            restoreProperty("exeris.persistence.maxPoolSize", previousMaxPoolSize);
        }
    }

    @Test
    @DisplayName("KernelBootstrap roundtrip rolls back when write fails and preserves previously committed value")
    void httpAndPersistenceRoundtripRollsBackOnException() throws Exception {
        int port = nextFreePort();
        String databaseName = "exeris_http_roundtrip_rollback_" + System.nanoTime();
        String key = "rollback-key-" + System.nanoTime();
        String committedValue = "stable-value-" + System.nanoTime();
        String failingValue = "x".repeat(300);

        String previousHttpMode = System.getProperty("exeris.http.mode");
        String previousHttpBindHost = System.getProperty("exeris.http.bindHost");
        String previousHttpPort = System.getProperty("exeris.http.port");
        String previousJdbcUrl = System.getProperty("exeris.persistence.jdbcUrl");
        String previousUsername = System.getProperty("exeris.persistence.username");
        String previousPassword = System.getProperty("exeris.persistence.password");
        String previousMaxPoolSize = System.getProperty("exeris.persistence.maxPoolSize");

        System.setProperty("exeris.http.mode", "SERVER");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));
        System.setProperty("exeris.persistence.jdbcUrl", h2Url(databaseName));
        System.setProperty("exeris.persistence.username", "sa");
        System.setProperty("exeris.persistence.password", "");
        System.setProperty("exeris.persistence.maxPoolSize", "4");

        try {
            AtomicReference<TransactionOrchestrator> txRef = new AtomicReference<>();
            ScopedValue.where(HttpKernelProviders.HTTP_SERVER_HANDLER, buildRoundtripHandler(txRef))
                    .call(() -> {
                        KernelBootstrap.builder()
                                .selector(BootstrapSelector.forNames("http", "persistence"))
                                .build()
                                .boot(() -> {
                                    txRef.set(new TransactionOrchestrator(KernelProviders.persistenceEngine()));
                                    String firstResponse = sendRequest(port, roundtripPath(key, committedValue));
                                    assertThat(firstResponse).contains("HTTP/1.1 200 OK");
                                    assertPersistedValue(key, committedValue);

                                    String secondResponse = sendRequest(port, roundtripPath(key, failingValue));
                                    assertThat(secondResponse).contains("HTTP/1.1 500 Internal Server Error");
                                    assertThat(secondResponse).contains("X-Exeris-Persistence: error");
                                    assertPersistedValue(key, committedValue);
                                });
                        return null;
                    });
        } finally {
            restoreProperty("exeris.http.mode", previousHttpMode);
            restoreProperty("exeris.http.bindHost", previousHttpBindHost);
            restoreProperty("exeris.http.port", previousHttpPort);
            restoreProperty("exeris.persistence.jdbcUrl", previousJdbcUrl);
            restoreProperty("exeris.persistence.username", previousUsername);
            restoreProperty("exeris.persistence.password", previousPassword);
            restoreProperty("exeris.persistence.maxPoolSize", previousMaxPoolSize);
        }
    }

    private static void assertPersistedValue(String key, String expectedValue) {
        try (PersistenceConnection connection = KernelProviders.persistenceEngine().openConnection()) {
            try (var statement = connection.prepare(
                    "SELECT entry_value FROM exeris_http_roundtrip WHERE entry_key = $1")) {
                statement.bindString(0, key);
                try (QueryResult result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.row().getString(0)).isEqualTo(expectedValue);
                }
            }
        }
    }

    private static String roundtripPath(String key, String value) {
        return "/db/roundtrip?key=" + urlEncode(key) + "&value=" + urlEncode(value);
    }

    private static String h2Url(String databaseName) {
        return "jdbc:h2:mem:" + databaseName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String sendRequest(int port, String path) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(2_000);

            try (Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
                writer.write("GET " + path + " HTTP/1.1\r\n");
                writer.write("Host: 127.0.0.1\r\n");
                writer.write("Connection: close\r\n");
                writer.write("\r\n");
                writer.flush();

                StringBuilder response = new StringBuilder();
                String line = reader.readLine();
                while (line != null) {
                    response.append(line).append("\n");
                    line = reader.readLine();
                }
                return response.toString();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("HTTP bootstrap request failed", ex);
        }
    }

    private static int nextFreePort() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, value);
    }

    // ── Roundtrip test handler ────────────────────────────────────────────────

    private static final String ROUNDTRIP_TABLE = "exeris_http_roundtrip";
    private static final String CREATE_ROUNDTRIP_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS exeris_http_roundtrip (
                entry_key VARCHAR(255) PRIMARY KEY,
                entry_value VARCHAR(255) NOT NULL
            )
            """;
    private static final String DELETE_ROUNDTRIP_SQL =
            "DELETE FROM " + ROUNDTRIP_TABLE + " WHERE entry_key = $1";
    private static final String INSERT_ROUNDTRIP_SQL =
            "INSERT INTO " + ROUNDTRIP_TABLE + " (entry_key, entry_value) VALUES ($1, $2)";
    private static final String SELECT_ROUNDTRIP_SQL =
            "SELECT entry_value FROM " + ROUNDTRIP_TABLE + " WHERE entry_key = $1";
    private static final String HEADER_PERSISTENCE = "X-Exeris-Persistence";
    private static final String HEADER_ROUNDTRIP_KEY = "X-Exeris-Roundtrip-Key";
    private static final String HEADER_ROUNDTRIP_VALUE = "X-Exeris-Roundtrip-Value";

    private static HttpHandler buildRoundtripHandler(AtomicReference<TransactionOrchestrator> txRef) {
        return HttpRouter.builder()
                .route(HttpMethod.GET, "/db/roundtrip",
                        e -> e.respond(testRoundtrip(txRef.get(), e.request().path(), e.request().version())))
                .notFound(e -> e.respond(HttpResponse.noBody(HttpStatus.NOT_FOUND, e.request().version())))
                .build();
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static HttpResponse testRoundtrip(
            TransactionOrchestrator tx,
            String requestPath,
            eu.exeris.kernel.spi.http.HttpVersion version) {
        if (tx == null) {
            return HttpResponse.noBody(HttpStatus.SERVICE_UNAVAILABLE, version,
                    List.of(new HttpHeader(HEADER_PERSISTENCE, "unbound")));
        }
        Map<String, String> query = testParseQuery(requestPath);
        String key = query.get("key");
        String value = query.get("value");
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return HttpResponse.noBody(HttpStatus.BAD_REQUEST, version,
                    List.of(new HttpHeader(HEADER_PERSISTENCE, "missing-parameters")));
        }
        try {
            String[] persisted = new String[1];
            tx.executeManaged(connection -> {
                connection.executeUpdate(CREATE_ROUNDTRIP_TABLE_SQL);
                try (PersistenceStatement del = connection.prepare(DELETE_ROUNDTRIP_SQL)) {
                    del.bindString(0, key).executeUpdate();
                }
                try (PersistenceStatement ins = connection.prepare(INSERT_ROUNDTRIP_SQL)) {
                    ins.bindString(0, key).bindString(1, value).executeUpdate();
                }
                try (PersistenceStatement sel = connection.prepare(SELECT_ROUNDTRIP_SQL)) {
                    sel.bindString(0, key);
                    try (QueryResult r = sel.executeQuery()) {
                        if (r.next()) {
                            persisted[0] = r.row().getString(0);
                        }
                    }
                }
            });
            if (persisted[0] != null) {
                return HttpResponse.noBody(HttpStatus.OK, version, List.of(
                        new HttpHeader(HEADER_PERSISTENCE, "ready"),
                        new HttpHeader(HEADER_ROUNDTRIP_KEY, key),
                        new HttpHeader(HEADER_ROUNDTRIP_VALUE, persisted[0])
                ));
            }
            return HttpResponse.noBody(HttpStatus.INTERNAL_SERVER_ERROR, version,
                    List.of(new HttpHeader(HEADER_PERSISTENCE, "missing-row")));
        } catch (RuntimeException _) {
            return HttpResponse.noBody(HttpStatus.INTERNAL_SERVER_ERROR, version,
                    List.of(new HttpHeader(HEADER_PERSISTENCE, "error")));
        }
    }

    private static Map<String, String> testParseQuery(String requestPath) {
        int sep = requestPath.indexOf('?');
        if (sep < 0 || sep == requestPath.length() - 1) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : requestPath.substring(sep + 1).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            values.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                    URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return values;
    }
}