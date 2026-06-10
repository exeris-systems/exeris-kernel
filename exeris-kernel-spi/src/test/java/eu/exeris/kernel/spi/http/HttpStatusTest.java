/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for {@link HttpStatus} — code-range validation and RFC 9110 §15
 * status-class predicates (v0.9 Sprint 4c Phase 3 SPI coverage).
 */
@DisplayName("HttpStatus — validation + status-class predicates")
class HttpStatusTest {

    @Nested
    @DisplayName("compact-constructor validation")
    class Validation {

        @ParameterizedTest
        @ValueSource(ints = {100, 200, 404, 599})
        @DisplayName("accepts codes in the valid 100–599 band")
        void acceptsValidCodes(int code) {
            assertThat(new HttpStatus(code, "x").code()).isEqualTo(code);
        }

        @ParameterizedTest
        @ValueSource(ints = {99, 0, -1, 600, 1000})
        @DisplayName("rejects codes outside 100–599")
        void rejectsOutOfBandCodes(int code) {
            assertThatThrownBy(() -> new HttpStatus(code, "x"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("status-class predicates across boundaries")
    class Predicates {

        @Test
        @DisplayName("1xx is informational only")
        void informational() {
            HttpStatus s = new HttpStatus(100, "Continue");
            assertThat(s.isInformational()).isTrue();
            assertThat(s.isSuccessful()).isFalse();
            assertThat(s.isError()).isFalse();
            assertThat(new HttpStatus(199, "x").isInformational()).isTrue();
        }

        @Test
        @DisplayName("2xx is successful only (boundaries 200/299)")
        void successful() {
            assertThat(new HttpStatus(200, "OK").isSuccessful()).isTrue();
            assertThat(new HttpStatus(299, "x").isSuccessful()).isTrue();
            assertThat(new HttpStatus(199, "x").isSuccessful()).isFalse();
            assertThat(new HttpStatus(300, "x").isSuccessful()).isFalse();
        }

        @Test
        @DisplayName("3xx is redirection only (boundaries 300/399)")
        void redirection() {
            assertThat(new HttpStatus(300, "x").isRedirection()).isTrue();
            assertThat(new HttpStatus(399, "x").isRedirection()).isTrue();
            assertThat(new HttpStatus(299, "x").isRedirection()).isFalse();
            assertThat(new HttpStatus(400, "x").isRedirection()).isFalse();
        }

        @Test
        @DisplayName("4xx is client error (boundaries 400/499) and counts as error")
        void clientError() {
            HttpStatus s = new HttpStatus(404, "Not Found");
            assertThat(s.isClientError()).isTrue();
            assertThat(s.isServerError()).isFalse();
            assertThat(s.isError()).isTrue();
            assertThat(new HttpStatus(499, "x").isClientError()).isTrue();
            assertThat(new HttpStatus(500, "x").isClientError()).isFalse();
        }

        @Test
        @DisplayName("5xx is server error (boundaries 500/599) and counts as error")
        void serverError() {
            HttpStatus s = new HttpStatus(500, "Internal Server Error");
            assertThat(s.isServerError()).isTrue();
            assertThat(s.isClientError()).isFalse();
            assertThat(s.isError()).isTrue();
            assertThat(new HttpStatus(599, "x").isServerError()).isTrue();
        }

        @Test
        @DisplayName("isError covers 4xx+5xx but not 1xx/2xx/3xx")
        void isErrorBoundary() {
            assertThat(new HttpStatus(399, "x").isError()).isFalse();
            assertThat(new HttpStatus(400, "x").isError()).isTrue();
            assertThat(new HttpStatus(599, "x").isError()).isTrue();
        }
    }
}
