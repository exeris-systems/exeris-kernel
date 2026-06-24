/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.bootstrap.SubsystemProvider;
import eu.exeris.kernel.tck.contract.bootstrap.AbstractSubsystemProviderTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Community: CommunitySubsystemProvider TCK")
class CommunitySubsystemProviderTckTest extends AbstractSubsystemProviderTck {

    @Override
    protected SubsystemProvider createProvider() {
        return new CommunitySubsystemProvider();
    }

    @Test
    @DisplayName("priority() == 0 (Community Open-Core slot, inherited from SPI default)")
    void priorityIsCommunitySlot() {
        // Pins the Community tier slot explicitly — AbstractSubsystemProviderTck only enforces
        // priority() >= 0, so an accidental future override to the Enterprise slot (100) would
        // otherwise pass. Mirrors the config provider side's isEqualTo(0) discipline.
        assertThat(new CommunitySubsystemProvider().priority()).isEqualTo(0);
    }
}