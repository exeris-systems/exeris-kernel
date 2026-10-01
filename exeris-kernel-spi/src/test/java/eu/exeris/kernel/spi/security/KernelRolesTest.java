/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KernelRoles — canonical system-role bit mapping (ADR-014)")
class KernelRolesTest {

    @Test
    @DisplayName("Reserved system-role bits are unique and within [0, SYSTEM_ROLE_BIT_LIMIT)")
    void systemBitsAreUniqueAndWithinReservedRange() {
        int[] bits = {
                KernelRoles.BIT_SYSTEM,
                KernelRoles.BIT_ADMIN,
                KernelRoles.BIT_OPERATOR,
                KernelRoles.BIT_USER
        };
        for (int bit : bits) {
            assertThat(bit)
                    .as("system role bit must be within the reserved range")
                    .isBetween(0, KernelRoles.SYSTEM_ROLE_BIT_LIMIT - 1);
        }
        assertThat(bits).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("SYSTEM_ROLE_BIT_LIMIT leaves head-room for application-defined roles in a long mask")
    void reservedRangeFitsInLongMask() {
        assertThat(KernelRoles.SYSTEM_ROLE_BIT_LIMIT)
                .as("system reservation must leave the majority of a long mask for application roles")
                .isLessThan(Long.SIZE);
    }

    @Test
    @DisplayName("systemRoleBit() resolves canonical names; unknown names return -1")
    void resolvesCanonicalNames() {
        assertThat(KernelRoles.systemRoleBit(KernelRoles.ROLE_SYSTEM)).isEqualTo(KernelRoles.BIT_SYSTEM);
        assertThat(KernelRoles.systemRoleBit(KernelRoles.ROLE_ADMIN)).isEqualTo(KernelRoles.BIT_ADMIN);
        assertThat(KernelRoles.systemRoleBit(KernelRoles.ROLE_OPERATOR)).isEqualTo(KernelRoles.BIT_OPERATOR);
        assertThat(KernelRoles.systemRoleBit(KernelRoles.ROLE_USER)).isEqualTo(KernelRoles.BIT_USER);
        assertThat(KernelRoles.systemRoleBit("ROLE_GHOST")).isEqualTo(-1);
        assertThat(KernelRoles.systemRoleBit(null)).isEqualTo(-1);
    }

    @Test
    @DisplayName("systemRoleMask() returns a single-bit mask matching systemRoleBit()")
    void maskMatchesBit() {
        assertThat(KernelRoles.systemRoleMask(KernelRoles.ROLE_ADMIN))
                .isEqualTo(1L << KernelRoles.BIT_ADMIN);
        assertThat(KernelRoles.systemRoleMask("ROLE_GHOST")).isZero();
        assertThat(Long.bitCount(KernelRoles.systemRoleMask(KernelRoles.ROLE_USER))).isOne();
    }

    @Test
    @DisplayName("Role-name constants are stable strings (no accidental refactors)")
    void roleNameConstantsAreStable() {
        assertThat(KernelRoles.ROLE_SYSTEM).isEqualTo("ROLE_SYSTEM");
        assertThat(KernelRoles.ROLE_ADMIN).isEqualTo("ROLE_ADMIN");
        assertThat(KernelRoles.ROLE_OPERATOR).isEqualTo("ROLE_OPERATOR");
        assertThat(KernelRoles.ROLE_USER).isEqualTo("ROLE_USER");
    }
}
