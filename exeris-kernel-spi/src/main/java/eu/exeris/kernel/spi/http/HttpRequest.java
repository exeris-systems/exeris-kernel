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
 * The caller (transport or codec) retains ownership; the {@link HttpHandler} that
 * consumes this request MUST call {@link LoanedBuffer#retain()} if it outlives
 * the current {@link HttpExchange} scope.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record}. No identity operations ({@code ==},
 * {@code System.identityHashCode()}, {@code synchronized}) on instances.
 * Scalarises via C2 JIT Escape Analysis when used transiently within a handler scope.
 * Will migrate to {@code value record} (JEP 401) once mainline GA is reached.
 *
 * <h2>Thread Safety</h2>
 * <p>This record is immutable. The {@link LoanedBuffer} body must only be accessed
 * from the virtual thread that owns the corresponding {@link HttpExchange}.
 *
 * <h2>Naming the Peer (ADR-074)</h2>
 * <p>{@link #authority()} is the RFC 3986 authority of the peer an
 * <em>outbound</em> request is addressed to. It exists because the client engine had nowhere to read
 * a peer from and therefore took one from {@code HttpConfig.bindHost}, the <em>listener</em> address:
 * through the supported path the client dialled the address its own server listened on, and an
 * application could not address any peer at all.
 *
 * <p>A {@code null} authority means <em>"the client engine's configured default peer"</em>, which is
 * what keeps every pre-0.12 call site compiling and behaving unchanged — see the retained
 * five-argument constructor.
 *
 * <p><strong>Inbound requests leave it null, deliberately.</strong> A server-side request already
 * carries the client's addressee in the {@code Host} header (HTTP/1.1) or the {@code :authority}
 * pseudo-header (HTTP/2), which is where the protocol puts it. Copying that into a record component
 * would create a second source of truth that can disagree with the first, so a handler reads it
 * through {@link #firstHeader(String)} as before.
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
 * @since 0.5.0
 */
public record HttpRequest(
        HttpMethod method,
        String authority,
        String path,
        HttpVersion version,
        List<HttpHeader> headers,
        LoanedBuffer body
) {

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
     * <p>This is the canonical constructor as it stood before 0.12, retained as a compatibility
     * bridge so that adding {@link #authority()} to a {@code stable} carrier does not break existing
     * callers — the pattern {@code FlowSnapshot} used three times in v0.11. It delegates with a
     * {@code null} authority, which is exactly the behaviour a pre-0.12 caller already had.
     *
     * @param method  HTTP method; non-null
     * @param path    request-target path component; non-null
     * @param version protocol version; non-null
     * @param headers immutable list of header fields; non-null, may be empty
     * @param body    request body buffer, or {@code null} if the request has no body
     * @since 0.5.0
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
     * <p>Iterates the header list linearly — acceptable for the typical small
     * header count ({@code ≤ 100}) encountered in HTTP requests.
     *
     * @param name header name to find; must not be {@code null}
     * @return optional header value
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
     * Returns {@code true} if this request carries a non-null body buffer.
     *
     * @return {@code true} if {@link #body()} is non-null
     */
    public boolean hasBody() {
        return body != null;
    }

    /**
     * Creates a bodyless request.
     *
     * @param method  HTTP method
     * @param path    request path
     * @param version protocol version
     * @param headers header list
     * @return a new {@code HttpRequest} with no body
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
     * @since 0.12.0
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
     * @since 0.8.0
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
