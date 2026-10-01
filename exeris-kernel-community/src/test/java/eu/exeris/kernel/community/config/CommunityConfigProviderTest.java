/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.config;

import eu.exeris.kernel.spi.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit: {@link CommunityConfigProvider} — key resolution, type coercion, lazy-init.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>getString / getInt / getLong / getBoolean from System properties</li>
 *   <li>Absent key → empty Optional</li>
 *   <li>Malformed integer/long → empty Optional (safe degradation)</li>
 *   <li>watch() is a silent no-op</li>
 *   <li>kernelSettings() is lazy and idempotent</li>
 *   <li>providerName() and priority() correctness</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L1 Unit: CommunityConfigProvider key resolution")
class CommunityConfigProviderTest {

    private static final String SYS_PREFIX = "exeris.";

    private CommunityConfigProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CommunityConfigProvider();
    }

    @AfterEach
    void cleanUpSystemProperties() {
        // Ensure no leaked system properties pollute other tests
        System.clearProperty(SYS_PREFIX + "test.string.key");
        System.clearProperty(SYS_PREFIX + "test.int.key");
        System.clearProperty(SYS_PREFIX + "test.int.bad");
        System.clearProperty(SYS_PREFIX + "test.long.key");
        System.clearProperty(SYS_PREFIX + "test.long.bad");
        System.clearProperty(SYS_PREFIX + "test.bool.key");
    }

    // =========================================================================
    // Provider identity
    // =========================================================================

    @Nested
    @DisplayName("Provider identity")
    class Identity {

        @Test
        @DisplayName("providerName() returns 'CommunityConfigProvider'")
        void providerNameIsCorrect() {
            assertThat(provider.providerName()).isEqualTo("CommunityConfigProvider");
        }

        @Test
        @DisplayName("priority() == 0 (Community Open-Core slot)")
        void priorityIsCommunitySlot() {
            assertThat(provider.priority()).isEqualTo(0);
        }
    }

    // =========================================================================
    // getString
    // =========================================================================

    @Nested
    @DisplayName("getString()")
    class GetString {

        @Test
        @DisplayName("returns empty Optional for absent key")
        void absentKeyReturnsEmpty() {
            assertThat(provider.getString("__nonexistent__.zzz")).isEmpty();
        }

        @Test
        @DisplayName("null key returns empty Optional without throwing")
        void nullKeyReturnsEmpty() {
            assertThatCode(() -> assertThat(provider.getString(null)).isEmpty())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("blank key returns empty Optional without throwing")
        void blankKeyReturnsEmpty() {
            assertThatCode(() -> assertThat(provider.getString("   ")).isEmpty())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("key present via System property returns value")
        void systemPropertyIsResolved() {
            System.setProperty(SYS_PREFIX + "test.string.key", "hello-exeris");
            Optional<String> result = provider.getString("test.string.key");
            assertThat(result).hasValue("hello-exeris");
        }
    }

    // =========================================================================
    // getInt
    // =========================================================================

    @Nested
    @DisplayName("getInt()")
    class GetInt {

        @Test
        @DisplayName("absent key returns empty Optional")
        void absentReturnsEmpty() {
            assertThat(provider.getInt("__nonexistent__.zzz")).isEmpty();
        }

        @Test
        @DisplayName("valid integer System property returns parsed int")
        void validIntParsed() {
            System.setProperty(SYS_PREFIX + "test.int.key", "8443");
            assertThat(provider.getInt("test.int.key")).hasValue(8443);
        }

        @Test
        @DisplayName("malformed integer System property returns empty Optional (safe degradation)")
        void malformedIntReturnsEmpty() {
            System.setProperty(SYS_PREFIX + "test.int.bad", "not-a-number");
            assertThat(provider.getInt("test.int.bad")).isEmpty();
        }

        @Test
        @DisplayName("whitespace-padded integer is parsed correctly")
        void whitespacePaddedIntIsParsed() {
            System.setProperty(SYS_PREFIX + "test.int.key", "  4096  ");
            assertThat(provider.getInt("test.int.key")).hasValue(4096);
        }
    }

    // =========================================================================
    // getLong
    // =========================================================================

    @Nested
    @DisplayName("getLong()")
    class GetLong {

        @Test
        @DisplayName("absent key returns empty Optional")
        void absentReturnsEmpty() {
            assertThat(provider.getLong("__nonexistent__.zzz")).isEmpty();
        }

        @Test
        @DisplayName("valid long System property returns parsed long")
        void validLongParsed() {
            System.setProperty(SYS_PREFIX + "test.long.key", "9876543210");
            assertThat(provider.getLong("test.long.key")).hasValue(9_876_543_210L);
        }

        @Test
        @DisplayName("malformed long returns empty Optional")
        void malformedLongReturnsEmpty() {
            System.setProperty(SYS_PREFIX + "test.long.bad", "xyz");
            assertThat(provider.getLong("test.long.bad")).isEmpty();
        }
    }

    // =========================================================================
    // getBoolean
    // =========================================================================

    @Nested
    @DisplayName("getBoolean()")
    class GetBoolean {

        @Test
        @DisplayName("absent key returns empty Optional")
        void absentReturnsEmpty() {
            assertThat(provider.getBoolean("__nonexistent__.zzz")).isEmpty();
        }

        @Test
        @DisplayName("'true' System property returns Boolean.TRUE")
        void trueStringParsed() {
            System.setProperty(SYS_PREFIX + "test.bool.key", "true");
            assertThat(provider.getBoolean("test.bool.key")).hasValue(Boolean.TRUE);
        }

        @Test
        @DisplayName("'false' System property returns Boolean.FALSE")
        void falseStringParsed() {
            System.setProperty(SYS_PREFIX + "test.bool.key", "false");
            assertThat(provider.getBoolean("test.bool.key")).hasValue(Boolean.FALSE);
        }
    }

    // =========================================================================
    // get(Class)
    // =========================================================================

    @Nested
    @DisplayName("get(key, Class)")
    class GetTyped {

        @Test
        @DisplayName("get(key, String.class) delegates to getString()")
        void delegatesToGetString() {
            System.setProperty(SYS_PREFIX + "test.string.key", "typed-value");
            Optional<String> result = provider.get("test.string.key", String.class);
            assertThat(result).hasValue("typed-value");
        }

        @Test
        @DisplayName("get(key, UnknownType.class) returns empty Optional")
        void unknownTypeReturnsEmpty() {
            Optional<Object> result = provider.get("any.key", Object.class);
            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // watch() — no-op contract
    // =========================================================================

    @Test
    @DisplayName("watch() accepts non-null callback and rejects null callback")
    void watchIsNoOp() {
        assertThatCode(() -> provider.watch(null, "some.key", ignored -> { }))
                .doesNotThrowAnyException();
        assertThatCode(() -> provider.watch("/some/path", null, ignored -> { }))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> provider.watch("/some/path", null, null))
            .isInstanceOf(NullPointerException.class);
    }

    // =========================================================================
    // kernelSettings() — lazy init
    // =========================================================================

    @Nested
    @DisplayName("kernelSettings() — LazyConstant semantics")
    class KernelSettingsLazy {

        @Test
        @DisplayName("kernelSettings() supplier is non-null")
        void supplierIsNonNull() {
            assertThat(provider.kernelSettings()).isNotNull();
        }

        @Test
        @DisplayName("kernelSettings().get() returns non-null KernelSettings")
        void settingsValueIsNonNull() {
            assertThat(provider.kernelSettings().get()).isNotNull();
        }

        @Test
        @DisplayName("kernelSettings().get() returns the same instance on repeated calls (lazy-init)")
        void lazyInitIsIdempotent() {
            ConfigProvider.KernelSettings first  = provider.kernelSettings().get();
            ConfigProvider.KernelSettings second = provider.kernelSettings().get();
            // Must be exactly the same object — not just equal
            assertThat(second).isSameAs(first);
        }

        @Test
        @DisplayName("KernelSettings.profile() is non-null")
        void profileIsNonNull() {
            assertThat(provider.kernelSettings().get().profile()).isNotNull();
        }
    }
}

