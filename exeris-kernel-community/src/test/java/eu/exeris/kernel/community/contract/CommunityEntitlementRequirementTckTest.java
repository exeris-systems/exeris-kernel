/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.contract;

import eu.exeris.kernel.spi.contract.EntitlementRequirement;
import eu.exeris.kernel.tck.contract.AbstractEntitlementRequirementTck;
import org.junit.jupiter.api.DisplayName;

import java.util.Set;

/**
 * Community ships no entitlement-requiring code, so it registers no {@link EntitlementRequirement}; this
 * binding runs the suite against a reference provider, the shape an Enterprise or commercial artifact copies.
 */
@DisplayName("Community: EntitlementRequirement TCK (reference provider)")
class CommunityEntitlementRequirementTckTest extends AbstractEntitlementRequirementTck {

    @Override
    protected Class<? extends EntitlementRequirement> providerClass() {
        return ReferenceRequirement.class;
    }

    /** Reference provider: a fixed, immutable set of capability identifiers. */
    public static final class ReferenceRequirement implements EntitlementRequirement {

        private static final Set<String> CAPABILITIES = Set.of("gateway.core", "security.bot-fingerprinting");

        /** ServiceLoader constructor. */
        public ReferenceRequirement() {
            // stateless
        }

        @Override
        public Set<String> requiredCapabilities() {
            return CAPABILITIES;
        }
    }
}
