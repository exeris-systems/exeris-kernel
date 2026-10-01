/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.security.jfr;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Why no security context could be established, as carried on {@code SecurityContextMissing}.
 *
 * <h2>Why the set is enumerated</h2>
 * <p>Every value here is one denial an operator has to be able to tell apart, and every reason the
 * event's description names must actually be emitted: an operator filtering on {@code NO_PROVIDER}
 * who gets an empty result can reasonably conclude no deployment was ever misconfigured. Enumerating
 * the reasons is what makes drift between the description and the emitters a compile-time concern
 * instead of a documentation one.
 *
 * <h2>The two error codes are not interchangeable</h2>
 * <p>{@link KernelErrorCodes#EX_SEC_2001} means no context could be established because there was
 * nothing to validate. {@link KernelErrorCodes#EX_SEC_2002} means validation was attempted and
 * failed. The difference is what separates a misconfigured deployment from a rejected caller, and it
 * is the whole reason this enum exists.
 *
 * @since 0.12
 */
public enum SecurityDenialReason {

    /**
     * No security provider is bound, so no request can present a credential anybody would read.
     *
     * <p>The one that matters most: a deployment that never bound a
     * provider denies its entire authenticated surface, and on the wire that is indistinguishable
     * from a fleet of clients that all forgot their tokens — so it gets diagnosed against the
     * clients. This event is the only place the two differ.
     */
    NO_PROVIDER(KernelErrorCodes.EX_SEC_2001),

    /**
     * A provider is bound and the request carried no bearer credential for it to read.
     *
     * <p>The ordinary client error, and the baseline the others are read against: a rise in
     * {@link #NO_PROVIDER} while this stays flat is a deployment change, not a client change.
     */
    TOKEN_MISSING(KernelErrorCodes.EX_SEC_2001),

    /**
     * A credential was presented and the provider rejected it — expired, malformed, or revoked.
     */
    TOKEN_INVALID(KernelErrorCodes.EX_SEC_2002),

    /**
     * The provider failed in a way it does not describe as a rejection.
     *
     * <p>Distinct from {@link #TOKEN_INVALID} because the caller may be blameless: an unreachable
     * JWKS endpoint degrades to "every token fails", which reads as a credential problem and is an
     * outbound-connectivity one.
     */
    PROVIDER_ERROR(KernelErrorCodes.EX_SEC_2002),

    /**
     * Storage-context derivation failed on the pre-authenticated path, after identity was accepted.
     */
    PRE_AUTH_BRIDGE_ERROR(KernelErrorCodes.EX_SEC_2002);

    private final String errorCode;

    SecurityDenialReason(String errorCode) {
        this.errorCode = errorCode;
    }

    /**
     * The canonical {@code EX-SEC-*} code this denial carries.
     *
     * <p>Bound to the reason rather than passed alongside it, so the two cannot drift: a code passed
     * separately at each call site can be paired with the wrong reason at any one of them.
     *
     * @return the error code
     */
    public String errorCode() {
        return errorCode;
    }
}
