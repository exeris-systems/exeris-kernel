/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SPI: An immutable HTTP request carrier (RFC 9110 §7).
 *
 * <h2>Zero-Copy Body</h2>
 * <p>The request body, when present, is carried as a {@link LoanedBuffer} backed by
 * a Panama {@link java.lang.foreign.MemorySegment}. No heap copy is made — the
 * buffer slice originates from the transport's slab pool and is reference-counted.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record}. No identity operations ({@code ==},
 * {@code System.identityHashCode()}, {@code synchronized}) on instances.
 * Scalarises via C2 JIT Escape Analysis when used transiently within a handler scope.
 * Will migrate to {@code value record} (JEP 401) once mainline GA is reached.
 *
 * <h2>Naming the Peer (ADR-074)</h2>
 * <p>{@link #authority()} is the RFC 3986 authority of the peer an <em>outbound</em> request is
 * addressed to — the DIAL address, deliberately distinct from {@code HttpConfig.bindHost}, which is
 * a LISTEN address. A {@code null} authority means <em>"the client engine's configured default
 * peer"</em>, and when the engine declares none such a request is refused rather than sent
 * somewhere unintended.
 *
 * <p><strong>Inbound requests leave it null, deliberately.</strong> A server-side request already
 * carries the client's addressee in the {@code Host} header (HTTP/1.1) or the {@code :authority}
 * pseudo-header (HTTP/2), which is where the protocol puts it. Copying that into a record component
 * would create a second source of truth that can disagree with the first, so a handler reads it
 * through {@link #firstHeader(String)}.
 *
 * <p><b>Allocation:</b> allocates (the carrier itself, and one merged header list per
 * {@link #withAdditionalHeaders(List)}); the body is carried by reference with no heap copy
 * <p><b>Thread confinement:</b> owner thread — the record is immutable and readable anywhere, but
 * its {@link LoanedBuffer} body may only be touched from the virtual thread that owns the
 * corresponding {@link HttpExchange}
 * <p><b>Ownership:</b> the transport or codec that produced the body owns it and releases it when
 * the exchange ends; a derived request from {@link #withAuthority(String)} or
 * {@link #withAdditionalHeaders(List)} shares that one buffer and takes no reference of its own
 *
 * @param method    HTTP method; non-null
 * @param authority outbound addressee as {@code host:port}, or {@code null} for the client engine's
 *                  configured default peer; always {@code null} on inbound requests. The port is
 *                  REQUIRED — {@code HttpRequest} carries no scheme, so there is no basis for
 *                  choosing 80 over 443, and an IPv6 address must be bracketed
 *                  ({@code [::1]:8080}) because the unbracketed form is ambiguous
 * @param path      request-target path component, e.g. {@code "/api/v1/users?page=1"}; non-null
 * @param version   protocol version; non-null
 * @param headers   immutable list of header fields; non-null, may be empty
 * @param body      request body buffer, or {@code null} if the request has no body
 * @apiNote A handler that lets this request — or its body — outlive the
 *          {@link HttpExchange} call must {@link LoanedBuffer#retain()} its own reference and close
 *          it; without that the segment returns to the pool while the reference is still held, and
 *          the bytes read back are another request's.
 * @implNote The Community client engine copies the body into its own off-heap segment for the
 *           combined wire buffer before writing it, rather than writing the carried reference
 *           directly.
 * @since 0.5
 */
public record HttpRequest(
        HttpMethod method,
        String authority,
        String path,
        HttpVersion version,
        List<HttpHeader> headers,
        LoanedBuffer body
) {

    /**
     * Rejects the four components a request cannot be read without; {@code authority} and
     * {@code body} stay nullable because each has a meaning as {@code null} — the engine's default
     * peer, and no request body.
     *
     * @throws NullPointerException if {@code method}, {@code path}, {@code version} or
     *                              {@code headers} is {@code null}
     */
    public HttpRequest {
        Objects.requireNonNull(method,  "method must not be null");
        Objects.requireNonNull(path,    "path must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        // authority is intentionally nullable — null means "the engine's configured default peer"
        // body is intentionally nullable — null signals no request body
    }

    /**
     * Creates a request addressed to the client engine's configured default peer.
     *
     * <p>A compatibility overload for callers that name no {@link #authority()}: it delegates with
     * a {@code null} one, which is how a request says "wherever this engine is configured to send".
     *
     * @param method  HTTP method; non-null
     * @param path    request-target path component; non-null
     * @param version protocol version; non-null
     * @param headers immutable list of header fields; non-null, may be empty
     * @param body    request body buffer, or {@code null} if the request has no body
     * @since 0.5
     */
    public HttpRequest(HttpMethod method,
                       String path,
                       HttpVersion version,
                       List<HttpHeader> headers,
                       LoanedBuffer body) {
        this(method, null, path, version, headers, body);
    }

    /**
     * Returns the value of the first header whose name matches {@code name}
     * case-insensitively, or {@link Optional#empty()} if no such header exists.
     *
     * @param name header name to find; must not be {@code null}
     * @return the first matching header's value, or {@link Optional#empty()} when no header
     *         carries that name
     * @throws NullPointerException if {@code name} is {@code null}
     * @implNote A linear scan of the header list, which is what the bound
     *           {@code maxRequestHeaderCount} (100 by default) makes acceptable; no index is built.
     */
    public Optional<String> firstHeader(String name) {
        Objects.requireNonNull(name, "name must not be null");
        for (HttpHeader h : headers) {
            if (h.nameEqualsIgnoreCase(name)) {
                return Optional.of(h.value());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns whether a body buffer travels with this request — {@code false} means there is
     * nothing to decode and nothing to release.
     *
     * @return {@code true} if {@link #body()} is non-null
     */
    public boolean hasBody() {
        return body != null;
    }

    /**
     * Creates a request with no body, addressed to the engine's configured default peer.
     *
     * @param method  HTTP method
     * @param path    request path
     * @param version protocol version
     * @param headers header list
     * @return a new {@code HttpRequest} whose {@link #body()} is {@code null}, so nothing has to be
     *         released for it
     */
    public static HttpRequest noBody(HttpMethod method, String path, HttpVersion version, List<HttpHeader> headers) {
        return new HttpRequest(method, null, path, version, headers, null);
    }

    /**
     * Returns a derived request addressed to {@code authority}, carrying the body by reference with
     * no buffer copy.
     *
     * <p>The companion to {@link #withAdditionalHeaders(List)} for the other thing a client-side
     * façade derives. Passing {@code null} returns a request bound to the engine's configured
     * default peer.
     *
     * @param authority {@code host:port} (port required, IPv6 bracketed), or {@code null} for the
     *                  engine's configured default peer
     * @return derived request, or {@code this} when the authority is already the requested one
     * @since 0.12
     */
    public HttpRequest withAuthority(String authority) {
        if (Objects.equals(this.authority, authority)) {
            return this;
        }
        return new HttpRequest(method, authority, path, version, headers, body);
    }

    /**
     * Returns a derived {@link HttpRequest} whose header list is the existing
     * {@link #headers()} followed by {@code additional}, preserving immutability
     * and body-buffer ownership (the body reference is carried over by reference;
     * no buffer copy).
     *
     * <p>Used by the client-side façade ({@code KernelWebClient.execute}) to merge
     * encoder-supplied {@code content-type} with façade-supplied
     * {@code accept}/{@code content-length}, and by {@code HttpClientRequestEnricher}
     * chains (ADR-032) that append tenant / principal / trace headers.
     *
     * <p>When {@code additional} is empty, returns {@code this} unchanged.
     *
     * @param additional headers to append after the existing list; non-null, may be empty
     * @return derived request with merged headers, or {@code this} when {@code additional} is empty
     * @since 0.8
     */
    public HttpRequest withAdditionalHeaders(List<HttpHeader> additional) {
        Objects.requireNonNull(additional, "additional must not be null");
        if (additional.isEmpty()) {
            return this;
        }
        List<HttpHeader> merged = new ArrayList<>(headers.size() + additional.size());
        merged.addAll(headers);
        merged.addAll(additional);
        return new HttpRequest(method, authority, path, version, List.copyOf(merged), body);
    }
}
