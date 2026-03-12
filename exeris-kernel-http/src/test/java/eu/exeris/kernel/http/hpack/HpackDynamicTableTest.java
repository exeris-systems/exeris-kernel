/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.http.hpack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("L0: HpackDynamicTable — RFC 7541 §4 Contract")
class HpackDynamicTableTest {

    @Test
    @DisplayName("New table is empty")
    void newTableIsEmpty() {
        HpackDynamicTable table = new HpackDynamicTable(4096);
        assertThat(table.size()).isZero();
        assertThat(table.byteSize()).isZero();
    }

    @Test
    @DisplayName("Constructor rejects negative maxTableSize")
    void constructorRejectsNegativeMaxTableSize() {
        assertThatThrownBy(() -> new HpackDynamicTable(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTableSize");
    }

    @Nested
    @DisplayName("add() — insertion and eviction")
    class AddContract {

        @Test
        @DisplayName("Single entry: name + value + 32 overhead")
        void singleEntry() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            table.add("custom-key", "custom-value");
            assertThat(table.size()).isEqualTo(1);
            assertThat(table.byteSize()).isEqualTo(10 + 12 + 32);
            assertThat(table.getName(0)).isEqualTo("custom-key");
            assertThat(table.getValue(0)).isEqualTo("custom-value");
        }

        @Test
        @DisplayName("Newest entry is at index 0")
        void newestAtIndexZero() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            table.add("first", "1");
            table.add("second", "2");
            assertThat(table.getName(0)).isEqualTo("second");
            assertThat(table.getName(1)).isEqualTo("first");
        }

        @Test
        @DisplayName("Eviction occurs when table exceeds max size")
        void evictionOnOverflow() {
            HpackDynamicTable table = new HpackDynamicTable(100);
            table.add("name", "a]");
            assertThat(table.size()).isEqualTo(1);

            while (table.size() == 1) {
                table.add("name", "a]");
            }
            assertThat(table.byteSize()).isLessThanOrEqualTo(100);
        }

        @Test
        @DisplayName("Eviction removes OLDEST entry first (FIFO)")
        void evictionRemovesOldestEntryFirst() {
            // Capacity for exactly 2 entries of (key-N: v-N) where each entry = 5+3+32 = 40 bytes
            HpackDynamicTable table = new HpackDynamicTable(80);
            table.add("k-1", "v-1");
            table.add("k-2", "v-2");
            assertThat(table.size()).isEqualTo(2);

            // Adding a third entry evicts the oldest ("k-1")
            table.add("k-3", "v-3");
            assertThat(table.size()).isEqualTo(2);
            assertThat(table.getName(0)).isEqualTo("k-3");
            assertThat(table.getName(1)).isEqualTo("k-2");
        }

        @Test
        @DisplayName("Circular buffer wraps correctly after many insertions")
        void circularBufferWrapsCorrectly() {
            // Table holds ~4 entries of 40 bytes each (160 bytes)
            HpackDynamicTable table = new HpackDynamicTable(160);
            for (int i = 0; i < 70; i++) {
                table.add("k-" + i, "v-" + i);
            }
            // After 70 insertions the circular buffer has wrapped multiple times.
            // Newest 4 entries are accessible at indices 0..3 in most-recent-first order.
            assertThat(table.size()).isLessThanOrEqualTo(4);
            assertThat(table.getName(0)).isEqualTo("k-69");
        }

        @Test
        @DisplayName("Entry larger than max size empties the table")
        void oversizedEntryEmptiesTable() {
            HpackDynamicTable table = new HpackDynamicTable(50);
            table.add("short", "x");
            assertThat(table.size()).isEqualTo(1);

            table.add("this-name-is-very-long-and-exceeds-the-max-size", "value");
            assertThat(table.size()).isZero();
        }
    }

    @Nested
    @DisplayName("setMaxSize() — dynamic resize with eviction")
    class ResizeContract {

        @Test
        @DisplayName("Reducing max size evicts oldest entries")
        void reducingMaxSizeEvicts() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            table.add("a", "1");
            table.add("b", "2");
            assertThat(table.size()).isEqualTo(2);

            table.setMaxSize(0);
            assertThat(table.size()).isZero();
        }

        @Test
        @DisplayName("setMaxSize rejects negative values")
        void setMaxSizeRejectsNegative() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            assertThatThrownBy(() -> table.setMaxSize(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("newMaxSize");
        }
    }

    @Nested
    @DisplayName("find() — combined static + dynamic lookup")
    class FindContract {

        @Test
        @DisplayName("Dynamic entry found with full match")
        void dynamicFullMatch() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            table.add("x-custom", "bar");
            int result = table.find("x-custom", "bar");
            assertThat(result & 0x10000).isNotZero();
            assertThat(result & 0xFFFF).isEqualTo(HpackStaticTable.SIZE + 1);
        }

        @Test
        @DisplayName("Static entry preferred over dynamic for full match")
        void staticPreferred() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            table.add(":method", "GET");
            int result = table.find(":method", "GET");
            assertThat(result & 0x10000).isNotZero();
            assertThat(result & 0xFFFF).isEqualTo(2);
        }

        @Test
        @DisplayName("Dynamic name-only match is preferred over static name-only match")
        void dynamicNameOnlyPreferredOverStatic() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            // :path is in static table (name-only candidates: indices 4 and 5).
            // Add :path with value "/custom" to dynamic table as well.
            table.add(":path", "/custom");
            // Now look up :path with "/other" — no full match anywhere.
            // Dynamic has name-only at SIZE+1, static has name-only at 4 or 5.
            int result = table.find(":path", "/other");
            assertThat(result & 0x10000).isZero();
            // Dynamic index must be preferred: index > static table size
            assertThat(result & 0xFFFF).isGreaterThan(HpackStaticTable.SIZE);
        }

        @Test
        @DisplayName("Falls back to static name-only when no dynamic name match")
        void staticNameOnlyFallback() {
            HpackDynamicTable table = new HpackDynamicTable(4096);
            // Static table has :method but dynamic table is empty
            int result = table.find(":method", "PATCH");
            assertThat(result & 0x10000).isZero();
            // Should return static index for :method (index 2 or 3 depending on static table)
            assertThat(result & 0xFFFF).isPositive().isLessThanOrEqualTo(HpackStaticTable.SIZE);
        }
    }

    @Test
    @DisplayName("Index out of range throws IndexOutOfBoundsException")
    void indexOutOfRange() {
        HpackDynamicTable table = new HpackDynamicTable(4096);
        assertThatThrownBy(() -> table.getName(0))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }
}
