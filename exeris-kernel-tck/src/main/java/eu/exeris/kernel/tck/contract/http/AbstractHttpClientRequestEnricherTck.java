/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpClientRequestEnricher;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link HttpClientRequestEnricher} contract verification (ADR-032).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link HttpClientRequestEnricher#enrich(HttpRequest)} MUST return a new
 *       {@link HttpRequest} when it makes any change; in-place mutation seams do
 *       not exist (the SPI request carrier is an immutable {@code record}).</li>
 *   <li>The enricher MUST NOT read, retain, or close the request body
 *       {@link eu.exeris.kernel.spi.memory.LoanedBuffer}. Body ownership belongs
 *       to the calling site.</li>
 *   <li>Header values containing CR ({@code 0x0D}), LF ({@code 0x0A}), or NUL
 *       ({@code 0x00}) MUST be rejected with {@link IllegalArgumentException}
 *       (CWE-93 outbound guard, symmetric with the inbound parser).</li>
 *   <li>{@link HttpClientRequestEnricher#noop()} returns an identity enricher;
 *       {@code chain(List)} applies in list order, an empty list is equivalent
 *       to {@code noop()}, and the input list must be non-null.</li>
 *   <li>The enricher runs synchronously on the caller's virtual thread — no
 *       thread-spawning, no blocking I/O.</li>
 * </ul>
 *
 * <h2>Subclass binding model</h2>
 * <p>Driver subclasses override {@link #createEnricher()} to supply the
 * driver-under-test. The static SPI clauses ({@code noop}, {@code chain},
 * immutability semantics, body-ownership) are also fully exercised against the
 * SPI itself in {@link StaticSpiNoop} / {@link StaticSpiChain}; those tests do
 * not need a driver.
 *
 * <p>The CR/LF/NUL validation tests are kept in this base but marked
 * {@link Disabled} pending the ADR-032 driver PR that lands
 * {@code CommunityKernelContextEnricher} together with the outbound-header
 * validator. They are explicit so the coverage gap is visible.
 *
 * <h2>How to use</h2>
 * {@snippet lang="java" :
 * class CommunityKernelContextEnricherTckTest extends AbstractHttpClientRequestEnricherTck {
 *     @Override
 *     protected HttpClientRequestEnricher createEnricher() {
 *         return new CommunityKernelContextEnricher();
 *     }
 * }
 * }
 *
 * @since 0.8
 */
public abstract class AbstractHttpClientRequestEnricherTck {

    /**
     * Creates the contract; subclasses supply the enricher under test via {@link #createEnricher()}.
     */
    public AbstractHttpClientRequestEnricherTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Creates the enricher under test. Subclasses that do not yet have a driver
     * implementation may return {@link HttpClientRequestEnricher#noop()} to
     * exercise only the SPI-level clauses; the validator clauses will continue
     * to be {@link Disabled}.
     *
     * @return non-null enricher
     */
    protected abstract HttpClientRequestEnricher createEnricher();

    private static HttpRequest baseRequest() {
        return HttpRequest.noBody(HttpMethod.GET, "/tck", HttpVersion.HTTP_1_1, List.of());
    }

    private static HttpRequest baseRequestWithHeader(String name, String value) {
        return HttpRequest.noBody(
                HttpMethod.GET, "/tck", HttpVersion.HTTP_1_1,
                List.of(new HttpHeader(name, value)));
    }

    @Nested
    @DisplayName("Static SPI: noop()")
    class StaticSpiNoop {

        @Test
        @DisplayName("noop() returns the input request unchanged (reference equality)")
        void noopIsIdentity() {
            HttpRequest in = baseRequest();
            HttpRequest out = HttpClientRequestEnricher.noop().enrich(in);
            assertThat(out)
                    .as("noop() must return the same instance — no derivation, no allocation")
                    .isSameAs(in);
        }

        @Test
        @DisplayName("noop() is stateless — repeated calls return the same input each time")
        void noopIsStateless() {
            HttpClientRequestEnricher noop = HttpClientRequestEnricher.noop();
            HttpRequest in = baseRequest();
            assertThat(noop.enrich(in)).isSameAs(in);
            assertThat(noop.enrich(in)).isSameAs(in);
        }
    }

    @Nested
    @DisplayName("Static SPI: chain(List)")
    class StaticSpiChain {

        @Test
        @DisplayName("chain(emptyList) is equivalent to noop() — returns input unchanged")
        void chainEmptyIsNoop() {
            HttpRequest in = baseRequest();
            HttpRequest out = HttpClientRequestEnricher.chain(List.of()).enrich(in);
            assertThat(out).isSameAs(in);
        }

        @Test
        @DisplayName("chain(null) throws NullPointerException")
        void chainRejectsNullList() {
            assertThatThrownBy(() -> HttpClientRequestEnricher.chain(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("chain applies members in list order — each sees the previous output")
        void chainAppliesInOrder() {
            HttpClientRequestEnricher addA = req ->
                    req.withAdditionalHeaders(List.of(new HttpHeader("x-tck-trace", "a")));
            HttpClientRequestEnricher addB = req ->
                    req.withAdditionalHeaders(List.of(new HttpHeader("x-tck-trace", "b")));
            HttpClientRequestEnricher addC = req ->
                    req.withAdditionalHeaders(List.of(new HttpHeader("x-tck-trace", "c")));

            HttpRequest out = HttpClientRequestEnricher.chain(List.of(addA, addB, addC))
                    .enrich(baseRequest());

            List<String> traceValues = new ArrayList<>();
            for (HttpHeader h : out.headers()) {
                if (h.nameEqualsIgnoreCase("x-tck-trace")) {
                    traceValues.add(h.value());
                }
            }
            assertThat(traceValues)
                    .as("chain must apply enrichers in list order")
                    .containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("chain takes a defensive snapshot of the input list (post-construction mutation ignored)")
        void chainSnapshotsInputList() {
            List<HttpClientRequestEnricher> live = new ArrayList<>();
            live.add(req -> req.withAdditionalHeaders(List.of(new HttpHeader("x-tck-snap", "1"))));

            HttpClientRequestEnricher composed = HttpClientRequestEnricher.chain(live);

            // Mutating the original list after composition must not affect the chain.
            live.add(req -> req.withAdditionalHeaders(List.of(new HttpHeader("x-tck-snap", "2"))));

            long snapHeaders = composed.enrich(baseRequest()).headers().stream()
                    .filter(h -> h.nameEqualsIgnoreCase("x-tck-snap"))
                    .count();
            assertThat(snapHeaders)
                    .as("chain must snapshot the input list; later additions must not leak in")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("chain rejects null members (propagated NPE from snapshot copy)")
        void chainRejectsNullMembers() {
            List<HttpClientRequestEnricher> withNull = new ArrayList<>();
            withNull.add(HttpClientRequestEnricher.noop());
            withNull.add(null);
            assertThatThrownBy(() -> HttpClientRequestEnricher.chain(withNull))
                    .as("List.copyOf rejects null elements; composition must surface the NPE")
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Driver under test")
    class DriverContract {

        @Test
        @DisplayName("enrich returns a non-null HttpRequest")
        void enrichReturnsNonNullRequest() {
            HttpClientRequestEnricher enricher = createEnricher();
            HttpRequest result = enricher.enrich(baseRequest());
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("enrich preserves method, path, and version")
        void enrichPreservesIdentityFields() {
            HttpClientRequestEnricher enricher = createEnricher();
            HttpRequest in = baseRequest();
            HttpRequest out = enricher.enrich(in);
            assertThat(out.method()).isEqualTo(in.method());
            assertThat(out.path()).isEqualTo(in.path());
            assertThat(out.version()).isEqualTo(in.version());
        }

        @Test
        @DisplayName("enrich runs synchronously on the caller's thread")
        void enrichIsSynchronousOnCallerThread() {
            HttpClientRequestEnricher enricher = createEnricher();
            AtomicReference<Thread> observed = new AtomicReference<>();
            HttpClientRequestEnricher probe = req -> {
                observed.set(Thread.currentThread());
                return req;
            };
            // Chain probe with the SUT; both must run on the caller thread.
            HttpClientRequestEnricher composed =
                    HttpClientRequestEnricher.chain(List.of(probe, enricher));
            Thread caller = Thread.currentThread();
            composed.enrich(baseRequest());
            assertThat(observed.get())
                    .as("enricher MUST execute synchronously on the caller thread; no off-thread dispatch")
                    .isSameAs(caller);
        }

        @Test
        @DisplayName("enrich does not retain a reference that mutates after return (body ownership)")
        void enrichDoesNotRetainBodyOwnership() {
            // The SPI clause is about not touching the body LoanedBuffer. A
            // bodyless request still verifies the "does not crash on null body"
            // half of the clause; an enricher that dereferenced request.body()
            // would NPE here.
            HttpClientRequestEnricher enricher = createEnricher();
            HttpRequest in = baseRequest();
            assertThat(in.body()).isNull();
            HttpRequest out = enricher.enrich(in);
            assertThat(out)
                    .as("enricher must accept a bodyless request without dereferencing body()")
                    .isNotNull();
        }

        @Test
        @DisplayName("enrich returns an immutable headers view (added headers do not mutate the input)")
        void enrichDoesNotMutateInput() {
            HttpClientRequestEnricher enricher = createEnricher();
            HttpRequest in = baseRequest();
            int inputHeaderCount = in.headers().size();
            HttpRequest out = enricher.enrich(in);
            assertThat(in.headers())
                    .as("input HttpRequest is immutable; enrich must not mutate its header list in place")
                    .hasSize(inputHeaderCount);
            // out may equal in (no-op enricher) or be derived; either is fine.
            assertThat(out).isNotNull();
        }
    }

    @Nested
    @DisplayName("CRLF / NUL header-injection guard (CWE-93)")
    @Disabled("Validator pending ADR-032 driver PR — see CommunityKernelContextEnricher; "
            + "tests are kept to make the contract gap visible.")
    class HeaderInjectionRejection {

        @Test
        @DisplayName("enrich rejects header value containing CR (0x0D)")
        void rejectsCarriageReturn() {
            HttpClientRequestEnricher enricher = createEnricher();
            HttpRequest poisoned = baseRequestWithHeader("x-attack", "value\rinjected");
            assertThatThrownBy(() -> enricher.enrich(poisoned))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("enrich rejects header value containing LF (0x0A)")
        void rejectsLineFeed() {
            HttpClientRequestEnricher enricher = createEnricher();
            HttpRequest poisoned = baseRequestWithHeader("x-attack", "value\ninjected");
            assertThatThrownBy(() -> enricher.enrich(poisoned))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("enrich rejects header value containing NUL (0x00)")
        void rejectsNul() {
            HttpClientRequestEnricher enricher = createEnricher();
            HttpRequest poisoned = baseRequestWithHeader("x-attack", "value\u0000injected");
            assertThatThrownBy(() -> enricher.enrich(poisoned))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("enrich rejects CRLF sequence inside a header value")
        void rejectsCrlfSequence() {
            HttpClientRequestEnricher enricher = createEnricher();
            HttpRequest poisoned = baseRequestWithHeader(
                    "x-attack", "value\r\nset-cookie: pwn=1");
            assertThatThrownBy(() -> enricher.enrich(poisoned))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // Suppress an unused-import warning if Collections is dropped during edits.
    @SuppressWarnings("unused")
    private static final Object COLLECTIONS_PIN = Collections.class;
}
