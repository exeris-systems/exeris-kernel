/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.abi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TCK: Abstract base proving that every native symbol a binding declares it needs resolves in the
 * library the running host provides.
 *
 * <p>A binding supplies its {@link #manifest() manifest} - derived from the descriptor tables its
 * loader binds from, never hand-listed - and the way to {@link #resolve resolve} one entry. The
 * suite then judges the host, not the binding's logic:
 * <ol>
 *   <li>the manifest is non-empty and names no symbol twice;</li>
 *   <li>every {@link Resolution#REQUIRED} entry that applies to the current platform resolves, and
 *       every miss is named in one failure;</li>
 *   <li>every alternatives group of the current platform has at least one member that resolves;</li>
 *   <li>on a {@link #pinnedMatrixRun() pinned matrix run}, every {@link Resolution#OPTIONAL} entry
 *       resolves too, so a symbol that a pinned library major no longer exports fails the run
 *       instead of silently disabling the feature behind it.</li>
 * </ol>
 *
 * <p>The optional-symbol case is reported as aborted, never as passed, when the run is not pinned.
 *
 * <p>Uses JDK types only: the binding owns the lookup, so this contract needs no knowledge of any
 * loader.
 *
 * <h2>Usage</h2>
 * {@snippet lang="java" :
 * class CoreAbiSymbolResolutionTckTest extends AbstractAbiSymbolResolutionTck {
 *     \@Override protected List<AbiSymbol> manifest() { return symbolsFromDescriptorTable(); }
 *     \@Override protected Optional<MemorySegment> resolve(AbiSymbol symbol) { return lookup(symbol); }
 *     \@Override protected boolean pinnedMatrixRun() { return pinnedMajorIsSet(); }
 * }
 * }
 *
 * @since 0.13
 */
public abstract class AbstractAbiSymbolResolutionTck {

    /** Whether a missing symbol is fatal for the binding. */
    public enum Resolution {
        /** A missing symbol fails the binding's bootstrap. */
        REQUIRED,
        /** A missing symbol is tolerated; the feature behind it degrades. */
        OPTIONAL
    }

    /** The platform family a symbol belongs to. */
    public enum Platform {
        /** Applies on every platform. */
        ANY,
        /** Applies on POSIX hosts only. */
        POSIX,
        /** Applies on Windows hosts only. */
        WINDOWS;

        /**
         * Whether a symbol declared for this platform applies on {@code current}.
         *
         * @param current the platform the suite is running on
         * @return {@code true} for {@link #ANY} and for an equal platform
         */
        public boolean appliesOn(Platform current) {
            return this == ANY || this == current;
        }
    }

    /**
     * One native symbol a binding needs.
     *
     * @param library     a label naming where the binding resolves the symbol, for diagnostics only
     * @param name        the exported symbol name
     * @param resolution  whether its absence is fatal
     * @param platform    the platform family it belongs to
     * @param anyOfGroup  the name of an alternatives group in which one resolved member suffices, or
     *                    {@code null} when the symbol stands alone; a group member is never
     *                    {@link Resolution#REQUIRED} on its own
     */
    public record AbiSymbol(String library, String name, Resolution resolution, Platform platform,
                            String anyOfGroup) {

        /** Validates the entry. */
        public AbiSymbol {
            Objects.requireNonNull(library, "library");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(resolution, "resolution");
            Objects.requireNonNull(platform, "platform");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            if (anyOfGroup != null && resolution == Resolution.REQUIRED) {
                throw new IllegalArgumentException(
                        name + " is a member of group '" + anyOfGroup + "' and cannot also be REQUIRED");
            }
        }

        /**
         * Whether the symbol belongs to an alternatives group.
         *
         * @return {@code true} when {@link #anyOfGroup()} is set
         */
        public boolean inGroup() {
            return anyOfGroup != null;
        }

        @Override
        public String toString() {
            return library + ":" + name;
        }
    }

    /** Constructor for subclasses. */
    protected AbstractAbiSymbolResolutionTck() {
        // binding classes supply the manifest and the lookup
    }

    /**
     * The symbols the binding needs, derived from the descriptor tables its loader binds from.
     *
     * @return the manifest; never empty
     */
    protected abstract List<AbiSymbol> manifest();

    /**
     * Looks one manifest entry up in the library the binding resolves it from.
     *
     * @param symbol the entry to resolve
     * @return the symbol's address, or empty when the library does not export it
     */
    protected abstract Optional<MemorySegment> resolve(AbiSymbol symbol);

    /**
     * Whether this run is pinned to one library major, so that an optional symbol the pinned major
     * lacks is a failure rather than a tolerated degradation.
     *
     * @return {@code true} on a pinned matrix run
     */
    protected abstract boolean pinnedMatrixRun();

    /**
     * The platform the suite is running on. Override only to test the suite itself.
     *
     * @return {@link Platform#WINDOWS} on a Windows host, {@link Platform#POSIX} otherwise
     */
    protected Platform currentPlatform() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                ? Platform.WINDOWS : Platform.POSIX;
    }

    @Test
    @DisplayName("The manifest is non-empty and names no symbol twice")
    void manifestIsNonEmptyAndUnique() {
        List<AbiSymbol> manifest = manifest();
        assertThat(manifest).as("a binding with no declared symbols proves nothing").isNotEmpty();
        Set<String> seen = new HashSet<>();
        List<String> duplicates = manifest.stream()
                .map(s -> s.library() + ":" + s.name() + "@" + s.platform())
                .filter(key -> !seen.add(key))
                .toList();
        assertThat(duplicates).as("symbols declared twice in the manifest").isEmpty();
    }

    @Test
    @DisplayName("Every REQUIRED symbol of the current platform resolves, and all misses are reported together")
    void everyRequiredSymbolResolves() {
        List<AbiSymbol> missing = manifest().stream()
                .filter(s -> s.resolution() == Resolution.REQUIRED)
                .filter(s -> s.platform().appliesOn(currentPlatform()))
                .filter(s -> resolve(s).isEmpty())
                .toList();
        assertThat(missing).as("REQUIRED symbols the host library does not export").isEmpty();
    }

    @Test
    @DisplayName("Every alternatives group of the current platform has a member that resolves")
    void everyAlternativesGroupHasAResolvedMember() {
        TreeMap<String, List<AbiSymbol>> groups = manifest().stream()
                .filter(AbiSymbol::inGroup)
                .filter(s -> s.platform().appliesOn(currentPlatform()))
                .collect(Collectors.groupingBy(AbiSymbol::anyOfGroup, TreeMap::new, Collectors.toList()));
        List<String> unresolved = groups.entrySet().stream()
                .filter(group -> group.getValue().stream().noneMatch(s -> resolve(s).isPresent()))
                .map(group -> group.getKey() + " " + group.getValue())
                .toList();
        assertThat(unresolved).as("alternatives groups with no resolvable member").isEmpty();
    }

    @Test
    @DisplayName("On a pinned matrix run every OPTIONAL symbol resolves too")
    void everyOptionalSymbolResolvesOnAPinnedRun() {
        assumeTrue(pinnedMatrixRun(),
                "optional-symbol coverage runs only on a run pinned to one library major");
        List<AbiSymbol> missing = manifest().stream()
                .filter(s -> s.resolution() == Resolution.OPTIONAL && !s.inGroup())
                .filter(s -> s.platform().appliesOn(currentPlatform()))
                .filter(s -> resolve(s).isEmpty())
                .toList();
        assertThat(missing).as("OPTIONAL symbols the pinned library does not export").isEmpty();
    }
}
