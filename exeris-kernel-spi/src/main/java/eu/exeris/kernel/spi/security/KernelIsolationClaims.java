/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.security;

/**
 * SPI: Canonical JWT claim names for Exeris tenant-isolation strategy negotiation.
 *
 * <h2>Purpose</h2>
 * <p>Defines the well-known JWT claim keys that the Security subsystem reads during
 * token validation and uses to build the appropriate {@link StorageContext}. The
 * Persistence subsystem never reads these claims directly — it only consumes the
 * already-resolved {@link StorageContext} bound via {@code ScopedValue}.
 *
 * <h2>Claim Semantics</h2>
 * <table>
 *   <caption>Claim descriptions</caption>
 *   <tr><th>Claim</th><th>Required when</th><th>Valid values</th></tr>
 *   <tr><td>{@link #ISOLATION_STRATEGY}</td><td>Always (optional)</td>
 *       <td>{@code "SHARED"}, {@code "SEPARATED_SCHEMA"}, {@code "DEDICATED"}</td></tr>
 *   <tr><td>{@link #SCHEMA_NAME}</td><td>{@code ISOLATION_STRATEGY == "SEPARATED_SCHEMA"}</td>
 *       <td>PostgreSQL schema identifier (e.g. {@code "tenant_acme"})</td></tr>
 *   <tr><td>{@link #DATASOURCE_KEY}</td><td>{@code ISOLATION_STRATEGY == "DEDICATED"}</td>
 *       <td>Key matching an entry in {@link eu.exeris.kernel.spi.persistence.PersistenceConfig#dedicatedDataSources()}
 *   </td></tr>
 * </table>
 *
 * <h2>Fail-Closed Default</h2>
 * <p>If {@link #ISOLATION_STRATEGY} is absent or contains an unrecognised value,
 * the Security layer MUST fall back to
 * {@link ImmutableStorageContext#shared(long, long)} — a SHARED/RLS context.
 * This prevents an attacker from escaping tenant isolation by supplying a
 * crafted or malformed strategy claim.
 *
 * <h2>The Wall</h2>
 * <p>This class is part of {@code exeris-kernel-spi} and carries no runtime
 * dependencies — no JWT library, no persistence driver, no framework code.
 *
 * @since 0.5.0
 * @see StorageContext.IsolationStrategy
 * @see ImmutableStorageContext
 * @see eu.exeris.kernel.spi.persistence.PersistenceConfig#dedicatedDataSources()
 */
public final class KernelIsolationClaims {

    /**
     * JWT claim that encodes the desired {@link StorageContext.IsolationStrategy}.
     *
     * <p>Value is the enum name string: {@code "SHARED"}, {@code "SEPARATED_SCHEMA"},
     * or {@code "DEDICATED"}. If absent or unrecognised, the Security layer MUST
     * default to {@code SHARED} (fail-closed).
     *
     * <p>This claim is consumed exclusively by the Security edge during token parsing.
     * The Persistence subsystem sees only the resulting {@link StorageContext}.
     */
    public static final String ISOLATION_STRATEGY = "x-exeris-isolation-strategy";

    /**
     * JWT claim carrying the PostgreSQL schema name for the
     * {@link StorageContext.IsolationStrategy#SEPARATED_SCHEMA} strategy.
     *
     * <p>The value is used verbatim as the PostgreSQL schema identifier in
     * {@code SET search_path TO &lt;schemaName&gt;}. Must be a valid PostgreSQL
     * identifier (lowercase letters, digits, underscores; max 63 characters).
     *
     * <p>If this claim is absent when {@link #ISOLATION_STRATEGY} is
     * {@code "SEPARATED_SCHEMA"}, the Security layer MUST fall back to {@code SHARED}.
     */
    public static final String SCHEMA_NAME = "x-exeris-isolation-schema";

    /**
     * JWT claim carrying the datasource routing key for the
     * {@link StorageContext.IsolationStrategy#DEDICATED} strategy.
     *
     * <p>The value must exactly match a key present in
     * {@link eu.exeris.kernel.spi.persistence.PersistenceConfig#dedicatedDataSources()}.
     * If this claim is absent when {@link #ISOLATION_STRATEGY} is {@code "DEDICATED"},
     * the Security layer MUST fall back to {@code SHARED}.
     *
     * <p>If the key is present in the JWT but not found in the configured
     * {@code dedicatedDataSources} map at connection time, the Persistence layer
     * throws {@link eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException}
     * with {@code EX-PERS-5006}.
     */
    public static final String DATASOURCE_KEY = "x-exeris-isolation-datasource";

    private KernelIsolationClaims() {
        // Utility class — not instantiable.
    }
}
