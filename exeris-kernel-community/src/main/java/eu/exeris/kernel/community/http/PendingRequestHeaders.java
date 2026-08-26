/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Package-private HPACK-decode accumulator for one HTTP/2 request used by
 * {@link Http2SessionContext#decodePendingRequest()}.
 *
 * <p>Extracted from {@link CommunityHttp2SessionProcessor} in v0.8 Sprint 3
 * (QA-018a) as one of four seams of the processor's God-class decomposition.
 * Enforces RFC 7540 §8.1.2.1 pseudo-header ordering (pseudo-headers MUST
 * precede regular headers), §8.1.2.2 connection-specific header rejection,
 * §8.1.2.3 the prohibition on duplicate request pseudo-headers, and the
 * {@code :method} / {@code :path} pseudo-header requirements.
 *
 * <p>Instances are short-lived (one per HEADERS+CONTINUATION block).
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

    /* default */ void invalidate() {
        valid = false;
    }

    /* default */ Http2DecodedRequest toDecodedRequest(int streamId) {
        HttpMethod method = parseHttp2Method(methodToken);
        String resolvedPath = path == null ? "" : path;
        boolean requestValid = valid && method != null && !resolvedPath.isEmpty();
        return new Http2DecodedRequest(streamId, method, resolvedPath, List.copyOf(requestHeaders), requestValid);
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
