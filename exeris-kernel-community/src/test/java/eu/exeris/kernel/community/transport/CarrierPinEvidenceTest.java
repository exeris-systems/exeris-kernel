/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract of the classifier that decides which carrier pins
 * {@link CommunityClientIngressCarrierPinningTest} counts.
 *
 * <p>A classifier that sets evidence aside earns a test in both directions: the class-loading cases
 * it must recognise, and — the ones that matter — the blocking-carrier pins it must never swallow.
 * Every reason string below is one the JVM actually emitted in a
 * {@code jdk.VirtualThreadPinned} event on this repository's transport suite.
 */
@DisplayName("CarrierPinEvidence: class-loading pins are set aside, blocked carriers are not")
class CarrierPinEvidenceTest {

    private static final List<String> RECV_FRAMES = List.of(
            "eu.exeris.kernel.community.transport.NativeTcpStream.read",
            "eu.exeris.kernel.community.transport.NativeTcpStream.awaitReadableIngress",
            "java.util.concurrent.locks.LockSupport.park");

    @Nested
    @DisplayName("Class loading and class initialisation")
    class ClassWork {

        @Test
        @DisplayName("waiting on another thread's class initialisation is set aside")
        void initializationWaitIsSetAside() {
            assertThat(CarrierPinEvidence.isClassLoadingOrInit(
                    "Waited for initialization of eu.exeris.kernel.core.transport.jfr.StreamLifecycleEvent"
                            + " by another thread",
                    RECV_FRAMES))
                    .isTrue();
        }

        @Test
        @DisplayName("running a <clinit> is set aside, whatever the stack says")
        void clinitOnStackIsSetAside() {
            assertThat(CarrierPinEvidence.isClassLoadingOrInit(
                    "VM call to eu.exeris.kernel.core.transport.jfr.StreamLifecycleEvent.<clinit> on stack",
                    List.of()))
                    .isTrue();
        }

        @Test
        @DisplayName("a generic reason with a class-loader frame is set aside")
        void classLoaderFrameIsSetAside() {
            assertThat(CarrierPinEvidence.isClassLoadingOrInit(
                    "Freeze or preempt failed (2)",
                    List.of("jdk.internal.loader.BuiltinClassLoader.loadClassOrNull",
                            "java.lang.ClassLoader.loadClass",
                            "eu.exeris.kernel.community.transport.NativeTcpStream.newPendingWrite")))
                    .isTrue();
        }

        @Test
        @DisplayName("a static initialiser anywhere in the stack is set aside")
        void clinitFrameIsSetAside() {
            assertThat(CarrierPinEvidence.isClassLoadingOrInit(
                    "Freeze or preempt failed (2)",
                    List.of("eu.exeris.kernel.community.transport.NativeTcpStream.<clinit>")))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Pins that must still fail the fence")
    class BlockedCarrier {

        @Test
        @DisplayName("a native frame on the stack is counted — this is the TCK-064 defect itself")
        void nativeFrameIsCounted() {
            assertThat(CarrierPinEvidence.isClassLoadingOrInit(
                    "Native frame on stack",
                    List.of("eu.exeris.kernel.core.transport.syscall.CoreSyscalls.recv",
                            "eu.exeris.kernel.community.transport.NativeTcpStream.read")))
                    .isFalse();
        }

        @Test
        @DisplayName("a generic reason with no class-loading frame is counted")
        void genericReasonWithoutClassWorkIsCounted() {
            assertThat(CarrierPinEvidence.isClassLoadingOrInit("Freeze or preempt failed (2)", RECV_FRAMES))
                    .isFalse();
        }

        @Test
        @DisplayName("a VM call that is not a <clinit> is counted")
        void nonClinitVmCallIsCounted() {
            assertThat(CarrierPinEvidence.isClassLoadingOrInit(
                    "VM call to java.lang.Object.wait on stack", RECV_FRAMES))
                    .isFalse();
        }

        @Test
        @DisplayName("an unknown reason with no frames is counted, not assumed benign")
        void unknownReasonIsCounted() {
            assertThat(CarrierPinEvidence.isClassLoadingOrInit("<unknown>", List.of())).isFalse();
        }

        @Test
        @DisplayName("a class name containing a loader package does not make a pin class work")
        void applicationFrameNamedLikeALoaderIsCounted() {
            assertThat(CarrierPinEvidence.isClassLoadingOrInit(
                    "Freeze or preempt failed (2)",
                    List.of("eu.exeris.kernel.community.transport.jdk.internal.loader.Decoy.read")))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Reporting")
    class Reporting {

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
}
