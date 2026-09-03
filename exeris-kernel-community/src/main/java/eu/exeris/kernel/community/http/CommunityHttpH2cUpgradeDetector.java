/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.transport.TransportStream;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Community-internal HTTP/2 upgrade-detection helper. Extracted from
 * {@link CommunityHttpRequestProcessor} in QA-011 (v0.8 Sprint 1) so the request
 * processor carries one less responsibility: the *"is this an h2c upgrade or HTTP/2
 * prior-knowledge connection? if yes, transition to the HTTP/2 frame loop"* workflow
 * lives here.
 *
 * <h2>Two upgrade paths</h2>
 * <ul>
 *   <li><b>Prior knowledge</b> — the client opens a TCP/TLS connection and sends the
 *       24-byte HTTP/2 connection preface ({@code PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n})
 *       as the first bytes. Detected via byte-by-byte preface compare on the read
 *       aggregate buffer; on match the request loop hands control to the HTTP/2
 *       session processor and exits.</li>
 *   <li><b>HTTP/1.1 → h2c upgrade</b> — an HTTP/1.1 request arrives carrying both
 *       {@code Upgrade: h2c} and {@code Connection: Upgrade}. Detected on the parsed
 *       header list; the session processor switches modes after replying with
 *       {@code 101 Switching Protocols}.</li>
 * </ul>
 *
 * <h2>State and lifecycle</h2>
 * <p>One detector instance per processor — owns the {@link HttpConfig} (for the
 * {@code h2cUpgradeEnabled} / {@code maxVersion} policy gates) and the
 * {@link CommunityHttp2SessionProcessor} (the actual frame-loop runner). Both are
 * already constructed by the processor in v0.7 — this helper just bundles the
 * decision logic that was previously spread across six instance methods plus a
 * private static.
 *
 * @since 0.8.0
 */
final class CommunityHttpH2cUpgradeDetector {

    private static final String HEADER_CONNECTION = "Connection";
    private static final String HEADER_UPGRADE = "Upgrade";
    private static final String H2C_TOKEN = "h2c";
    private static final String UPGRADE_TOKEN = "upgrade";
    private static final byte[] HTTP2_PRIOR_KNOWLEDGE_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private final HttpConfig config;
    private final CommunityHttp2SessionProcessor http2SessionProcessor;

    /* default */ CommunityHttpH2cUpgradeDetector(HttpConfig config,
                                                  CommunityHttp2SessionProcessor http2SessionProcessor) {
        this.config = Objects.requireNonNull(config, "config");
        this.http2SessionProcessor = Objects.requireNonNull(http2SessionProcessor, "http2SessionProcessor");
    }

    /**
     * Returns {@code true} when {@link HttpConfig#h2cUpgradeEnabled()} is set and the
     * configured {@link HttpConfig#maxVersion()} allows HTTP/2 negotiation.
     */
    /* default */ boolean isH2cEnabled() {
        return config.h2cUpgradeEnabled() && supportsHttp2(config.maxVersion());
    }

    /**
     * If the aggregate buffer holds an HTTP/2 prior-knowledge preface, transitions to
     * the HTTP/2 session loop and returns {@code true}; otherwise leaves the buffer
     * untouched and returns {@code false}. The {@code maxVersion} policy gates the
     * detection — older configs see this method short-circuit to {@code false}.
     */
    @SuppressWarnings("PMD.CloseResource")
    // The LoanedBuffer returned by state.aggregate() is the flyweight whose lifecycle
    // is owned by ProcessingState across the keep-alive loop iterations; closing it
    // here would break the request-loop invariant. The aggregate is closed by
    // ProcessingState.close() through the try-with-resources in
    // CommunityHttpRequestProcessor.process.
    /* default */ boolean shouldHandlePriorKnowledge(TransportStream stream,
                                                     HttpHandler handler,
                                                     ProcessingState state,
                                                     long totalBytes) {
        if (!supportsHttp2(config.maxVersion())) {
            return false;
        }
        LoanedBuffer aggregate = state.aggregate();
        if (!isHttp2PriorKnowledgePreface(aggregate.segment(), totalBytes)) {
            return false;
        }
        http2SessionProcessor.handlePriorKnowledge(
                stream,
                handler,
                state,
                totalBytes,
                HTTP2_PRIOR_KNOWLEDGE_PREFACE.length);
        return true;
    }

    /**
     * Hands an already-parsed HTTP/1.1 upgrade request to the HTTP/2 session processor.
     * Caller is responsible for first verifying the upgrade headers via
     * {@link #isH2cUpgradeIntent(List)} and that h2c upgrade is enabled via
     * {@link #isH2cEnabled()}.
     */
    /* default */ void handleHttp1UpgradeToH2c(ReadResult readResult,
                                               TransportStream stream,
                                               HttpHandler handler,
                                               ProcessingState state) {
        http2SessionProcessor.handleUpgrade(readResult, stream, handler, state);
    }

    /**
     * Returns {@code true} when the first
     * {@link #HTTP2_PRIOR_KNOWLEDGE_PREFACE}{@code .length} bytes of {@code segment}
     * exactly match the HTTP/2 connection preface. Operates byte-by-byte to avoid the
     * heap allocation that a {@code byte[]} copy + {@code Arrays.equals} would cost on
     * the request hot path.
     */
    /* default */ static boolean isHttp2PriorKnowledgePreface(MemorySegment segment, long totalBytes) {
        if (totalBytes < HTTP2_PRIOR_KNOWLEDGE_PREFACE.length) {
            return false;
        }
        for (int index = 0; index < HTTP2_PRIOR_KNOWLEDGE_PREFACE.length; index++) {
            byte actual = segment.get(ValueLayout.JAVA_BYTE, index);
            if (actual != HTTP2_PRIOR_KNOWLEDGE_PREFACE[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} when {@code headers} carry both {@code Upgrade: h2c} and
     * {@code Connection: Upgrade}. Header names compared case-insensitively; both
     * values may be CSV lists per RFC 7230 §6.7, so each value is split on commas
     * before token comparison.
     */
    /* default */ static boolean isH2cUpgradeIntent(List<HttpHeader> headers) {
        boolean hasUpgradeH2c = false;
        boolean hasConnectionUpgrade = false;
        for (HttpHeader header : headers) {
            if (header.nameEqualsIgnoreCase(HEADER_UPGRADE)
                    && containsCsvTokenIgnoreCase(header.value(), H2C_TOKEN)) {
                hasUpgradeH2c = true;
            }
            if (header.nameEqualsIgnoreCase(HEADER_CONNECTION)
                    && containsCsvTokenIgnoreCase(header.value(), UPGRADE_TOKEN)) {
                hasConnectionUpgrade = true;
            }
        }
        return hasUpgradeH2c && hasConnectionUpgrade;
    }

    private static boolean supportsHttp2(HttpVersion maxVersion) {
        return maxVersion == HttpVersion.HTTP_2 || maxVersion == HttpVersion.HTTP_3;
    }

    private static boolean containsCsvTokenIgnoreCase(String headerValue, String token) {
        int start = 0;
        int length = headerValue.length();
        while (start < length) {
            int end = headerValue.indexOf(',', start);
            if (end < 0) {
                end = length;
            }
            String candidate = headerValue.substring(start, end).trim();
            if (candidate.equalsIgnoreCase(token)) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }
}
