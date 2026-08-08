/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.spi.security.identity.ClaimsMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Resolves the {@link ClaimsMapper} the Community identity pipeline maps with.
 *
 * <p>{@code ClaimsMapper}'s own contract calls it "the <b>only</b> application-customisable mapping
 * point in the identity pipeline" — where a deployment decides how {@code sub} becomes a
 * {@code principalId}, which claims carry roles versus scopes, and how a tenant identifier is
 * derived. Until v0.11 that was not reachable: {@code CommunityOidcIdentityProvider} accepted a
 * mapper, but every path {@code CommunitySecurityProvider} used to build it passed none, so a booted
 * kernel could only ever run the default. The seam existed and the road to it did not.
 *
 * <p>Discovery follows the kernel's plugin model rather than introducing a second one:
 * {@link ServiceLoader}, exactly as {@code SecurityProvider} and {@code IdentityProvider} are found.
 *
 * <h2>Two registrations are an error, not a race</h2>
 *
 * <p>{@code ClaimsMapper} carries no {@code priority()}, so two registered mappers cannot be ordered
 * — and picking either one silently would decide, on classpath order, how every principal in the
 * deployment is identified. That is refused with the offending class names rather than resolved by
 * accident, which matches the fail-closed doctrine the surrounding identity code already applies to
 * an unclaimed token.
 *
 * @since 0.11.0
 */
final class CommunityClaimsMapperResolver {

    /** More than this many registered mappers is ambiguous — see {@link #select}. */
    private static final int SINGLE = 1;

    private CommunityClaimsMapperResolver() {
    }

    /**
     * @return the single registered {@link ClaimsMapper}, or the Community default when none is
     *         registered
     * @throws IllegalStateException if more than one mapper is on the classpath
     */
    /* default */ static ClaimsMapper resolve() {
        // Context class loader, not the resolver's own: an application registering a mapper does so on
        // its classpath, and it is also the only seam that lets a test register one without a service
        // file in test resources — which would apply to every test in this module and make the
        // "nothing registered" case unreachable.
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return select(loader == null
                ? ServiceLoader.load(ClaimsMapper.class)
                : ServiceLoader.load(ClaimsMapper.class, loader));
    }

    /**
     * The decision, separated from the lookup so all three outcomes are testable without a service
     * file on the test classpath — which would otherwise apply to every test in the module and make
     * the "nothing registered" case unreachable.
     *
     * @param discovered what {@link ServiceLoader} found
     * @return the mapper to use
     * @throws IllegalStateException if {@code discovered} holds more than one
     */
    /* default */ static ClaimsMapper select(Iterable<ClaimsMapper> discovered) {
        List<ClaimsMapper> found = new ArrayList<>(2);
        for (ClaimsMapper mapper : discovered) {
            found.add(mapper);
        }
        if (found.isEmpty()) {
            return new CommunityClaimsMapper();
        }
        if (found.size() > SINGLE) {
            StringBuilder names = new StringBuilder(found.size() * 40);
            for (ClaimsMapper mapper : found) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(mapper.getClass().getName());
            }
            throw new IllegalStateException(
                    "Ambiguous ClaimsMapper: " + found.size() + " registered (" + names
                    + "). ClaimsMapper has no priority(), so the kernel will not pick one — "
                    + "register exactly one, or none to use the Community default.");
        }
        return found.getFirst();
    }
}
