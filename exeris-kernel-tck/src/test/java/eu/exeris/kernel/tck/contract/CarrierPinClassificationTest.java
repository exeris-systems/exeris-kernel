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
 *
 * <p><strong>Where the reason strings come from.</strong> They are the JVM's own format strings,
 * read out of {@code libjvm.so} beside {@code JavaThread::post_vthread_pinned_event}:
 * {@code "Waited for initialization of %s by another thread"},
 * {@code "VM call to %s.<clinit> on stack"}, {@code "Freeze or preempt failed (%d)"} and
 * {@code "Native or VM frame on stack"}. This file previously claimed each was one the JVM had
 * emitted on this repository's transport suite, which it could not support — no captured
 * {@code jdk.VirtualThreadPinned} event exists under version control — and under that claim four
 * cases used {@code "Native frame on stack"}, a spelling the JVM does not produce. A predicate
 * written against an invented string is tested against nothing.
 *
 * <p>The two "the JVM said nothing useful" cases read their reason from the classifier's own
 * constants rather than repeating the characters. A literal there is a test that keeps passing
 * against a string the class no longer produces — which is the failure mode these two cases exist
 * to rule out.
 *
 * <p>The frame shapes are hand-built, and that is a real limit: a recording puts the blocking
 * primitive innermost and a {@code <clinit>} well below it, so the frame heuristic is a fallback for
 * a JDK that stops supplying {@code pinnedReason} rather than the path a real pin takes. What the
 * cases here can pin is the boundary of the window it reads, from both sides.
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
        @DisplayName("running a <clinit> is set aside on the JVM's own account of the pin")
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
        @DisplayName("an FFM frame below the window does not outrank a class-initialisation pin")
        void anFfmFrameBelowTheWindowDoesNotOutrankTheClinitReason() {
            // The regression this pins. jdk.internal.foreign. was scanned over the whole stack for
            // one review round, and on this kernel almost anything touching a MemorySegment carries
            // such a frame somewhere below — so a real class-initialisation pin on an allocation or
            // transport path was counted as a blocked carrier. A false failure is as damaging as a
            // false silence, and the earlier reasoning had weighed only one of them.
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "VM call to eu.exeris.kernel.core.transport.jfr.StreamLifecycleEvent.<clinit> on stack",
                    List.of("eu.exeris.kernel.core.transport.jfr.StreamLifecycleEvent.emit",
                            "eu.exeris.kernel.core.transport.scheduler.PaqsScheduler.runStream",
                            "eu.exeris.kernel.core.memory.CommunityArenaBuffers.slice",
                            "eu.exeris.kernel.spi.memory.LoanedBuffer.segment",
                            "jdk.internal.foreign.AbstractMemorySegmentImpl.asSlice",
                            "jdk.internal.foreign.MemorySessionImpl.checkValidState")))
                    .isTrue();
        }

        @Test
        @DisplayName("a static initialiser at the blocking site is set aside")
        void clinitFrameIsSetAside() {
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "Freeze or preempt failed (2)",
                    List.of("eu.exeris.kernel.community.transport.NativeTcpStream.<clinit>")))
                    .isTrue();
        }

        @Test
        @DisplayName("a loader frame at the last index the window reaches is still set aside")
        void aLoaderFrameAtTheEdgeOfTheWindowIsSetAside() {
            // Pins CLASS_WORK_FRAME_DEPTH from below, which nothing did: every other positive frame
            // case here puts its marker at index 0, so the constant could have been 1 and the whole
            // suite stayed green. Its counterpart, aLoaderFrameBelowTheBlockingSiteIsCounted, sits
            // one index further out and pins the same constant from above.
            List<String> frames = List.of(
                    "eu.exeris.kernel.community.transport.NativeTcpStream.read",
                    "eu.exeris.kernel.community.transport.NativeTcpStream.newPendingWrite",
                    "eu.exeris.kernel.core.transport.scheduler.PaqsScheduler.runStream",
                    "java.lang.ClassLoader.loadClass");

            assertThat(frames)
                    .withFailMessage("this case only pins the window while the marker sits at its last index; "
                            + "CLASS_WORK_FRAME_DEPTH changed and this stack no longer ends on it")
                    .hasSize(CarrierPinClassification.CLASS_WORK_FRAME_DEPTH);

            assertThat(CarrierPinClassification.isClassLoadingOrInit("Freeze or preempt failed (2)", frames))
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
                    "Native or VM frame on stack",
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
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    CarrierPinClassification.UNKNOWN_REASON, List.of()))
                    .isFalse();
        }

        @Test
        @DisplayName("a JDK without the pinnedReason field does not silence the fence")
        void missingReasonFieldIsCounted() {
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    CarrierPinClassification.NO_REASON_FIELD, RECV_FRAMES))
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
        @DisplayName("a loader frame deep under a blocked carrier does not set the pin aside")
        void aLoaderFrameBelowTheBlockingSiteIsCounted() {
            // The blocking site is at the top. A stack 256 deep nearly always has a loadClass
            // somewhere in it, and scanning all of it let that frame outrank the block above it.
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "Native or VM frame on stack",
                    List.of("eu.exeris.kernel.core.transport.syscall.CoreSyscalls.recv",
                            "eu.exeris.kernel.community.transport.NativeTcpStream.read",
                            "eu.exeris.kernel.core.transport.scheduler.PaqsScheduler.runStream",
                            "java.lang.VirtualThread.run",
                            "jdk.internal.loader.BuiltinClassLoader.loadClass",
                            "java.lang.ClassLoader.loadClass")))
                    .isFalse();
        }

        @Test
        @DisplayName("a block inside a static initialiser is counted — a <clinit> is not a licence")
        void aBlockingFrameInsideAClinitIsCounted() {
            // The hole this closes. Loading a native library is ordinary work for a static
            // initialiser, so the <clinit> frame and the NativeLibraries frame appear together —
            // and the <clinit> predicate alone set the whole pin aside. On a kernel that loads
            // OpenSSL through FFM that is not a narrower fence, it is no fence.
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "Native or VM frame on stack",
                    List.of("jdk.internal.loader.NativeLibraries.load",
                            "eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader.<clinit>",
                            "eu.exeris.kernel.core.crypto.openssl.CoreOpenSslBindings.<clinit>")))
                    .isFalse();
        }

        @Test
        @DisplayName("a reason that merely mentions <clinit> is counted, not read as the JVM's verdict")
        void aReasonMentioningClinitOutsideTheJvmPhrasingIsCounted() {
            // "VM call to <class>.<clinit> on stack" is what the JVM emits. A substring match on
            // <clinit> alone let any other reason carrying those characters silence the fence.
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "Native or VM frame on stack while <clinit> was pending elsewhere", RECV_FRAMES))
                    .isFalse();
        }

        @Test
        @DisplayName("a blocking frame below the window is counted even when the reason says <clinit>")
        void aBlockingFrameBelowTheWindowOutranksTheClinitReason() {
            // A native-library load is never calling context, so ALWAYS_BLOCKING is scanned over
            // the whole stack: a carrier genuinely blocked loading OpenSSL inside a static
            // initialiser must be counted however deep the load sits. Note the FFM frame at index 4
            // does NOT decide this case — BLOCKING_NEAR_TOP stops at CLASS_WORK_FRAME_DEPTH — which
            // is what makes this a test of the always-scanned half rather than of both at once.
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "VM call to eu.exeris.kernel.core.crypto.openssl.CoreOpenSslBindings.<clinit> on stack",
                    List.of("eu.exeris.kernel.core.crypto.openssl.CoreOpenSslBindings.linkSymbol",
                            "eu.exeris.kernel.core.crypto.openssl.CoreOpenSslBindings.lookup",
                            "java.lang.foreign.SymbolLookup.libraryLookup",
                            "java.lang.foreign.Linker.downcallHandle",
                            "jdk.internal.foreign.abi.DowncallLinker.getBoundMethodHandle",
                            "jdk.internal.loader.NativeLibraries.load")))
                    .isFalse();
        }

        @Test
        @DisplayName("an FFM frame at the blocking site is counted, even under a <clinit> reason")
        void anFfmFrameAtTheBlockingSiteIsCounted() {
            // The near-top half of the veto, from the side that must still fail a fence: a downcall
            // blocks where the downcall happens, so an FFM frame innermost means a blocked carrier
            // whatever the reason says about class initialisation.
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "VM call to eu.exeris.kernel.core.crypto.openssl.CoreOpenSslBindings.<clinit> on stack",
                    List.of("jdk.internal.foreign.abi.DowncallStub.invoke",
                            "eu.exeris.kernel.core.crypto.openssl.CoreOpenSslBindings.linkSymbol")))
                    .isFalse();
        }

        @Test
        @DisplayName("a type merely prefixed by a loader type name does not make a pin class work")
        void aReasonMentioningAnInitWaitOutsideTheJvmPhrasingIsCounted() {
            // The same hole as aReasonMentioningClinitOutsideTheJvmPhrasingIsCounted, two lines away
            // in the classifier and left open when that one was closed: the init-wait predicate was
            // a bare contains() while its neighbour had been tightened to the JVM's own phrasing.
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "Native or VM frame on stack while Waited for initialization of "
                            + "eu.exeris.kernel.core.transport.jfr.StreamLifecycleEvent was pending",
                    RECV_FRAMES))
                    .isFalse();
        }

        @Test
        @DisplayName("a type merely prefixed by a loader type name does not make a pin class work")
        void aTypePrefixedLikeALoaderIsCounted() {
            // ClassLoaders and URLClassPath were matched without a trailing dot, so a type whose
            // name only starts with theirs passed as class loading.
            assertThat(CarrierPinClassification.isClassLoadingOrInit(
                    "Freeze or preempt failed (2)",
                    List.of("jdk.internal.loader.ClassLoadersDecoy.read",
                            "jdk.internal.loader.URLClassPathDecoy.read")))
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
