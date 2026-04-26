/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.Locale;
import java.util.Map;

final class CommunityHttpConfigResolver {

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

    private CommunityHttpConfigResolver() {
    }

    /* default */ static HttpConfig buildHttpConfig(ConfigProvider configProvider) {
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
        String configuredMode = configProvider.getString("http.mode")
            .map(String::strip)
            .filter(mode -> !mode.isEmpty())
            .orElse(null);
        if (configuredMode != null) {
            HttpMode mode = HTTP_MODE_ALIASES.get(configuredMode.toUpperCase(Locale.ROOT));
            if (mode == null) {
                throw new IllegalArgumentException("Unsupported http.mode: " + configuredMode);
            }
            return mode;
        }

        boolean hasExplicitPort = configProvider.getInt("http.port").isPresent()
            || configProvider.getInt("network.port").isPresent();
        return hasExplicitPort ? HttpMode.SERVER : HttpMode.DISABLED;
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
