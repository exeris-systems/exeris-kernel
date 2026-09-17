/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract of the classifier every carrier-pinning fence in this repository consults.
 *
 * <p>A classifier that sets evidence aside earns a test in both directions: the class-loading cases
 * it must recognise, and — the ones that matter — the blocked-carrier pins it must never swallow.
 * Every reason string below is one the JVM actually emitted in a {@code jdk.VirtualThreadPinned}
 * event on this repository's transport suite.
 */
@DisplayName("CarrierPinClassification: cold classes are set aside, blocked carriers are not")
class CarrierPinClassificationTest {

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
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "Waited for initialization of eu.exeris.kernel.core.transport.jfr.StreamLifecycleEvent"
                            + " by another thread",
                    RECV_FRAMES))
                    .isTrue();
        }

        @Test
        @DisplayName("running a <clinit> is set aside, whatever the stack says")
        void clinitOnStackIsSetAside() {
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "VM call to eu.exeris.kernel.core.transport.jfr.StreamLifecycleEvent.<clinit> on stack",
                    List.of()))
                    .isTrue();
        }

        @Test
        @DisplayName("a generic reason with a class-loader frame is set aside")
        void classLoaderFrameIsSetAside() {
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "Freeze or preempt failed (2)",
                    List.of("jdk.internal.loader.BuiltinClassLoader.loadClassOrNull",
                            "java.lang.ClassLoader.loadClass",
                            "eu.exeris.kernel.community.transport.NativeTcpStream.newPendingWrite")))
                    .isTrue();
        }

        @Test
        @DisplayName("a static initialiser anywhere in the stack is set aside")
        void clinitFrameIsSetAside() {
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "Freeze or preempt failed (2)",
                    List.of("eu.exeris.kernel.community.transport.NativeTcpStream.<clinit>")))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Pins that must still fail a fence")
    class BlockedCarrier {

        @Test
        @DisplayName("a native frame on the stack is counted — this is the TCK-064 defect itself")
        void nativeFrameIsCounted() {
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "Native frame on stack",
                    List.of("eu.exeris.kernel.core.transport.syscall.CoreSyscalls.recv",
                            "eu.exeris.kernel.community.transport.NativeTcpStream.read")))
                    .isFalse();
        }

        @Test
        @DisplayName("a generic reason with no class-loading frame is counted")
        void genericReasonWithoutClassWorkIsCounted() {
            assertThat(CarrierPinClassification.isClassLoadingOrInit("Freeze or preempt failed (2)", RECV_FRAMES))
                    .isFalse();
        }

        @Test
        @DisplayName("a VM call that is not a <clinit> is counted")
        void nonClinitVmCallIsCounted() {
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "VM call to java.lang.Object.wait on stack", RECV_FRAMES))
                    .isFalse();
        }

        @Test
        @DisplayName("an unknown reason with no frames is counted, not assumed benign")
        void unknownReasonIsCounted() {
            assertThat(CarrierPinClassification.isClassLoadingOrInit("<unknown>", List.of())).isFalse();
        }

        @Test
        @DisplayName("a JDK without the pinnedReason field does not silence the fence")
        void missingReasonFieldIsCounted() {
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "<no pinnedReason field on this JDK>", RECV_FRAMES))
                    .isFalse();
        }

        @Test
        @DisplayName("a native-library load is counted — it shares a package with the class loaders")
        void nativeLibraryLoadIsCounted() {
            // jdk.internal.loader.NativeLibraries is where a carrier blocks while a native library
            // is loaded, and this kernel loads OpenSSL. A package-prefix match filed it as class
            // loading; an enumerated set of loader types does not.
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "Freeze or preempt failed (2)",
                    List.of("jdk.internal.loader.NativeLibraries.load",
                            "jdk.internal.loader.NativeLibraries$NativeLibraryImpl.open",
                            "eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader.load")))
                    .isFalse();
        }

        @Test
        @DisplayName("a class name containing a loader package does not make a pin class work")
        void applicationFrameNamedLikeALoaderIsCounted() {
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "Freeze or preempt failed (2)",
                    List.of("eu.exeris.kernel.community.transport.jdk.internal.loader.Decoy.read")))
                    .isFalse();
        }
    }
}
