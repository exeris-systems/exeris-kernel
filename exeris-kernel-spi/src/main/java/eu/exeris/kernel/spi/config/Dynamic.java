/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code static volatile} field for zero-downtime hot-reload via an
 * implementation-specific virtual-thread configuration watcher.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Target: {@code static volatile} fields only.</li>
 *   <li>Type: Must be an immutable {@code record} or a primitive wrapper.</li>
 *   <li>Update mechanism: atomic pointer swap via {@code VarHandle.setVolatile()}
 *       — no locks, no {@code synchronized}, no heap churn.</li>
 *   <li>Visibility guarantee: all threads see either old or new value (never partial
 *       state) — standard Java volatile happens-before guarantee.</li>
 * </ul>
 *
 * <h2>Community vs Enterprise</h2>
 * <p><b>Community</b>: {@code @Dynamic} fields are registered but never reloaded —
 * {@link ConfigProvider#watch(String, String, java.util.function.Consumer)} is a no-op.
 * <p><b>Enterprise</b>: Fields are registered at startup via an explicit call on the
 * Enterprise {@code ConfigProvider} implementation (no reflection classpath scan — banned).
 * A Virtual Thread watcher (NIO.2 + Virtual Thread) then performs the swap atomically
 * on file change.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // 1. Declare config record (must be immutable)
 * public record PaymentConfig(boolean stripeEnabled, boolean paypalEnabled) {
 *     public static final PaymentConfig DEFAULT = new PaymentConfig(true, false);
 * }
 *
 * // 2. Annotate the static volatile field
 * public final class FeatureFlags {
 *     @Dynamic(file = "features.json", key = "payment")
 *     public static volatile PaymentConfig CURRENT = PaymentConfig.DEFAULT;
 * }
 *
 * // 3. Register explicitly (Enterprise — no classpath scan)
 * enterpriseProvider.registerDynamic(FeatureFlags.class);
 *
 * // 4. Read at runtime — always current, zero lock contention
 * boolean stripe = FeatureFlags.CURRENT.stripeEnabled();
 * }</pre>
 *
 * <h2>Performance</h2>
 * <ul>
 *   <li>Read cost: 1 volatile load (~5 CPU cycles on x86_64).</li>
 *   <li>Write cost: 1 volatile store (~10 CPU cycles) — only on file change.</li>
 *   <li>After JVM warm-up, JIT may constant-fold if the field is effectively final.</li>
 * </ul>
 *
 * @since 0.5.0
 * @see ConfigProvider#watch(String, String, java.util.function.Consumer)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Dynamic {

    /**
     * Configuration file name relative to the config directory.
     *
     * <p>Default config directory: {@code /etc/exeris/config} (K8s ConfigMap mount point),
     * overridden by {@code exeris.config.dir} system property or {@code EXERIS_CONFIG_DIR}
     * environment variable.
     *
     * @return file name, e.g., {@code "features.json"} or {@code "application.properties"}
     */
    String file();

    /**
     * Dot-notation key path to extract the value.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "payment"} → {@code config.payment}</li>
     *   <li>{@code "network.port"} → {@code config.network.port}</li>
     * </ul>
     *
     * @return key path string
     */
    String key();

    /**
     * Whether this field is mandatory.
     *
     * <p>If {@code true} and the key is absent at startup, the kernel halts
     * immediately ({@code EX-CFG-1001 FAIL_FAST}).
     *
     * @return {@code true} if the field must be present
     */
    boolean required() default true;
}

