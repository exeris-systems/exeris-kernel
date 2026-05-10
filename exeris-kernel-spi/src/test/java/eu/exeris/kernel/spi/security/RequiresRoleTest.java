/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("@RequiresRole — SPI annotation contract (ADR-014)")
class RequiresRoleTest {

    @Test
    @DisplayName("Retention is SOURCE — annotation never reaches the class file")
    void retentionIsSource() {
        Retention retention = RequiresRole.class.getAnnotation(Retention.class);
        assertThat(retention)
                .as("@RequiresRole MUST declare @Retention(SOURCE) per ADR-014 §3")
                .isNotNull();
        assertThat(retention.value())
                .as("Reflection at runtime MUST never observe @RequiresRole — that would "
                        + "violate the No-Waste-Compute hot-path contract")
                .isEqualTo(RetentionPolicy.SOURCE);
    }

    @Test
    @DisplayName("Targets are METHOD and TYPE only")
    void targetsAreMethodAndType() {
        Target target = RequiresRole.class.getAnnotation(Target.class);
        assertThat(target).isNotNull();
        assertThat(target.value())
                .containsExactlyInAnyOrder(ElementType.METHOD, ElementType.TYPE);
    }

    @Test
    @DisplayName("Default match strategy is RoleMatch.ANY")
    void defaultMatchIsAny() throws NoSuchMethodException {
        Object defaultValue = RequiresRole.class.getMethod("match").getDefaultValue();
        assertThat(defaultValue).isEqualTo(RoleMatch.ANY);
    }

    @Test
    @DisplayName("RoleMatch carries exactly ANY and ALL — no surprises for the APT processor")
    void roleMatchEnumShape() {
        assertThat(RoleMatch.values())
                .containsExactly(RoleMatch.ANY, RoleMatch.ALL);
    }
}
