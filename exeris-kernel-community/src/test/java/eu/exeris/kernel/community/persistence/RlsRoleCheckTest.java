/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RlsRoleCheck — role attribute decision, no database")
class RlsRoleCheckTest {

    private static final String PROVIDER = "ExerisCommunity/JDBC";

    @ParameterizedTest(name = "superuser={0} bypassRls={1} -> bypasses={2}, attribute=''{3}''")
    @DisplayName("each attribute combination maps to the bypass verdict and its name")
    @CsvSource(value = {
            "false, false, false, ''",
            "true,  false, true,  SUPERUSER",
            "false, true,  true,  BYPASSRLS",
            "true,  true,  true,  'SUPERUSER,BYPASSRLS'"
    }, quoteCharacter = '\'')
    void attributeCombinations(boolean superuser, boolean bypassRls, boolean bypasses, String attribute) {
        RlsRoleCheck.RoleAttributes attributes = new RlsRoleCheck.RoleAttributes("r", superuser, bypassRls);

        assertThat(attributes.bypasses()).isEqualTo(bypasses);
        assertThat(attributes.attribute()).isEqualTo(attribute);
    }

    @Test
    @DisplayName("a bypassing role without consent is refused with the exact code and rawArgs")
    void refusesWithoutConsent() {
        RlsRoleCheck.RoleAttributes attributes = new RlsRoleCheck.RoleAttributes("app_owner", true, true);

        assertThatThrownBy(() -> RlsRoleCheck.refuseUnlessAllowed(attributes, false, PROVIDER))
                .isInstanceOfSatisfying(PersistenceProviderException.class, refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5009);
                    assertThat(refusal.rawArgs()).containsExactly(PROVIDER, "app_owner", "SUPERUSER,BYPASSRLS");
                });
    }

    @Test
    @DisplayName("a bypassing role with consent is not refused")
    void consentSuppressesRefusal() {
        RlsRoleCheck.RoleAttributes attributes = new RlsRoleCheck.RoleAttributes("app_owner", false, true);

        assertThatCode(() -> RlsRoleCheck.refuseUnlessAllowed(attributes, true, PROVIDER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an ordinary role is not refused whatever the consent")
    void ordinaryRoleIsNeverRefused() {
        RlsRoleCheck.RoleAttributes attributes = new RlsRoleCheck.RoleAttributes("app", false, false);

        assertThatCode(() -> RlsRoleCheck.refuseUnlessAllowed(attributes, false, PROVIDER))
                .doesNotThrowAnyException();
    }
}
