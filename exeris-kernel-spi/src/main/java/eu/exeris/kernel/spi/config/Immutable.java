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
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Target:</b> {@code static final} fields only. The compile-time processor in
 *       {@code exeris-kernel-build-config} rejects any other placement.</li>
 *   <li><b>Mutual exclusion:</b> a field annotated {@code @Immutable} must NOT also carry
 *       {@link Dynamic} — the two intents are contradictory and the build-time processor
 *       fails the compilation when both are present on the same element.</li>
 *   <li><b>Runtime enforcement:</b> when a configuration provider's {@code WatchService}
 *       driver observes an on-disk change to an {@code @Immutable} key, it <b>refuses</b>
 *       the reload (the field is never updated) and surfaces a secret-safe structured
 *       event under
 *       {@link eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_CFG_1004}. The previous,
 *       sealed value remains authoritative.</li>
 * </ul>
 *
 * <h2>Community vs Enterprise</h2>
 * <p><b>Community</b>: {@link ConfigProvider#guardImmutable(String, String)} is a no-op —
 * Community does not run a hot-reload watcher, so an {@code @Immutable} key is already
 * effectively sealed (no reload path exists).
 * <p><b>Enterprise</b>: keys are registered explicitly at startup (no reflective classpath
 * scan — banned). The Virtual Thread watcher then refuses any reload attempt on a guarded
 * key and emits the {@code EX-CFG-1004} refusal event.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * public final class SecurityTrustAnchors {
 *     @Immutable(file = "security.properties", key = "security.jwks.uri",
 *                reason = "rotating the JWKS endpoint at runtime would bypass issuer validation")
 *     public static final String JWKS_URI = loadAtBootstrap("security.jwks.uri");
 * }
 *
 * // Register the guard at bootstrap (mirrors @Dynamic's watch() registration):
 * provider.guardImmutable("security.properties", "security.jwks.uri");
 * }</pre>
 *
 * @since 0.9.0
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
