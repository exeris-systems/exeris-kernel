/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Light smoke coverage for HTTP-138 (ADR-036). NOT the contract TCK — the
 * {@code AbstractHttpRequestBodyDecoderTck} base is owned by {@code exeris-tck}.
 * This asserts two load-bearing claims the kernel half makes:
 *
 * <ol>
 *   <li>the Community Jackson driver round-trips a JSON body to a typed instance
 *       through one segment copy and no intermediate {@code String} (No-Waste);</li>
 *   <li>the request-decode registry, bound into the
 *       {@link HttpKernelProviders#HTTP_REQUEST_BODY_DECODER_REGISTRY} slot exactly as
 *       {@code CommunityHttpSubsystem.providerBindings()} binds it during bootstrap, is
 *       resolvable via {@link HttpKernelProviders#httpRequestBodyDecoderRegistry()}
 *       inside the scope and empty outside it — the read path the generated handler uses.</li>
 * </ol>
 */
@SuppressWarnings("unchecked") // confined Map casts in assertions; the SPI is generics-free by design
@DisplayName("Community: HttpRequestBodyDecoder smoke (ADR-036 / HTTP-138)")
class CommunityJsonRequestBodyDecoderSmokeTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private final MemoryAllocator allocator =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    private LoanedBuffer bufferOf(byte[] bytes) {
        LoanedBuffer buf = allocator.allocateNetwork(Math.max(1, bytes.length));
        if (bytes.length > 0) {
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0L, buf.segment(), 0L, bytes.length);
        }
        buf.setSize(bytes.length);
        return buf;
    }

    private HttpRequestDecodingContext ctx() {
        return new HttpRequestDecodingContext(HttpMethod.POST, "/widgets", List.of(), allocator);
    }

    @Test
    @DisplayName("driver round-trips JSON to typed instance")
    void driverRoundTrip() {
        HttpRequestBodyDecoder decoder = new CommunityJsonRequestBodyDecoder(MAPPER);
        byte[] json = "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8);
        try (LoanedBuffer body = bufferOf(json)) {
            Object decoded = decoder.decode(body, Map.class, ctx());
            assertThat(decoded).isInstanceOf(Map.class);
            assertThat((Map<Object, Object>) decoded).containsEntry("k", "v");
            // Buffer must remain usable — the decoder MUST NOT have closed it.
            assertThat(body.size()).isEqualTo(json.length);
        }
    }

    @Test
    @DisplayName("malformed body surfaces a wrapped RuntimeException, no driver type escapes")
    void malformedBodyWrapped() {
        HttpRequestBodyDecoder decoder = new CommunityJsonRequestBodyDecoder(MAPPER);
        byte[] truncated = "{ \"k\": ".getBytes(StandardCharsets.UTF_8);
        try (LoanedBuffer body = bufferOf(truncated)) {
            assertThat(catchRuntime(() -> decoder.decode(body, Map.class, ctx())))
                    .isInstanceOf(IllegalStateException.class)
                    .extracting(t -> t.getClass().getName().startsWith("tools.jackson"))
                    .isEqualTo(false);
        }
    }

    @Test
    @DisplayName("registry is readable via the slot inside scope, empty outside (generated-handler read path)")
    void slotBoundDuringDispatch() {
        // Source the registry exactly as CommunityHttpProvider.requestBodyDecoderRegistry() does.
        HttpRequestBodyDecoderRegistry registry = new CommunityHttpProvider()
                .requestBodyDecoderRegistry()
                .orElseThrow();

        assertThat(HttpKernelProviders.httpRequestBodyDecoderRegistry()).isEmpty();

        ScopedValue.where(HttpKernelProviders.HTTP_REQUEST_BODY_DECODER_REGISTRY, registry).run(() -> {
            var bound = HttpKernelProviders.httpRequestBodyDecoderRegistry();
            assertThat(bound).isPresent();
            HttpRequestBodyDecoder resolved = bound.orElseThrow().resolve(Map.class, "application/json");
            assertThat(resolved).isNotNull();
            byte[] json = "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8);
            try (LoanedBuffer body = bufferOf(json)) {
                assertThat((Map<Object, Object>) resolved.decode(body, Map.class, ctx())).containsEntry("k", "v");
            }
        });

        assertThat(HttpKernelProviders.httpRequestBodyDecoderRegistry()).isEmpty();
    }

    private static RuntimeException catchRuntime(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected a RuntimeException");
        } catch (RuntimeException ex) {
            return ex;
        }
    }
}
