/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequestBodyDecoder;
import eu.exeris.kernel.spi.http.HttpRequestBodyDecoderRegistry;
import eu.exeris.kernel.spi.http.HttpRequestDecodingContext;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.contract.http.AbstractHttpRequestBodyDecoderTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community binding of {@link AbstractHttpRequestBodyDecoderTck} against
 * {@link CommunityJsonRequestBodyDecoder} (Jackson 3) — ADR-036 §7.
 *
 * <p>The abstract base subsumes the former
 * {@code CommunityJsonRequestBodyDecoderSmokeTest}: round-trip decode, malformed-body
 * opacity, and content-type tolerance now live in the contract TCK. The one case the
 * smoke test held that is <em>not</em> a decoder-contract concern — the
 * {@link HttpKernelProviders} {@code ScopedValue} slot read path the generated handler
 * uses — is folded in below as a Community-tier wiring test.
 *
 * @since 0.8.0
 */
@DisplayName("Community: HttpRequestBodyDecoder TCK (Jackson 3)")
class CommunityJsonRequestBodyDecoderTckTest extends AbstractHttpRequestBodyDecoderTck {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Override
    protected HttpRequestBodyDecoder createDecoder() {
        return new CommunityJsonRequestBodyDecoder(MAPPER);
    }

    @Override
    protected String supportedContentType() {
        return "application/json";
    }

    @Override
    protected String structuredSuffixContentType() {
        // application/*+json structured-syntax suffix per RFC 6838 §4.2.8.
        return "application/merge-patch+json";
    }

    @Override
    protected byte[] validEncodedBytes() {
        return "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected byte[] malformedEncodedBytes() {
        // Truncated JSON object — Jackson surfaces JacksonException; driver MUST wrap.
        return "{ \"k\": ".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected Class<?> validTargetType() {
        return Map.class;
    }

    @Override
    protected MemoryAllocator createAllocator() {
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    // ------------------------------------------------------------------------
    // Community-tier wiring (folded in from the former smoke test) — the slot
    // read path the generated handler uses. NOT a decoder-contract concern, so
    // it does not belong in the abstract TCK. Kept in a @Nested class so JUnit
    // collects it alongside the inherited contract suites under this binding.
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("HttpKernelProviders slot wiring")
    class SlotWiring {

        @Test
        @DisplayName("registry is readable via the slot inside scope, empty outside (generated-handler read path)")
        @SuppressWarnings("unchecked") // confined Map cast in the assertion; the SPI is generics-free by design
        void slotBoundDuringDispatch() {
            MemoryAllocator alloc =
                    new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
            try {
                // Source the registry exactly as CommunityHttpProvider.requestBodyDecoderRegistry() does.
                HttpRequestBodyDecoderRegistry registry = new CommunityHttpProvider()
                        .requestBodyDecoderRegistry()
                        .orElseThrow();

                assertThat(HttpKernelProviders.httpRequestBodyDecoderRegistry())
                        .as("slot must be empty outside the kernel scope")
                        .isEmpty();

                ScopedValue.where(HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY, registry).run(() -> {
                    var bound = HttpKernelProviders.httpRequestBodyDecoderRegistry();
                    assertThat(bound).as("slot must be present inside the kernel scope").isPresent();
                    HttpRequestBodyDecoder resolved =
                            bound.orElseThrow().resolve(Map.class, "application/json");
                    assertThat(resolved).as("registry must resolve the JSON decoder").isNotNull();

                    byte[] json = "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8);
                    LoanedBuffer body = alloc.allocateNetwork(json.length);
                    try (body) {
                        MemorySegment.copy(MemorySegment.ofArray(json), 0L, body.segment(), 0L, json.length);
                        body.setSize(json.length);
                        HttpRequestDecodingContext ctx =
                                new HttpRequestDecodingContext(HttpMethod.POST, "/widgets", List.of(), alloc);
                        assertThat((Map<Object, Object>) resolved.decode(body, Map.class, ctx))
                                .containsEntry("k", "v");
                    }
                });

                assertThat(HttpKernelProviders.httpRequestBodyDecoderRegistry())
                        .as("slot must be empty again after the scope closes")
                        .isEmpty();
            } finally {
                alloc.close();
            }
        }
    }
}
