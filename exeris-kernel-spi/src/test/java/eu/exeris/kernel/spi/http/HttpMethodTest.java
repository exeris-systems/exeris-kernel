/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for {@link HttpMethod} RFC 9110 §9.2 semantic predicates —
 * safe / idempotent / typical-request-body (v0.9 Sprint 4c Phase 3 SPI coverage).
 */
@DisplayName("HttpMethod — RFC 9110 method semantics")
class HttpMethodTest {

    private static final Set<HttpMethod> SAFE =
            EnumSet.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE);
    private static final Set<HttpMethod> IDEMPOTENT =
            EnumSet.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE,
                    HttpMethod.PUT, HttpMethod.DELETE);
    private static final Set<HttpMethod> TYPICAL_BODY =
            EnumSet.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);

    @ParameterizedTest
    @EnumSource(HttpMethod.class)
    @DisplayName("isSafe matches the RFC safe-method set exactly")
    void isSafe(HttpMethod method) {
        assertThat(method.isSafe()).isEqualTo(SAFE.contains(method));
    }

    @ParameterizedTest
    @EnumSource(HttpMethod.class)
    @DisplayName("isIdempotent matches the RFC idempotent-method set exactly")
    void isIdempotent(HttpMethod method) {
        assertThat(method.isIdempotent()).isEqualTo(IDEMPOTENT.contains(method));
    }

    @ParameterizedTest
    @EnumSource(HttpMethod.class)
    @DisplayName("hasTypicalRequestBody matches POST/PUT/PATCH exactly")
    void hasTypicalRequestBody(HttpMethod method) {
        assertThat(method.hasTypicalRequestBody()).isEqualTo(TYPICAL_BODY.contains(method));
    }

    @Test
    @DisplayName("every safe method is idempotent (RFC 9110 §9.2.2 superset relation)")
    void safeImpliesIdempotent() {
        for (HttpMethod m : HttpMethod.values()) {
            if (m.isSafe()) {
                assertThat(m.isIdempotent())
                        .as("%s is safe so must be idempotent", m)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("CONNECT is neither safe, idempotent, nor body-typical")
    void connectIsNeither() {
        assertThat(HttpMethod.CONNECT.isSafe()).isFalse();
        assertThat(HttpMethod.CONNECT.isIdempotent()).isFalse();
        assertThat(HttpMethod.CONNECT.hasTypicalRequestBody()).isFalse();
    }
}
