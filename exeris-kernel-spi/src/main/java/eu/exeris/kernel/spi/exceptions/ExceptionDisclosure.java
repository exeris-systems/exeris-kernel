/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.config.KernelProfile;
import eu.exeris.kernel.spi.context.KernelProviders;

/**
 * Profile-aware disclosure helpers for {@link ExerisKernelException} rendering.
 *
 * <h2>Why this exists</h2>
 * <p>The Exeris Glass-Box telemetry pipeline keeps {@code rawArgs} and stack traces
 * available for binary forensic decoding, but operator-visible artifacts (log lines,
 * HTTP error bodies, console output) must never leak operational primitives in PROD.
 * This utility centralises the redaction policy so every Community sink (and any
 * future Enterprise renderer) honours the same contract:
 *
 * <ul>
 *   <li>{@link KernelProfile#DEV} — full disclosure: message, rawArgs, stack trace.</li>
 *   <li>{@link KernelProfile#TEST}, {@link KernelProfile#PROD} — opaque code+traceId
 *       envelope; rawArgs replaced with {@link #EMPTY_ARGS}; stack trace suppressed.</li>
 * </ul>
 *
 * <h2>Allocation policy</h2>
 * <p>Disclosure is invoked at the rendering boundary (sink emission, HTTP response
 * shaping) — not on the throw hot-path. A single {@code String.concat} is permitted
 * for the opaque envelope to keep correlation possible. Hot-path code MUST keep using
 * {@link ExerisKernelException#rawArgs()} directly for binary serialisation.
 *
 * <h2>Profile resolution</h2>
 * <p>{@link #activeProfile()} reads {@link KernelProviders#CURRENT_CONFIG} and falls
 * back to {@link KernelProfile#PROD} when the slot is unbound (e.g., during early
 * bootstrap before the config provider is wired). PROD is the safe default — no
 * operator-visible artifact should leak detail before the profile is known.
 *
 * @see ExerisKernelException
 * @see KernelProfile
 * @since 0.7.0
 */
public final class ExceptionDisclosure {

    /** Shared empty array returned by {@link #discloseRawArgs} in non-DEV profiles. */
    public static final Object[] EMPTY_ARGS = new Object[0];

    private static final String OPAQUE_FORMAT_BEFORE_TRACE = " [traceId=";
    private static final String OPAQUE_FORMAT_AFTER_TRACE = "]";

    private ExceptionDisclosure() {
        // Utility class — static helpers only.
    }

    /**
     * Returns the operator-visible message for {@code ex} under {@code profile}.
     *
     * <p>In {@link KernelProfile#DEV} the original {@link Throwable#getMessage()} is
     * returned verbatim. In all other profiles an opaque envelope of the form
     * {@code "<errorCode> [traceId=<uuid>]"} is returned — sufficient for log
     * correlation, free of operational primitives.
     *
     * @param exception non-null kernel exception
     * @param profile   non-null kernel profile
     * @return message safe to surface for the given profile; never {@code null}
     */
    public static String discloseMessage(ExerisKernelException exception, KernelProfile profile) {
        if (profile.enablesFullErrorDisclosure()) {
            String original = exception.getMessage();
            return original == null ? exception.errorCode() : original;
        }
        return exception.errorCode() + OPAQUE_FORMAT_BEFORE_TRACE + exception.traceId() + OPAQUE_FORMAT_AFTER_TRACE;
    }

    /**
     * Returns the rawArgs payload to expose in operator-visible artifacts.
     *
     * <p>DEV returns the original array (same reference, no defensive copy — Glass-Box
     * binary contract is unchanged). PROD/TEST return {@link #EMPTY_ARGS} so renderers
     * never serialise operational primitives.
     *
     * @param exception non-null kernel exception
     * @param profile   non-null kernel profile
     * @return rawArgs to render; never {@code null}
     */
    public static Object[] discloseRawArgs(ExerisKernelException exception, KernelProfile profile) {
        return profile.enablesFullErrorDisclosure() ? exception.rawArgs() : EMPTY_ARGS;
    }

    /**
     * Reports whether the active profile permits stack-trace surfacing.
     *
     * <p>SLF4J-style sinks consult this to decide whether to pass the throwable to
     * the underlying logger (which would otherwise format the full stack frames).
     *
     * @param profile non-null kernel profile
     * @return {@code true} only when {@code profile} is {@link KernelProfile#DEV}
     */
    public static boolean discloseStackTrace(KernelProfile profile) {
        return profile.enablesFullErrorDisclosure();
    }

    /**
     * Resolves the active {@link KernelProfile} for the current scope.
     *
     * <p>Reads {@link KernelProviders#CURRENT_CONFIG} when bound and returns
     * {@code config.kernelSettings().get().profile()}. Falls back to
     * {@link KernelProfile#PROD} when the slot is unbound (early bootstrap or
     * unit-test contexts) — PROD is the safest default because it suppresses
     * detail until the profile is explicitly resolved.
     *
     * @return active kernel profile; never {@code null}
     */
    public static KernelProfile activeProfile() {
        if (!KernelProviders.CURRENT_CONFIG.isBound()) {
            return KernelProfile.PROD;
        }
        ConfigProvider provider = KernelProviders.CURRENT_CONFIG.get();
        ConfigProvider.KernelSettings settings = provider.kernelSettings().get();
        KernelProfile profile = settings == null ? null : settings.profile();
        return profile == null ? KernelProfile.PROD : profile;
    }
}
