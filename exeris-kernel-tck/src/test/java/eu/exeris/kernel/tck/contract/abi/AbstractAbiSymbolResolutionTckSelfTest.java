/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.abi;

import eu.exeris.kernel.tck.contract.abi.AbstractAbiSymbolResolutionTck.AbiSymbol;
import eu.exeris.kernel.tck.contract.abi.AbstractAbiSymbolResolutionTck.Platform;
import eu.exeris.kernel.tck.contract.abi.AbstractAbiSymbolResolutionTck.Resolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Meta-test proving {@link AbstractAbiSymbolResolutionTck} fails for the right reason: a fake binding
 * whose library lacks a symbol must fail the matching case and name the symbol, and a complete
 * binding must pass every case.
 */
class AbstractAbiSymbolResolutionTckSelfTest {

    private static final AbiSymbol REQUIRED_PRESENT =
            new AbiSymbol("libfake", "fake_present", Resolution.REQUIRED, Platform.ANY, null);
    private static final AbiSymbol REQUIRED_MISSING =
            new AbiSymbol("libfake", "fake_missing_required", Resolution.REQUIRED, Platform.ANY, null);
    private static final AbiSymbol OPTIONAL_MISSING =
            new AbiSymbol("libfake", "fake_missing_optional", Resolution.OPTIONAL, Platform.ANY, null);
    private static final AbiSymbol OTHER_PLATFORM_MISSING =
            new AbiSymbol("libfake", "fake_other_platform", Resolution.REQUIRED, Platform.WINDOWS, null);
    private static final AbiSymbol GROUP_A =
            new AbiSymbol("libfake", "fake_group_a", Resolution.OPTIONAL, Platform.POSIX, "errno");
    private static final AbiSymbol GROUP_B =
            new AbiSymbol("libfake", "fake_group_b", Resolution.OPTIONAL, Platform.POSIX, "errno");

    @Test
    @DisplayName("a REQUIRED symbol the library lacks fails the required case and is named")
    void missingRequiredSymbolFailsAndIsNamed() {
        FakeBinding binding = new FakeBinding(List.of(REQUIRED_PRESENT, REQUIRED_MISSING), Set.of("fake_present"),
                false);
        assertThatThrownBy(binding::everyRequiredSymbolResolves)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("fake_missing_required")
                .hasMessageNotContaining("libfake:fake_present");
    }

    @Test
    @DisplayName("every miss is reported in one failure")
    void allMissesAreReportedTogether() {
        AbiSymbol second = new AbiSymbol("libfake", "fake_missing_two", Resolution.REQUIRED, Platform.ANY, null);
        FakeBinding binding = new FakeBinding(List.of(REQUIRED_MISSING, second), Set.of(), false);
        assertThatThrownBy(binding::everyRequiredSymbolResolves)
                .hasMessageContaining("fake_missing_required")
                .hasMessageContaining("fake_missing_two");
    }

    @Test
    @DisplayName("a complete binding passes, and a missing OPTIONAL or other-platform symbol is tolerated")
    void tolerantCasesPass() {
        FakeBinding binding = new FakeBinding(
                List.of(REQUIRED_PRESENT, OPTIONAL_MISSING, OTHER_PLATFORM_MISSING, GROUP_A, GROUP_B),
                Set.of("fake_present", "fake_group_b"), false);
        assertThatCode(binding::manifestIsNonEmptyAndUnique).doesNotThrowAnyException();
        assertThatCode(binding::everyRequiredSymbolResolves).doesNotThrowAnyException();
        assertThatCode(binding::everyAlternativesGroupHasAResolvedMember).doesNotThrowAnyException();
        assertThatThrownBy(binding::everyOptionalSymbolResolvesOnAPinnedRun)
                .isInstanceOf(TestAbortedException.class);
    }

    @Test
    @DisplayName("a pinned run fails an OPTIONAL symbol the library lacks, and names it")
    void pinnedRunFailsMissingOptional() {
        FakeBinding binding = new FakeBinding(List.of(REQUIRED_PRESENT, OPTIONAL_MISSING),
                Set.of("fake_present"), true);
        assertThatThrownBy(binding::everyOptionalSymbolResolvesOnAPinnedRun)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("fake_missing_optional");
    }

    @Test
    @DisplayName("an alternatives group with no resolvable member fails and is named")
    void unresolvedGroupFails() {
        FakeBinding binding = new FakeBinding(List.of(GROUP_A, GROUP_B), Set.of(), false);
        assertThatThrownBy(binding::everyAlternativesGroupHasAResolvedMember)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("errno");
    }

    @Test
    @DisplayName("a REQUIRED symbol of the current platform fails when missing, one of another platform does not")
    void platformDirectionDecidesWhatIsRequired() {
        AbiSymbol posixMissing =
                new AbiSymbol("libfake", "fake_posix_missing", Resolution.REQUIRED, Platform.POSIX, null);
        assertThatThrownBy(new FakeBinding(List.of(posixMissing), Set.of(), false)::everyRequiredSymbolResolves)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("fake_posix_missing");
        assertThatCode(new FakeBinding(List.of(OTHER_PLATFORM_MISSING), Set.of(), false)::everyRequiredSymbolResolves)
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an alternatives group of another platform is excluded")
    void otherPlatformGroupIsExcluded() {
        AbiSymbol windowsGroup =
                new AbiSymbol("libfake", "fake_win_group", Resolution.OPTIONAL, Platform.WINDOWS, "winerr");
        assertThatCode(new FakeBinding(List.of(windowsGroup), Set.of(), false)
                ::everyAlternativesGroupHasAResolvedMember).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an empty manifest and a duplicated symbol both fail")
    void emptyAndDuplicateManifestsFail() {
        assertThatThrownBy(new FakeBinding(List.of(), Set.of(), false)::manifestIsNonEmptyAndUnique)
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(new FakeBinding(List.of(REQUIRED_PRESENT, REQUIRED_PRESENT), Set.of("fake_present"),
                false)::manifestIsNonEmptyAndUnique)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("fake_present");
    }

    @Test
    @DisplayName("a REQUIRED group member is rejected at construction")
    void requiredGroupMemberIsRejected() {
        assertThatThrownBy(() -> new AbiSymbol("libfake", "x", Resolution.REQUIRED, Platform.ANY, "g"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Fake binding: resolves exactly the names in {@code exported}; the host platform is POSIX. */
    private static final class FakeBinding extends AbstractAbiSymbolResolutionTck {

        private final List<AbiSymbol> manifest;
        private final Set<String> exported;
        private final boolean pinned;

        FakeBinding(List<AbiSymbol> manifest, Set<String> exported, boolean pinned) {
            this.manifest = manifest;
            this.exported = exported;
            this.pinned = pinned;
        }

        @Override
        protected List<AbiSymbol> manifest() {
            return manifest;
        }

        @Override
        protected Optional<MemorySegment> resolve(AbiSymbol symbol) {
            return exported.contains(symbol.name()) ? Optional.of(MemorySegment.ofAddress(1L)) : Optional.empty();
        }

        @Override
        protected boolean pinnedMatrixRun() {
            return pinned;
        }

        @Override
        protected Platform currentPlatform() {
            return Platform.POSIX;
        }
    }
}
