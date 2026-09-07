/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code static final} field as a configuration <em>trust anchor</em> that
 * must never be hot-reloaded under any circumstance.
 *
 * <p>{@code @Immutable} is the deliberate inverse of {@link Dynamic}: where
 * {@code @Dynamic} opts a {@code static volatile} field into zero-downtime hot-reload,
 * {@code @Immutable} <em>seals</em> a key for the lifetime of the process. It exists for
 * configuration whose runtime mutation would be a security or correctness hazard:
 *
 * <ul>
 *   <li>security trust anchors (JWKS endpoints, isolation strategy, issuer allow-lists);</li>
 *   <li>tenant isolation boundaries (storage-context routing keys);</li>
 *   <li>native library paths (OpenSSL / FFM load locations).</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * {@snippet lang="java" :
 * public final class SecurityTrustAnchors {
 *     @Immutable(file = "security.properties", key = "security.jwks.uri",
 *                reason = "rotating the JWKS endpoint at runtime would bypass issuer validation")
 *     public static final String JWKS_URI = loadAtBootstrap("security.jwks.uri");
 * }
 *
 * // Register the guard at bootstrap (mirrors @Dynamic's watch() registration):
 * provider.guardImmutable("security.properties", "security.jwks.uri");
 * }
 *
 * @implSpec A provider that runs a hot-reload watcher must, on observing an on-disk change to
 *           a guarded key, <b>refuse</b> the reload — the field is never updated and the
 *           boot-time value stays authoritative for the lifetime of the process — and surface
 *           the refusal as a secret-safe structured event under
 *           {@link eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_CFG_1004} carrying the
 *           file and key name only, never the value. A provider that runs no watcher has
 *           nothing to enforce: with no reload path, the key is already sealed.
 * @apiNote Place this only on a {@code static final} field, and never together with
 *          {@link Dynamic} on the same element — the two intents contradict each other. The
 *          annotation processor in {@code exeris-kernel-build-config} fails the compilation in
 *          both cases, so a misplaced seal is a build error rather than a runtime surprise.
 *          Register the guard explicitly at bootstrap through
 *          {@link ConfigProvider#guardImmutable(String, String)}; the annotation documents the
 *          intent, the registration is what a watcher can act on.
 * @implNote In Community, {@link ConfigProvider#guardImmutable(String, String)} is a no-op,
 *           since Community runs no hot-reload watcher. In Enterprise, keys are registered
 *           explicitly at startup (no reflective classpath scan — banned) and the
 *           virtual-thread watcher refuses every reload attempt on a guarded key, emitting the
 *           {@code EX-CFG-1004} refusal event.
 * @since 0.9
 * @see Dynamic
 * @see ConfigProvider#guardImmutable(String, String)
 * @see eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_CFG_1004
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Immutable {

    /**
     * Configuration file name relative to the config directory.
     *
     * <p>Same resolution rules as {@link Dynamic#file()}: relative to the configured
     * config directory ({@code exeris.config.dir} / {@code EXERIS_CONFIG_DIR} /
     * {@code /etc/exeris/config}).
     *
     * @return file name, e.g., {@code "security.properties"}
     */
    String file();

    /**
     * Dot-notation key path that is sealed against hot-reload.
     *
     * @return key path string, e.g., {@code "security.jwks.uri"}
     */
    String key();

    /**
     * Human-readable rationale for why this key is sealed.
     *
     * <p>Documentation-only — not emitted in telemetry (the refusal event carries the
     * key name, never the value or this reason). Useful for the security key catalog
     * and for code review.
     *
     * @return rationale string; empty by default
     */
    String reason() default "";
}
