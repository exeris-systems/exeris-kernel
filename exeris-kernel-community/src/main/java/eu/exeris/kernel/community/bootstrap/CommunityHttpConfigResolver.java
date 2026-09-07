/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.Locale;
import java.util.Map;

/**
 * Reads {@code http.*} configuration into an {@link HttpConfig}, and decides whether HTTP runs at
 * all.
 *
 * <p>{@link #resolveMode} infers {@link HttpMode#SERVER} from the presence of an explicit
 * {@code http.port} or {@code network.port} when {@code http.mode} itself is unset, and
 * {@link HttpMode#DISABLED} otherwise — HTTP is the subsystem an application typically boots the
 * kernel for, so a configured port is read as intent to serve. Every other alias map
 * ({@link #HTTP_VERSION_ALIASES}) accepts both the enum spelling and the everyday one
 * ({@code "HTTP/2"}, {@code "2"}, {@code "2.0"}) so operators are not made to learn the SPI's naming.
 */
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

        // Resolved independently of http.maxRequestBodyBytes, which is the point (ADR-071 amendment):
        // one bounds what this server accepts, the other what this client reads back from someone
        // else's server. Sharing a key made a deployment tuning its ingress retune its outbound
        // client, and neither name said so.
        long maxResponseBodyBytes = configProvider.getLong("http.maxResponseBodyBytes")
            .orElse(HttpConfig.DEFAULT_MAX_RESPONSE_BODY_BYTES);
        boolean h2cUpgradeEnabled = configProvider.getBoolean("http.h2cUpgradeEnabled")
            .orElse(true);
        HttpVersion maxVersion = resolveMaxVersion(configProvider);
        // ADR-074. A DIAL address, deliberately distinct from http.bindHost, which is a LISTEN
        // address — the client used to read the latter as the former. No default: an unaddressed
        // request is refused rather than sent to whatever the server happens to bind.
        // ADR-071's tail: the HTTP/1 header keys are a per-field size and a field count, while
        // HTTP/2 bounds an assembled header BLOCK — different quantities, so this is its own key
        // rather than a product of the other two, which would have loosened the default twelvefold.
        int maxHeaderBlockSize = configProvider.getInt("http.maxHeaderBlockSize")
            .orElse(HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE);
        // Three HTTP/2 keys and not one, because they bound three different quantities: the
        // COMPRESSED block on the wire (above), the CUMULATIVE decoded field section, and a
        // SINGLE decoded literal. Compression is what makes the first two independent — neither
        // can be computed from the other — and the middle one is the only one RFC 9113 §6.5.2
        // defines SETTINGS_MAX_HEADER_LIST_SIZE against, so it is the one that gets advertised.
        int maxHeaderListSize = configProvider.getInt("http.maxHeaderListSize")
            .orElse(HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE);
        int maxStringLiteralSize = configProvider.getInt("http.maxStringLiteralSize")
            .orElse(HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE);
        String defaultAuthority = configProvider.getString("http.client.defaultAuthority")
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .orElse(null);

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
            maxVersion,
            defaultAuthority,
            maxHeaderBlockSize,
            maxHeaderListSize,
            maxStringLiteralSize,
            maxResponseBodyBytes
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
