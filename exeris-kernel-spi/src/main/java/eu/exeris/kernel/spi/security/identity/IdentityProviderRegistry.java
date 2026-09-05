/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security.identity;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * SPI: Priority-ordered selection of a single {@link IdentityProvider} for a raw token (ADR-040).
 *
 * <p>Mirrors the resolution shape of {@code HttpResponseBodyDecoderRegistry} (ADR-034): higher
 * {@link IdentityProvider#priority()} wins; ties resolve by registration order; the first
 * candidate whose {@link IdentityProvider#canAttempt(LoanedBuffer)} returns {@code true} is
 * selected. {@code null} is returned when no provider claims the token — the dispatcher
 * ({@code SecurityProvider}) maps that to a terminal {@code EX-SEC-2002} deny, never fail-open.
 *
 * <h2>Fail-closed dispatch (ADR-012)</h2>
 * <p>This registry selects <b>exactly one</b> provider. The caller MUST treat the selected
 * provider's {@link IdentityProvider#authenticate} failure as terminal and MUST NOT re-select a
 * different provider for the same token — re-dispatch on failure is token-confusion / fail-open.
 *
 * @since 0.10
 * @see IdentityProvider
 */
@FunctionalInterface
public interface IdentityProviderRegistry {

    /**
     * Selects the single provider that should validate the given token.
     *
     * @param rawToken the raw credential in a caller-owned loaned buffer; never {@code null}
     * @return the selected provider, or {@code null} when no provider claims the token
     */
    IdentityProvider select(LoanedBuffer rawToken);

    /**
     * Returns a registry that selects no provider (every token denies).
     *
     * @return empty registry
     */
    static IdentityProviderRegistry empty() {
        return rawToken -> null;
    }

    /**
     * Returns a registry over the given providers honouring the selection contract:
     * <ul>
     *   <li>Higher {@link IdentityProvider#priority()} wins.</li>
     *   <li>Ties resolve by registration order (first occurrence in {@code providers} wins).</li>
     *   <li>The first candidate whose {@link IdentityProvider#canAttempt(LoanedBuffer)} is
     *       {@code true} is selected; {@code null} when none claims the token.</li>
     * </ul>
     *
     * @param providers ordered list of candidates; non-null, may be empty
     * @return registry over the given providers
     */
    @SuppressWarnings("PMD.ShortMethodName") // 'of' is a standard Java factory idiom (cf. List.of)
    static IdentityProviderRegistry of(List<IdentityProvider> providers) {
        Objects.requireNonNull(providers, "providers must not be null");
        // Snapshot + stable-sort by descending priority; ties preserve insertion order.
        List<IdentityProvider> ordered = new ArrayList<>(providers);
        ordered.sort(Comparator.comparingInt(IdentityProvider::priority).reversed());
        List<IdentityProvider> snapshot = List.copyOf(ordered);
        return rawToken -> {
            Objects.requireNonNull(rawToken, "rawToken must not be null");
            for (IdentityProvider provider : snapshot) {
                if (provider.canAttempt(rawToken)) {
                    return provider;
                }
            }
            return null;
        };
    }
}
