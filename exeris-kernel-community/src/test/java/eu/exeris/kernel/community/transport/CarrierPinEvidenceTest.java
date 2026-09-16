/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How {@link CommunityClientIngressCarrierPinningTest} reports what it found.
 *
 * <p>The verdict itself — cold class or blocked carrier — belongs to
 * {@link eu.exeris.kernel.tck.contract.CarrierPinClassification} and is pinned in both directions by
 * its own test in the TCK module. What is asserted here is that a failure message carries the
 * evidence a reader needs, and that pins set aside are named rather than dropped.
 */
@DisplayName("CarrierPinEvidence: a failure report carries the reason, the stack, and what it set aside")
class CarrierPinEvidenceTest {

    @Test
    @DisplayName("a rendered pin carries the reason and the frames, not just the thread")
    void describeCarriesReasonAndFrames() {
        CarrierPinEvidence.Pin pin = new CarrierPinEvidence.Pin(
                "paqs/CommunityNativeTcpCarrier/NORMAL/25",
                21.25,
                "Waited for initialization of X by another thread",
                List.of("eu.exeris.kernel.core.transport.scheduler.PaqsScheduler.runStream"),
                true);

        assertThat(pin.describe())
                .contains("paqs/CommunityNativeTcpCarrier/NORMAL/25")
                // Locale.ROOT, so the decimal point does not follow the runner's locale.
                .contains("21.25 ms")
                .contains("Waited for initialization of X by another thread")
                .contains("PaqsScheduler.runStream");
    }

    @Test
    @DisplayName("an empty set-aside list says so rather than printing nothing")
    void setAsideReportsAbsence() {
        assertThat(CarrierPinEvidence.renderSetAside(List.of()))
                .isEqualTo("No class-loading or class-initialisation pins in this run.");
    }

    @Test
    @DisplayName("set-aside pins are listed in the report, not hidden")
    void setAsideListsWhatItSetAside() {
        CarrierPinEvidence.Pin pin = new CarrierPinEvidence.Pin(
                "tck064-client-recv-5", 24.0, "Freeze or preempt failed (2)",
                List.of("jdk.internal.loader.BuiltinClassLoader.loadClass"), true);

        assertThat(CarrierPinEvidence.renderSetAside(List.of(pin)))
                .contains("1 class-loading/initialisation pin(s)")
                .contains("tck064-client-recv-5")
                .contains("BuiltinClassLoader.loadClass");
    }
}
