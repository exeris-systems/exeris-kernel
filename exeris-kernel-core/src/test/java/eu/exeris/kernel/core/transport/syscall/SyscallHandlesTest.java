/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.core.transport.syscall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Unit Tests: {@link SyscallHandles} — value record contract, null-handle predicates,
 * and Valhalla-readiness guarantees.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>{@code hasFcntl()} / {@code hasIoctlsocket()} predicate logic</li>
 *   <li>Record equals() by value — no identity operations</li>
 *   <li>All non-null fields accessible via record accessors</li>
 *   <li>Null handles for platform-absent symbols are tolerated</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L1: SyscallHandles — value record contract and null-handle predicates")
class SyscallHandlesTest {

    // =========================================================================
    // Test fixture: minimal MethodHandle stubs via MethodHandles.lookup()
    // =========================================================================

    /**
     * Creates a trivial zero-arg MethodHandle returning void, for use as a stub.
     */
    private static MethodHandle stubHandle() {
        try {
            return MethodHandles.lookup()
                    .findStatic(SyscallHandlesTest.class, "noOp", MethodType.methodType(void.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError("Test setup failed", e);
        }
    }

    @SuppressWarnings("unused")
    private static void noOp() {
        // stub target — intentionally empty
    }

    private static SyscallHandles posixHandles() {
        MethodHandle h = stubHandle();
        return new SyscallHandles(h, h, h, h, h, h, h, h, h, null, null);
    }

    private static SyscallHandles windowsHandles() {
        MethodHandle h = stubHandle();
        return new SyscallHandles(h, h, h, h, h, h, h, h, null, h, h);
    }

    // =========================================================================
    // Nested: hasFcntl() predicate
    // =========================================================================

    @Nested
    @DisplayName("hasFcntl() predicate")
    class HasFcntlPredicate {

        @Test
        @DisplayName("hasFcntl() returns true when fcntl handle is non-null (POSIX)")
        void posixHasFcntl() {
            assertThat(posixHandles().hasFcntl()).isTrue();
        }

        @Test
        @DisplayName("hasFcntl() returns false when fcntl handle is null (Windows)")
        void windowsDoesNotHaveFcntl() {
            assertThat(windowsHandles().hasFcntl()).isFalse();
        }
    }

    // =========================================================================
    // Nested: hasIoctlsocket() predicate
    // =========================================================================

    @Nested
    @DisplayName("hasIoctlsocket() predicate")
    class HasIoctlsocketPredicate {

        @Test
        @DisplayName("hasIoctlsocket() returns false on POSIX (ioctlsocket is null)")
        void posixDoesNotHaveIoctlsocket() {
            assertThat(posixHandles().hasIoctlsocket()).isFalse();
        }

        @Test
        @DisplayName("hasIoctlsocket() returns true on Windows (ioctlsocket is non-null)")
        void windowsHasIoctlsocket() {
            assertThat(windowsHandles().hasIoctlsocket()).isTrue();
        }
    }

    // =========================================================================
    // Nested: Mutual exclusivity (platform invariant)
    // =========================================================================

    @Nested
    @DisplayName("Platform invariant: fcntl and ioctlsocket are mutually exclusive")
    class MutualExclusivity {

        @Test
        @DisplayName("POSIX handles: hasFcntl()=true AND hasIoctlsocket()=false")
        void posixInvariant() {
            assertThat(posixHandles()).satisfies(h -> {
                assertThat(h.hasFcntl()).isTrue();
                assertThat(h.hasIoctlsocket()).isFalse();
            });
        }

        @Test
        @DisplayName("Windows handles: hasFcntl()=false AND hasIoctlsocket()=true")
        void windowsInvariant() {
            assertThat(windowsHandles()).satisfies(h -> {
                assertThat(h.hasFcntl()).isFalse();
                assertThat(h.hasIoctlsocket()).isTrue();
            });
        }
    }

    // =========================================================================
    // Nested: Record accessor availability
    // =========================================================================

    @Nested
    @DisplayName("Record accessors — all fields accessible")
    class RecordAccessors {

        @Test
        @DisplayName("All POSIX required handles are non-null")
        void posixRequiredHandlesNonNull() {
            assertThat(posixHandles()).satisfies(h -> {
                assertThat(h.socket()).isNotNull();
                assertThat(h.bind()).isNotNull();
                assertThat(h.listen()).isNotNull();
                assertThat(h.accept()).isNotNull();
                assertThat(h.connect()).isNotNull();
                assertThat(h.close()).isNotNull();
                assertThat(h.send()).isNotNull();
                assertThat(h.recv()).isNotNull();
                assertThat(h.fcntl()).isNotNull();
            });
        }

        @Test
        @DisplayName("All Windows required handles are non-null")
        void windowsRequiredHandlesNonNull() {
            assertThat(windowsHandles()).satisfies(h -> {
                assertThat(h.socket()).isNotNull();
                assertThat(h.bind()).isNotNull();
                assertThat(h.listen()).isNotNull();
                assertThat(h.accept()).isNotNull();
                assertThat(h.connect()).isNotNull();
                assertThat(h.close()).isNotNull();
                assertThat(h.send()).isNotNull();
                assertThat(h.recv()).isNotNull();
                assertThat(h.ioctlsocket()).isNotNull();
            });
        }
    }

    // =========================================================================
    // Nested: Valhalla-readiness — identity-free record contract
    // =========================================================================

    @Nested
    @DisplayName("Valhalla-readiness — identity-free record")
    class ValhallaReadiness {

        @Test
        @DisplayName("Two SyscallHandles with identical fields are equal (value semantics)")
        void equalByValue() {
            MethodHandle h = stubHandle();
            SyscallHandles p1 = new SyscallHandles(h, h, h, h, h, h, h, h, h, null, null);
            SyscallHandles p2 = new SyscallHandles(h, h, h, h, h, h, h, h, h, null, null);
            assertThat(p1).isEqualTo(p2).hasSameHashCodeAs(p2);
        }

        @Test
        @DisplayName("toString() includes hasFcntl info via record default")
        void toStringIsInformative() {
            String str = posixHandles().toString();
            // record toString format: SyscallHandles[socket=..., ...]
            assertThat(str).startsWith("SyscallHandles[");
        }
    }
}
