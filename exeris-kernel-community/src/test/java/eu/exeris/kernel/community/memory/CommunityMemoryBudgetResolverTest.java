/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.community.transport.MapConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code memory.offHeapBudgetBytes} resolves to a positive budget, to {@code -1} for none, and is
 * refused at any other value, naming the key.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("Community: CommunityMemoryBudgetResolver")
class CommunityMemoryBudgetResolverTest {

    private static MapConfigProvider with(long value) {
        return MapConfigProvider.ofLongs(Map.of(CommunityMemoryBudgetResolver.KEY, value));
    }

    @Test
    @DisplayName("no provider bound means no budget")
    void nullProviderIsNoBudget() {
        assertThat(CommunityMemoryBudgetResolver.resolve(null)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("an absent key means no budget")
    void absentKeyIsNoBudget() {
        assertThat(CommunityMemoryBudgetResolver.resolve(MapConfigProvider.ofLongs(Map.of()))).isEqualTo(-1L);
    }

    @Test
    @DisplayName("-1 means no budget")
    void minusOneIsNoBudget() {
        assertThat(CommunityMemoryBudgetResolver.resolve(with(-1L))).isEqualTo(-1L);
    }

    @Test
    @DisplayName("a positive value is the budget")
    void positiveIsTheBudget() {
        assertThat(CommunityMemoryBudgetResolver.resolve(with(1_048_576L))).isEqualTo(1_048_576L);
    }

    @Test
    @DisplayName("0 is refused, naming the key")
    void zeroIsRefused() {
        assertThatThrownBy(() -> CommunityMemoryBudgetResolver.resolve(with(0L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(CommunityMemoryBudgetResolver.KEY);
    }

    @Test
    @DisplayName("a value below -1 is refused, naming the key")
    void belowMinusOneIsRefused() {
        assertThatThrownBy(() -> CommunityMemoryBudgetResolver.resolve(with(-2L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(CommunityMemoryBudgetResolver.KEY);
    }
}
