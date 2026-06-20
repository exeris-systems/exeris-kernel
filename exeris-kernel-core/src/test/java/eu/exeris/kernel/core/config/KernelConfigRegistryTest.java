/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link KernelConfigRegistry}.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>Registration: callbacks are stored and retrievable.</li>
 *   <li>Matching: file=null matches any file; file-specific match is exact.</li>
 *   <li>Seal: late registrations after seal() are silently ignored.</li>
 *   <li>fireReload: matching callbacks are invoked with the correct value.</li>
 *   <li>fireReload: non-matching registrations are NOT invoked.</li>
 *   <li>fireReload: callback exception does NOT abort the loop (EX-CFG-1003 resilience).</li>
 *   <li>Concurrency: concurrent fireReload calls from Virtual Threads see no data races.</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("KernelConfigRegistry — unit tests")
class KernelConfigRegistryTest {

    private static final KernelConfigRegistry.Registration EXTRA_ENTRY =
            new KernelConfigRegistry.Registration("f", "k2", v -> {});

    // =========================================================================
    // Registration
    // =========================================================================

    @Nested
    @DisplayName("Registration contract")
    class RegistrationContract {

        @Test
        @DisplayName("Registered callback appears in registrations() view")
        void registrationIsStored() {
            var reg = new KernelConfigRegistry();
            reg.register("app.properties", "network.port", v -> {});

            assertThat(reg.registrations()).hasSize(1);
            assertThat(reg.registrations().getFirst().key()).isEqualTo("network.port");
        }

        @Test
        @DisplayName("Multiple registrations for the same file accumulate independently")
        void multipleRegistrationsAccumulate() {
            var reg = new KernelConfigRegistry();
            reg.register("app.properties", "network.port",    v -> {});
            reg.register("app.properties", "network.timeout", v -> {});
            reg.register("feature.properties", "flags.alpha", v -> {});

            assertThat(reg.registrations()).hasSize(3);
        }

        @Test
        @DisplayName("registrations() returns an unmodifiable view")
        void registrationsViewIsUnmodifiable() {
            var reg = new KernelConfigRegistry();
            reg.register("f.properties", "k", v -> {});

            var view = reg.registrations();
            assertThat(view).hasSize(1);

            assertThatThrownBy(() -> view.add(EXTRA_ENTRY))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // Seal
    // =========================================================================

    @Nested
    @DisplayName("Seal contract")
    class SealContract {

        @Test
        @DisplayName("isSealed() returns false before seal() and true after")
        void sealedFlagTransitions() {
            var reg = new KernelConfigRegistry();
            assertThat(reg.isSealed()).isFalse();
            reg.seal();
            assertThat(reg.isSealed()).isTrue();
        }

        @Test
        @DisplayName("Registration after seal() is silently ignored")
        void registrationAfterSealIsIgnored() {
            var reg = new KernelConfigRegistry();
            reg.register("f.properties", "key1", v -> {});
            reg.seal();
            reg.register("f.properties", "key2", v -> {}); // must be silently ignored

            assertThat(reg.registrations())
                    .as("Late registration must be ignored after seal()")
                    .hasSize(1);
        }
    }

    // =========================================================================
    // @Immutable sealed-key guards
    // =========================================================================

    @Nested
    @DisplayName("@Immutable guard contract")
    class ImmutableGuardContract {

        @Test
        @DisplayName("registerImmutable() makes isImmutable() report the (file, key) sealed")
        void registerImmutableIsReported() {
            var reg = new KernelConfigRegistry();
            reg.registerImmutable("security.properties", "security.jwks.uri");

            assertThat(reg.isImmutable("security.properties", "security.jwks.uri")).isTrue();
            assertThat(reg.isImmutable("security.properties", "other.key")).isFalse();
            assertThat(reg.immutableRegistrations()).hasSize(1);
        }

        @Test
        @DisplayName("A null-file guard seals the key in any file")
        void nullFileGuardMatchesAnyFile() {
            var reg = new KernelConfigRegistry();
            reg.registerImmutable(null, "security.issuer");

            assertThat(reg.isImmutable("a.properties", "security.issuer")).isTrue();
            assertThat(reg.isImmutable("b.properties", "security.issuer")).isTrue();
            assertThat(reg.isImmutable("a.properties", "unrelated")).isFalse();
        }

        @Test
        @DisplayName("registerImmutable() after seal() is silently ignored")
        void registerImmutableAfterSealIsIgnored() {
            var reg = new KernelConfigRegistry();
            reg.registerImmutable("security.properties", "k1");
            reg.seal();
            reg.registerImmutable("security.properties", "k2"); // must be silently ignored

            assertThat(reg.immutableRegistrations())
                    .as("Late immutable registration must be ignored after seal()")
                    .hasSize(1);
            assertThat(reg.isImmutable("security.properties", "k2")).isFalse();
        }

        @Test
        @DisplayName("immutableRegistrations() returns an unmodifiable snapshot")
        void immutableRegistrationsViewIsUnmodifiable() {
            var reg = new KernelConfigRegistry();
            reg.registerImmutable("security.properties", "k1");

            assertThatThrownBy(() -> reg.immutableRegistrations().add(
                    new KernelConfigRegistry.ImmutableRegistration("x", "y")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // Matching
    // =========================================================================

    @Nested
    @DisplayName("Registration.matches() — file and key matching")
    class MatchingContract {

        @Test
        @DisplayName("file=null matches any file name")
        void nullFileMatchesAnyFile() {
            var r = new KernelConfigRegistry.Registration(null, "network.port", v -> {});
            assertThat(r.matches("app.properties", "network.port")).isTrue();
            assertThat(r.matches("other.properties", "network.port")).isTrue();
        }

        @Test
        @DisplayName("file-specific registration only matches that exact file")
        void specificFileMatchIsExact() {
            var r = new KernelConfigRegistry.Registration("app.properties", "network.port", v -> {});
            assertThat(r.matches("app.properties",   "network.port")).isTrue();
            assertThat(r.matches("other.properties", "network.port")).isFalse();
        }

        @Test
        @DisplayName("Key mismatch prevents match even if file matches")
        void keyMismatchPreventsMatch() {
            var r = new KernelConfigRegistry.Registration("app.properties", "network.port", v -> {});
            assertThat(r.matches("app.properties", "network.timeout")).isFalse();
        }
    }

    // =========================================================================
    // fireReload dispatch
    // =========================================================================

    @Nested
    @DisplayName("fireReload() dispatch")
    class FireReloadDispatch {

        @Test
        @DisplayName("Matching callback receives the new value")
        void matchingCallbackReceivesValue() {
            var reg     = new KernelConfigRegistry();
            var received = new AtomicReference<String>();
            reg.register("app.properties", "network.port", received::set);

            reg.fireReload("app.properties", "network.port", "9090");

            assertThat(received.get()).isEqualTo("9090");
        }

        @Test
        @DisplayName("Non-matching key does NOT invoke callback")
        void nonMatchingKeyIsSkipped() {
            var reg   = new KernelConfigRegistry();
            var called = new ArrayList<String>();
            reg.register("app.properties", "network.port", called::add);

            reg.fireReload("app.properties", "network.timeout", "30000");

            assertThat(called).isEmpty();
        }

        @Test
        @DisplayName("Non-matching file does NOT invoke callback")
        void nonMatchingFileIsSkipped() {
            var reg   = new KernelConfigRegistry();
            var called = new ArrayList<String>();
            reg.register("app.properties", "network.port", called::add);

            reg.fireReload("other.properties", "network.port", "9090");

            assertThat(called).isEmpty();
        }

        @Test
        @DisplayName("Multiple matching callbacks are all invoked")
        void multipleMatchingCallbacksAreAllInvoked() {
            var reg    = new KernelConfigRegistry();
            var values = new ArrayList<String>();
            reg.register("app.properties", "network.port", v -> values.add("A:" + v));
            reg.register("app.properties", "network.port", v -> values.add("B:" + v));

            reg.fireReload("app.properties", "network.port", "8080");

            assertThat(values).containsExactly("A:8080", "B:8080");
        }

        @Test
        @DisplayName("Callback exception does NOT abort the loop — subsequent callbacks still run")
        void callbackExceptionDoesNotAbortLoop() {
            var reg    = new KernelConfigRegistry();
            var called = new ArrayList<String>();

            reg.register("app.properties", "network.port", v -> {
                throw new RuntimeException("simulated callback failure");
            });
            reg.register("app.properties", "network.port", called::add);

            // Must not throw; second callback must still be invoked
            reg.fireReload("app.properties", "network.port", "9090");

            assertThat(called).containsExactly("9090");
        }
    }

    // =========================================================================
    // Concurrency
    // =========================================================================

    @Nested
    @DisplayName("Concurrency — Virtual Thread fire-and-read races")
    class ConcurrencyContract {

        @Test
        @DisplayName("Concurrent fireReload from 64 VTs — all updates are visible, no exception")
        @SuppressWarnings("preview")
        void concurrentFireReloadFromVirtualThreads() throws Exception {
            var reg    = new KernelConfigRegistry();
            var values = new CopyOnWriteArrayList<String>();

            reg.register("app.properties", "counter", values::add);
            reg.seal();

            int threads = 64;
            try (var scope = StructuredTaskScope.open()) {
                for (int i = 0; i < threads; i++) {
                    final String val = String.valueOf(i);
                    scope.fork(() -> fireAndReturn(reg, "app.properties", "counter", val));
                }
                scope.join();
            }

            assertThat(values)
                    .as("All 64 fireReload calls must deliver their value to the callback")
                    .hasSize(threads);
        }

        private static Void fireAndReturn(KernelConfigRegistry reg,
                                          String file, String key, String value) {
            reg.fireReload(file, key, value);
            return null;
        }
    }
}

