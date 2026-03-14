/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.http.hpack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("L0: HpackStaticTable — RFC 7541 Appendix A")
class HpackStaticTableTest {

    @Test
    @DisplayName("Static table has 61 entries")
    void sizeIs61() {
        assertThat(HpackStaticTable.SIZE).isEqualTo(61);
    }

    @Test
    @DisplayName("Index 1 → :authority (empty value)")
    void index1Authority() {
        assertThat(HpackStaticTable.getName(1)).isEqualTo(":authority");
        assertThat(HpackStaticTable.getValue(1)).isEmpty();
    }

    @Test
    @DisplayName("Index 2 → :method GET")
    void index2MethodGet() {
        assertThat(HpackStaticTable.getName(2)).isEqualTo(":method");
        assertThat(HpackStaticTable.getValue(2)).isEqualTo("GET");
    }

    @Test
    @DisplayName("Index 3 → :method POST")
    void index3MethodPost() {
        assertThat(HpackStaticTable.getName(3)).isEqualTo(":method");
        assertThat(HpackStaticTable.getValue(3)).isEqualTo("POST");
    }

    @Test
    @DisplayName("Index 8 → :status 200")
    void index8Status200() {
        assertThat(HpackStaticTable.getName(8)).isEqualTo(":status");
        assertThat(HpackStaticTable.getValue(8)).isEqualTo("200");
    }

    @Test
    @DisplayName("Index 61 → www-authenticate (empty value)")
    void index61WwwAuthenticate() {
        assertThat(HpackStaticTable.getName(61)).isEqualTo("www-authenticate");
        assertThat(HpackStaticTable.getValue(61)).isEmpty();
    }

    @Test
    @DisplayName("Index 0 throws IndexOutOfBoundsException")
    void indexZeroThrows() {
        assertThatThrownBy(() -> HpackStaticTable.getName(0))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("Index 62 throws IndexOutOfBoundsException")
    void index62Throws() {
        assertThatThrownBy(() -> HpackStaticTable.getName(62))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Nested
    @DisplayName("find() — combined name+value lookup")
    class FindContract {

        @Test
        @DisplayName("Full match: :method GET → index 2, full=true")
        void fullMatchMethodGet() {
            int result = HpackStaticTable.find(":method", "GET");
            assertThat(result & 0x100).isNotZero();
            assertThat(result & 0xFF).isEqualTo(2);
        }

        @Test
        @DisplayName("Name-only match: :method OPTIONS → index 2, full=false")
        void nameOnlyMatchMethodOptions() {
            int result = HpackStaticTable.find(":method", "OPTIONS");
            assertThat(result & 0x100).isZero();
            assertThat(result & 0xFF).isEqualTo(2);
        }

        @Test
        @DisplayName("No match: x-custom FOO → 0")
        void noMatch() {
            int result = HpackStaticTable.find("x-custom", "FOO");
            assertThat(result).isZero();
        }
    }
}

