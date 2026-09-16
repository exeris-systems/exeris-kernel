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
 * Marks a {@code static volatile} field for zero-downtime hot-reload via an
 * implementation-specific virtual-thread configuration watcher.
 *
 * <h2>Usage</h2>
 * {@snippet lang="java" :
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
 * }
 *
 * @implSpec A watcher that honours this annotation replaces the field by one atomic pointer
 *           store ({@code VarHandle.setVolatile()} or an equivalent release store) — no lock,
 *           no {@code synchronized} block, no heap churn on the reading side. Every reader
 *           therefore observes either the old value or the new one and never a partially
 *           published object, which is the standard Java {@code volatile} happens-before
 *           guarantee and nothing stronger.
 * @apiNote Place this only on a {@code static volatile} field whose type is an immutable
 *          {@code record} or a primitive wrapper. The reload swaps a single reference, so a
 *          mutable carrier would expose a half-updated object that no {@code volatile} store
 *          can protect. Read the field at the point of use rather than hoisting it into a
 *          local across a long operation — a hoisted read is the one that keeps using the
 *          superseded value after a reload.
 *          <p>A read costs one volatile load (~5 CPU cycles on x86_64); a write costs one
 *          volatile store (~10 cycles) and is paid only when the file changes.
 * @implNote Community providers register {@code @Dynamic} fields but never reload them —
 *           {@link ConfigProvider#watch(String, String, java.util.function.Consumer)} is a
 *           no-op there. Enterprise providers register the field through an explicit call at
 *           startup (no reflective classpath scan — banned), and a virtual-thread NIO.2
 *           watcher performs the swap on file change.
 * @since 0.5
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

