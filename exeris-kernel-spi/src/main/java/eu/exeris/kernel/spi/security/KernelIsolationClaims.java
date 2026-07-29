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
 *   <tr><td>{@link #SHARED_SCOPE_KEY}</td><td>Never — orthogonal to the strategy, not a sub-claim</td>
 *       <td>Shared-scope partition identifier; absent means tenant-private. Currently always denied —
 *       see the constant's own documentation</td></tr>
 * </table>
 *
 * <h2>Fail-Closed Rule (ADR-012 §4a, amended 2026-06-10 — S-P0-07)</h2>
 * <p>Absence and breakage are <b>not</b> the same case:
 * <ul>
 *   <li><b>Absent/blank</b> {@link #ISOLATION_STRATEGY} — no isolation intent was expressed, so the
 *       mapping falls back to {@link ImmutableStorageContext#shared(long, long)} (a SHARED/RLS context).
 *       This is the <i>only</i> permissive fall-through.</li>
 *   <li><b>Declared but unhonourable</b> — an unrecognised strategy value, a wrong-typed claim, or a
 *       missing/blank required sub-claim — is a <b>terminal deny</b>
 *       ({@code SecurityAuthenticationException}, {@code EX-SEC-2002}), never a downgrade.</li>
 * </ul>
 * <p>Producing {@code SHARED} for a declared-but-broken strategy is <b>fail-OPEN</b>: it silently drops
 * the tenant to the weakest isolation tier and grants a session on malformed or injected security input.
 *
 * <h3>Which layer enforces which case — driver implementors read this</h3>
 * <p>The deny is mandatory in every case above, but it is <b>not</b> enforced in one place:
 * <ul>
 *   <li><b>Unrecognised strategy value</b> and <b>missing/blank sub-claim</b> — enforced by
 *       {@link eu.exeris.kernel.spi.security.identity.IdentityStorageMapping#fromClaims}, the single
 *       kernel-owned mapping every {@code IdentityProvider} routes through. Drivers get this for free.</li>
 *   <li><b>Wrong-typed claim</b> (present but not representable as a single string) — a
 *       <b>{@code TokenValidator} obligation</b>, <i>not</i> covered by {@code fromClaims}. Per
 *       {@link eu.exeris.kernel.spi.security.identity.VerifiedClaims#claim(String)} such a claim is
 *       reported as <i>absent</i>, so it reaches the mapping as the permissive no-intent case and would
 *       resolve to {@code SHARED}. A driver that does not type-check the claim during validation
 *       therefore re-creates the fail-OPEN downgrade at its own layer. The first-party Community driver
 *       discharges this in its token validator; every other driver MUST do the equivalent.</li>
 * </ul>
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
     * or {@code "DEDICATED"}. If <b>absent or blank</b>, the mapping defaults to {@code SHARED}
     * (no isolation intent expressed). An <b>unrecognised</b> value is a terminal deny
     * ({@code EX-SEC-2002}) in the mapping; a <b>wrong-typed</b> value must be denied by the driver's
     * {@code TokenValidator} before the mapping sees it — neither is ever a downgrade. See the
     * class-level fail-closed rule for which layer owns which case.
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
     * <p>If this claim is absent or blank when {@link #ISOLATION_STRATEGY} is
     * {@code "SEPARATED_SCHEMA"}, the declared strategy cannot be honoured and the mapping MUST
     * <b>deny</b> ({@code EX-SEC-2002}) — falling back to {@code SHARED} here would be fail-OPEN.
     */
    public static final String SCHEMA_NAME = "x-exeris-isolation-schema";

    /**
     * JWT claim carrying the datasource routing key for the
     * {@link StorageContext.IsolationStrategy#DEDICATED} strategy.
     *
     * <p>The value must exactly match a key present in
     * {@link eu.exeris.kernel.spi.persistence.PersistenceConfig#dedicatedDataSources()}.
     * If this claim is absent or blank when {@link #ISOLATION_STRATEGY} is {@code "DEDICATED"},
     * the declared strategy cannot be honoured and the mapping MUST <b>deny</b>
     * ({@code EX-SEC-2002}) — falling back to {@code SHARED} here would be fail-OPEN.
     *
     * <p>If the key is present in the JWT but not found in the configured
     * {@code dedicatedDataSources} map at connection time, the Persistence layer
     * throws {@link eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException}
     * with {@code EX-PERS-5006}.
     */
    public static final String DATASOURCE_KEY = "x-exeris-isolation-datasource";

    /**
     * JWT claim declaring the shared-scope partition this subject participates in
     * ({@link StorageContext#sharedScopeKey()}, ADR-012 §4b).
     *
     * <p>Orthogonal to {@link #ISOLATION_STRATEGY}: it composes with any physical strategy rather than
     * selecting one. Absent means the tenant-private default. The name deliberately departs from the
     * {@code x-exeris-isolation-*} family — shared scope is a row-<i>visibility</i> concern, not an
     * isolation-strategy sub-claim, and naming it under {@code isolation-} would re-weld it to the
     * placement axis ADR-012 §4b separates it from.
     *
     * <p><b>Currently always denied.</b> No persistence binding implements the read-widen /
     * owner-scoped-write mode yet, so a token declaring this claim is a terminal deny
     * ({@code EX-SEC-2002}, reason {@code shared-scope-unsupported}). Resolving it to a tenant-private
     * context instead would silently narrow what the caller asked for, and resolving it to a widened one
     * would grant visibility nothing enforces — ADR-012 §4b.5 forbids both. The deny lifts, per
     * deployment, when a binding can honour it.
     *
     * @since 0.11.0
     */
    public static final String SHARED_SCOPE_KEY = "x-exeris-shared-scope";

    private KernelIsolationClaims() {
        // Utility class — not instantiable.
    }
}
