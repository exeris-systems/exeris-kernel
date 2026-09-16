/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Package-private HPACK-decode accumulator for one HTTP/2 request used by
 * {@link Http2SessionContext#decodePendingRequest()}.
 *
 * <p>Enforces RFC 7540 §8.1.2.1 pseudo-header ordering (pseudo-headers MUST
 * precede regular headers), §8.1.2.2 connection-specific header rejection,
 * §8.1.2.3 the prohibition on duplicate request pseudo-headers, and the
 * {@code :method} / {@code :path} pseudo-header requirements.
 *
 * <p>Instances are short-lived (one per HEADERS+CONTINUATION block) and single-use: the accumulator
 * is built, filled, and consumed by {@link #toDecodedRequest} inside one call, and never touched
 * again. That is what lets the decoded request wrap this list rather than copy it.
 */
final class PendingRequestHeaders {

    /** Recognised HTTP/2 request pseudo-headers, tracked for duplicate detection (§8.1.2.3). */
    private enum Pseudo { METHOD, PATH, AUTHORITY, SCHEME }

    private String methodToken;
    private String path;
    private boolean valid = true;
    private boolean sawRegularHeader;
    private final Set<Pseudo> seenPseudoHeaders = EnumSet.noneOf(Pseudo.class);
    private final List<HttpHeader> requestHeaders = new ArrayList<>();

    /**
     * Accumulates one decoded header field, applying it as a pseudo-header (a name starting with
     * {@code :}) or a regular header depending on its name. A no-op once this accumulator has
     * already been marked invalid.
     *
     * @param name  the decoded header name, exactly as HPACK produced it
     * @param value the decoded header value
     */
    /* default */ void accept(String name, String value) {
        if (!valid) {
            return;
        }
        if (name.startsWith(":")) {
            acceptPseudoHeader(name, value);
            return;
        }
        sawRegularHeader = true;
        if (Http2SessionContext.isConnectionSpecificHeader(name)) {
            valid = false;
            return;
        }
        requestHeaders.add(new HttpHeader(name, value));
    }

    /** Marks this accumulator invalid regardless of what it has accepted so far. */
    /* default */ void invalidate() {
        valid = false;
    }

    /**
     * Ends this accumulator's life, handing the decoded request an unmodifiable view of the
     * accumulated headers. A view, not a copy: nothing else holds the list, and the accumulator is
     * unreachable the moment this returns, so a copy would only duplicate every header of every
     * HTTP/2 request to protect a reference no caller can obtain.
     */
    /* default */ Http2DecodedRequest toDecodedRequest(int streamId) {
        HttpMethod method = parseHttp2Method(methodToken);
        String resolvedPath = path == null ? "" : path;
        boolean requestValid = valid && method != null && !resolvedPath.isEmpty();
        return new Http2DecodedRequest(streamId, method, resolvedPath,
                Collections.unmodifiableList(requestHeaders), requestValid);
    }

    private void acceptPseudoHeader(String name, String value) {
        if (sawRegularHeader) {
            valid = false;
            return;
        }
        Pseudo pseudo = pseudoOf(name);
        // RFC 7540 §8.1.2.3: unknown pseudo-header, or a duplicate one, fails closed. A repeated
        // :path / :method is a request-smuggling vector — never let a later value overwrite the
        // first via last-wins.
        if (pseudo == null || !seenPseudoHeaders.add(pseudo)) {
            valid = false;
            return;
        }
        if (pseudo == Pseudo.METHOD) {
            methodToken = value;
        } else if (pseudo == Pseudo.PATH) {
            path = value;
        }
        // :authority / :scheme are accepted but not required for this processing path.
    }

    private static Pseudo pseudoOf(String name) {
        return switch (name) {
            case ":method" -> Pseudo.METHOD;
            case ":path" -> Pseudo.PATH;
            case ":authority" -> Pseudo.AUTHORITY;
            case ":scheme" -> Pseudo.SCHEME;
            default -> null;
        };
    }

    private static HttpMethod parseHttp2Method(String methodToken) {
        if (methodToken == null || methodToken.isBlank()) {
            return null;
        }
        try {
            return HttpMethod.valueOf(methodToken);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
