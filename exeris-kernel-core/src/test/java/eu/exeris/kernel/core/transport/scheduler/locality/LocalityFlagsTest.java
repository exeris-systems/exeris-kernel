/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import eu.exeris.kernel.core.transport.scheduler.locality.LocalityFlags.CarrierResolve;
import eu.exeris.kernel.core.transport.scheduler.locality.LocalityFlags.PollerRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LocalityFlags")
class LocalityFlagsTest {

    @Test
    @DisplayName("every default reproduces the baseline scheduler")
    void defaultsAreBaseline() {
        LocalityFlags flags = LocalityFlags.defaults();
        assertEquals(CarrierResolve.SCOPE, flags.carrierResolve());
        assertEquals(PollerRoute.NAME, flags.pollerRoute());
        assertFalse(flags.factoryPreferredCarrier());
        assertFalse(flags.timerCount());
        assertFalse(flags.allVthreads());
        assertFalse(flags.perCarrierPollers());
        assertEquals(50_000L, flags.yieldBudgetNs());
    }

    @Test
    @DisplayName("values are parsed case-insensitively")
    void parsesValues() {
        LocalityFlags flags = LocalityFlags.of(Map.of(
                "exeris.locality.carrierResolve", "Mounted",
                "exeris.locality.poller.route", "preferred",
                "exeris.locality.factory.preferredCarrier", "true",
                "exeris.locality.timer.count", "TRUE",
                "jdk.pollerMode", "3"));
        assertEquals(CarrierResolve.MOUNTED, flags.carrierResolve());
        assertEquals(PollerRoute.PREFERRED, flags.pollerRoute());
        assertTrue(flags.factoryPreferredCarrier());
        assertTrue(flags.timerCount());
        assertTrue(flags.perCarrierPollers());
    }

    @Test
    @DisplayName("an unparsable value is rejected with the property name")
    void rejectsInvalidValues() {
        IllegalArgumentException choice = assertThrows(IllegalArgumentException.class,
                () -> LocalityFlags.of(Map.of("exeris.locality.carrierResolve", "bogus")));
        assertTrue(choice.getMessage().contains("exeris.locality.carrierResolve"));
        assertTrue(choice.getMessage().contains("scope|mounted"));
        IllegalArgumentException bool = assertThrows(IllegalArgumentException.class,
                () -> LocalityFlags.of(Map.of("exeris.locality.timer.count", "yes")));
        assertTrue(bool.getMessage().contains("exeris.locality.timer.count"));
    }
}
