/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.http.routing.HttpRouter;
import eu.exeris.kernel.core.persistence.TransactionOrchestrator;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpProvider;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.UnaryOperator;

@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity"})
final class CommunityHttpSubsystem implements Subsystem {

    private static final String HEADER_PERSISTENCE = "X-Exeris-Persistence";
    private static final String HEADER_ROUNDTRIP_KEY = "X-Exeris-Roundtrip-Key";
    private static final String HEADER_ROUNDTRIP_VALUE = "X-Exeris-Roundtrip-Value";
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

    private static final Map<String, HttpMode> HTTP_MODE_ALIASES = Map.of(
        "SERVER",   HttpMode.SERVER,
        "CLIENT",   HttpMode.CLIENT,
        "DUAL",     HttpMode.DUAL,
        "DISABLED", HttpMode.DISABLED
    );

    private static final Map<String, HttpVersion> HTTP_VERSION_ALIASES = Map.ofEntries(
        Map.entry("HTTP_1_0", HttpVersion.HTTP_1_0),
        Map.entry("HTTP/1.0", HttpVersion.HTTP_1_0),
        Map.entry("1.0",      HttpVersion.HTTP_1_0),
        Map.entry("HTTP_1_1", HttpVersion.HTTP_1_1),
        Map.entry("HTTP/1.1", HttpVersion.HTTP_1_1),
        Map.entry("1.1",      HttpVersion.HTTP_1_1),
        Map.entry("HTTP_2",   HttpVersion.HTTP_2),
        Map.entry("HTTP/2",   HttpVersion.HTTP_2),
        Map.entry("2",        HttpVersion.HTTP_2),
        Map.entry("2.0",      HttpVersion.HTTP_2),
        Map.entry("HTTP_3",   HttpVersion.HTTP_3),
        Map.entry("HTTP/3",   HttpVersion.HTTP_3),
        Map.entry("3",        HttpVersion.HTTP_3),
        Map.entry("3.0",      HttpVersion.HTTP_3)
    );

    private HttpProvider provider;
    private HttpConfig httpConfig;
    private DeferredHttpServerEngine serverEngine;
    private DeferredHttpClientEngine clientEngine;
    private boolean running;

    @Override
    public String name() {
        return "http";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("memory");
    }

    @Override
    public BootstrapPhase phase() {
        return BootstrapPhase.RUNTIME;
    }

    @Override
    public void initialize() {
        ConfigProvider configProvider = KernelProviders.CURRENT_CONFIG.get();
        httpConfig = buildHttpConfig(configProvider);
        if (httpConfig.mode() == HttpMode.DISABLED) {
            return;
        }

        provider = ServiceLoader.load(HttpProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(HttpProvider::priority))
                .orElseThrow(() -> new IllegalStateException("No HttpProvider found on classpath"));

        if (httpConfig.mode() == HttpMode.SERVER || httpConfig.mode() == HttpMode.DUAL) {
            serverEngine = new DeferredHttpServerEngine(provider, httpConfig);
        }
        if (httpConfig.mode() == HttpMode.CLIENT || httpConfig.mode() == HttpMode.DUAL) {
            clientEngine = new DeferredHttpClientEngine(provider, httpConfig);
        }
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    public void start() {
        if (provider == null || httpConfig == null || httpConfig.mode() == HttpMode.DISABLED) {
            return;
        }

        if (serverEngine != null) {
            PersistenceEngine persistenceEngine = KernelProviders.PERSISTENCE_ENGINE.isBound()
                    ? KernelProviders.persistenceEngine()
                    : null;
            HttpHandler handler = HttpKernelProviders.httpServerHandler()
                .orElseGet(() -> healthHandler(persistenceEngine));
            serverEngine.setHandler(handler);
            serverEngine.start();
        }
        if (clientEngine != null) {
            clientEngine.start();
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (clientEngine != null) {
            clientEngine.close();
        }
        if (serverEngine != null) {
            serverEngine.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (provider == null) {
            return Subsystem.super.providerBindings();
        }
        if (serverEngine != null && clientEngine != null) {
            return carrier -> carrier
                    .where(HttpKernelProviders.HTTP_PROVIDER, provider)
                    .where(HttpKernelProviders.HTTP_SERVER_ENGINE, serverEngine)
                    .where(HttpKernelProviders.HTTP_CLIENT_ENGINE, clientEngine);
        }
        if (serverEngine != null) {
            return carrier -> carrier
                    .where(HttpKernelProviders.HTTP_PROVIDER, provider)
                    .where(HttpKernelProviders.HTTP_SERVER_ENGINE, serverEngine);
        }
        return carrier -> carrier
                .where(HttpKernelProviders.HTTP_PROVIDER, provider)
                .where(HttpKernelProviders.HTTP_CLIENT_ENGINE, clientEngine);
    }

    private HttpHandler healthHandler(PersistenceEngine persistenceEngine) {
        TransactionalExecutor transactionalExecutor = persistenceEngine == null
            ? null
            : new TransactionOrchestrator(persistenceEngine);
        return HttpRouter.builder()
            .route(HttpMethod.GET, "/health",
                e -> e.respond(HttpResponse.noBody(HttpStatus.OK, e.request().version())))
            .route(HttpMethod.GET, "/health/live",
                e -> e.respond(HttpResponse.noBody(HttpStatus.OK, e.request().version())))
            .route(HttpMethod.GET, "/health/ready",
                e -> e.respond(HttpResponse.noBody(readinessStatus(), e.request().version())))
            .route(HttpMethod.GET, "/db/ping",
                e -> e.respond(persistenceProbe(transactionalExecutor, e.request().version())))
            .route(HttpMethod.GET, "/db/roundtrip",
                e -> e.respond(persistenceRoundtrip(transactionalExecutor, e.request().path(), e.request().version())))
            .notFound(e -> e.respond(HttpResponse.noBody(HttpStatus.NOT_FOUND, e.request().version())))
            .build();
    }

    private HttpStatus readinessStatus() {
        if (!running) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (serverEngine != null && !serverEngine.isRunning()) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.OK;
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
        } catch (RuntimeException ex) {
            return HttpResponse.noBody(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    version,
                    List.of(new HttpHeader(HEADER_PERSISTENCE, "error"))
            );
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static HttpResponse persistenceRoundtrip(TransactionalExecutor transactionalExecutor,
                                                     String requestPath,
                                                     HttpVersion version) {
        if (transactionalExecutor == null) {
            return HttpResponse.noBody(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    version,
                    List.of(new HttpHeader(HEADER_PERSISTENCE, "unbound"))
            );
        }

        Map<String, String> query = parseQuery(requestPath);
        String key = query.get("key");
        String value = query.get("value");
        if (isBlank(key) || isBlank(value)) {
            return HttpResponse.noBody(
                    HttpStatus.BAD_REQUEST,
                    version,
                    List.of(new HttpHeader(HEADER_PERSISTENCE, "missing-parameters"))
            );
        }

        try {
            String[] persistedValue = new String[1];
            transactionalExecutor.executeManaged(connection -> {
                connection.executeUpdate(CREATE_ROUNDTRIP_TABLE_SQL);

                try (PersistenceStatement delete = connection.prepare(DELETE_ROUNDTRIP_SQL)) {
                    delete.bindString(0, key).executeUpdate();
                }
                try (PersistenceStatement insert = connection.prepare(INSERT_ROUNDTRIP_SQL)) {
                    insert.bindString(0, key)
                            .bindString(1, value)
                            .executeUpdate();
                }
                try (PersistenceStatement select = connection.prepare(SELECT_ROUNDTRIP_SQL)) {
                    select.bindString(0, key);
                    try (QueryResult result = select.executeQuery()) {
                        if (result.next()) {
                            persistedValue[0] = result.row().getString(0);
                        }
                    }
                }
            });

            if (persistedValue[0] != null) {
                return HttpResponse.noBody(
                        HttpStatus.OK,
                        version,
                        List.of(
                                new HttpHeader(HEADER_PERSISTENCE, "ready"),
                                new HttpHeader(HEADER_ROUNDTRIP_KEY, key),
                                new HttpHeader(HEADER_ROUNDTRIP_VALUE, persistedValue[0])
                        )
                );
            }
            return HttpResponse.noBody(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    version,
                    List.of(new HttpHeader(HEADER_PERSISTENCE, "missing-row"))
            );
        } catch (RuntimeException _) {
            return HttpResponse.noBody(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    version,
                    List.of(new HttpHeader(HEADER_PERSISTENCE, "error"))
            );
        }
    }

    private static Map<String, String> parseQuery(String requestPath) {
        int separator = requestPath.indexOf('?');
        if (separator < 0 || separator == requestPath.length() - 1) {
            return Map.of();
        }

        Map<String, String> values = new LinkedHashMap<>();
        String query = requestPath.substring(separator + 1);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String rawKey = equals >= 0 ? pair.substring(0, equals) : pair;
            String rawValue = equals >= 0 ? pair.substring(equals + 1) : "";
            values.put(urlDecode(rawKey), urlDecode(rawValue));
        }
        return values;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static HttpConfig buildHttpConfig(ConfigProvider configProvider) {
        HttpMode mode = resolveMode(configProvider);
        if (mode == HttpMode.DISABLED) {
            return new HttpConfig(
                    HttpMode.DISABLED,
                    null,
                    -1,
                    HttpConfig.DEFAULT_MAX_CONNECTIONS,
                    HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                    HttpConfig.DEFAULT_MAX_HEADER_COUNT,
                    HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                    HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES,
                    false,
                    HttpVersion.HTTP_1_1
            );
        }

        String bindHost = configProvider.getString("http.bindHost")
                .orElse(HttpConfig.DEFAULT_BIND_HOST);
        int port = configProvider.getInt("http.port")
                .orElse(configProvider.getInt("network.port").orElse(HttpConfig.DEFAULT_PORT));
        int maxConnections = configProvider.getInt("http.maxConnections")
                .orElse(HttpConfig.DEFAULT_MAX_CONNECTIONS);
        long idleTimeoutMillis = configProvider.getLong("http.idleTimeoutMillis")
                .orElse(HttpConfig.DEFAULT_IDLE_TIMEOUT_MS);
        int maxHeaderCount = configProvider.getInt("http.maxRequestHeaderCount")
                .orElse(HttpConfig.DEFAULT_MAX_HEADER_COUNT);
        int maxHeaderSize = configProvider.getInt("http.maxRequestHeaderSize")
                .orElse(HttpConfig.DEFAULT_MAX_HEADER_SIZE);
        long maxBodyBytes = configProvider.getLong("http.maxRequestBodyBytes")
                .orElse(HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES);
        boolean h2cUpgradeEnabled = configProvider.getBoolean("http.h2cUpgradeEnabled")
                .orElse(true);
        HttpVersion maxVersion = resolveMaxVersion(configProvider);

        return new HttpConfig(
                mode,
                bindHost,
                port,
                maxConnections,
                idleTimeoutMillis,
                maxHeaderCount,
                maxHeaderSize,
                maxBodyBytes,
                h2cUpgradeEnabled,
                maxVersion
        );
    }

    private static HttpMode resolveMode(ConfigProvider configProvider) {
        String configuredMode = configProvider.getString("http.mode").orElse("DISABLED");
        HttpMode mode = HTTP_MODE_ALIASES.get(configuredMode.strip().toUpperCase(Locale.ROOT));
        if (mode == null) {
            throw new IllegalArgumentException("Unsupported http.mode: " + configuredMode);
        }
        return mode;
    }

    private static HttpVersion resolveMaxVersion(ConfigProvider configProvider) {
        String raw = configProvider.getString("http.maxVersion").orElse(HttpVersion.HTTP_2.name());
        HttpVersion resolved = HTTP_VERSION_ALIASES.get(raw.strip().toUpperCase(Locale.ROOT));
        if (resolved == null) {
            throw new IllegalArgumentException("Unsupported http.maxVersion: " + raw);
        }
        return resolved;
    }
}
