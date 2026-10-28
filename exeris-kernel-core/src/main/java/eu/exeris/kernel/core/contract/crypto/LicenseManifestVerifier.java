/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract.crypto;

import eu.exeris.kernel.core.contract.jfr.ContractEnforcementEvent;
import eu.exeris.kernel.core.contract.jfr.ContractViolationReporter;
import eu.exeris.kernel.spi.contract.ContractViolation;
import eu.exeris.kernel.spi.contract.EnforcementLevel;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.contract.ExecutionEnvironment;
import eu.exeris.kernel.spi.contract.WorkloadEnvelope;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
import eu.exeris.kernel.spi.time.TimeKernelProviders;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Offline cryptographic verification engine for {@code license-manifest.json} (v1).
 *
 * <p>Implements ADR-088 and ADR-089 verification requirements:
 * <ol>
 *   <li>Parses manifest JSON once, with zero external dependencies.</li>
 *   <li>Extracts {@code issuer.keyId} and resolves the trusted issuer key, with its {@link IssuerKeyScope},
 *       from {@link TrustedIssuerKeyStore} (or, outside bootstrap, from a caller-supplied
 *       {@link IssuerKeyResolver}).</li>
 *   <li>Canonicalizes the parsed tree (excluding the detached {@code "signature"} block) using RFC 8785, so
 *       the bytes verified are the bytes of the values read.</li>
 *   <li>Verifies the 64-byte Ed25519 signature via JDK {@link Signature}.</li>
 *   <li>Checks every field against the v1 schema of ADR-088 §1: exact types, exact enumerations, and the
 *       required fields. A value of the wrong type or outside its enumeration is rejected, never coerced or
 *       replaced by a default.</li>
 *   <li>Checks the manifest against the scope of the key that signed it: the environments it authorizes
 *       ({@code EX-LIC-0009}), its validity ({@code EX-LIC-0010}) and its workload envelope
 *       ({@code EX-LIC-0011}), and for a key with a workload ceiling the enforcement levels it sets
 *       ({@code EX-LIC-0013}). A scope breach is {@code HARD}: no {@code enforcementRules} entry softens it.</li>
 *   <li>Performs temporal validation against {@code validFrom}, {@code validUntil}, and {@code gracePeriodDays}.</li>
 *   <li>Reports a {@code SOFT} {@code gracePeriod} violation — a warning and a
 *       {@link ContractEnforcementEvent} — when operating within the grace period.</li>
 *   <li>Materializes the immutable {@link ExecutionContract}.</li>
 * </ol>
 *
 * <p>Every schema violation fails with {@code EX-LIC-0001}; the manifest is signed, so a malformed one is
 * an issuer defect and is never read leniently. When several rules fail, the first of the order above
 * names the code, so a tampered manifest is {@code EX-LIC-0001} and a manifest out of its key's scope is a
 * scope breach even when it has also expired. The contract gate's own checks (the class of a declared requirement, the
 * declared environment) run after this verification, so an expired manifest reports its time code before them.
 *
 * @since 0.13
 */
@SuppressWarnings({
        "PMD.CyclomaticComplexity",
        "PMD.TooManyMethods",
        "PMD.GodClass"
})
public final class LicenseManifestVerifier {

    /** The {@code $schema} value of a v1 manifest. */
    public static final String SCHEMA_V1 = "https://specs.exeris.eu/schema/v1/license-manifest.json";

    private static final int ED25519_SIGNATURE_LENGTH = 64;
    private static final int DEFAULT_GRACE_PERIOD_DAYS = 14;
    /** Largest integer a JSON number carries exactly (2^53); the canonical form is an IEEE-754 double. */
    private static final double MAX_SAFE_INTEGER = 9_007_199_254_740_992d;

    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "$schema", "contract", "entitlement", "execution", "enforcementRules", "issuer", "signature"
    );
    private static final Set<String> VALID_COMMERCIAL_MODELS = Set.of(
            "STANDARD", "ENTERPRISE", "CAPACITY", "VALUE_SHARE", "OEM"
    );
    private static final Set<String> VALID_EDITIONS = Set.of(
            "community", "commercial", "enterprise"
    );
    private static final String PERPETUAL_INTERNAL = "PERPETUAL_INTERNAL";
    private static final Set<String> VALID_LICENSE_MODES = Set.of(
            "SUBSCRIPTION", PERPETUAL_INTERNAL
    );
    private static final Set<String> VALID_ENVIRONMENTS = Arrays.stream(ExecutionEnvironment.values())
            .map(ExecutionEnvironment::id)
            .collect(Collectors.toUnmodifiableSet());
    private LicenseManifestVerifier() {}

    /**
     * Verifies the given license manifest JSON string against the instant of the kernel time source.
     *
     * @param manifestJson raw JSON string
     * @return materialized execution contract
     * @throws ContractBreachException with {@code EX-LIC-0001} if the manifest is not valid UTF-8 or JSON,
     *                                 breaks the v1 schema or fails its signature; {@code EX-LIC-0002} if
     *                                 its issuer key is unknown; {@code EX-LIC-0003} if it expired beyond
     *                                 its grace period; {@code EX-LIC-0006} if it is not yet valid;
     *                                 {@code EX-LIC-0009} to {@code EX-LIC-0011} or {@code EX-LIC-0013} if
     *                                 it breaks the scope of its issuer key, as
     *                                 {@link #verifyScoped(String, Instant, IssuerKeyResolver)} lists
     */
    public static ExecutionContract verify(String manifestJson) {
        return verifyAtKernelTime(manifestJson, IssuerKeyResolver.embedded());
    }

    /**
     * Verifies the given license manifest JSON bytes against the instant of the kernel time source.
     *
     * @param manifestBytes UTF-8 JSON bytes
     * @return materialized execution contract
     * @throws ContractBreachException with {@code EX-LIC-0001} if the manifest is not valid UTF-8 or JSON,
     *                                 breaks the v1 schema or fails its signature; {@code EX-LIC-0002} if
     *                                 its issuer key is unknown; {@code EX-LIC-0003} if it expired beyond
     *                                 its grace period; {@code EX-LIC-0006} if it is not yet valid;
     *                                 {@code EX-LIC-0009} to {@code EX-LIC-0011} or {@code EX-LIC-0013} if
     *                                 it breaks the scope of its issuer key, as
     *                                 {@link #verifyScoped(String, Instant, IssuerKeyResolver)} lists
     */
    public static ExecutionContract verify(byte[] manifestBytes) {
        Objects.requireNonNull(manifestBytes, "manifestBytes must not be null");
        return verifyAtKernelTime(JsonCanonicalizer.decodeUtf8(manifestBytes), IssuerKeyResolver.embedded());
    }

    /**
     * Verifies at the instant the kernel time source reports (ADR-082): the platform clock until a time
     * provider is bound.
     */
    /* default */ static ExecutionContract verifyAtKernelTime(String manifestJson, IssuerKeyResolver keyResolver) {
        return verify(manifestJson, TimeKernelProviders.timeSource().wallTime(), keyResolver);
    }

    /**
     * Verifies the given license manifest JSON bytes against a specific evaluation timestamp.
     *
     * @param manifestBytes  UTF-8 JSON bytes; malformed UTF-8 is rejected with EX-LIC-0001
     * @param evaluationTime evaluation instant for temporal validity
     * @return materialized execution contract
     * @throws ContractBreachException with {@code EX-LIC-0001} if the manifest is not valid UTF-8 or JSON,
     *                                 breaks the v1 schema or fails its signature; {@code EX-LIC-0002} if
     *                                 its issuer key is unknown; {@code EX-LIC-0003} if it expired beyond
     *                                 its grace period; {@code EX-LIC-0006} if it is not yet valid;
     *                                 {@code EX-LIC-0009} to {@code EX-LIC-0011} or {@code EX-LIC-0013} if
     *                                 it breaks the scope of its issuer key, as
     *                                 {@link #verifyScoped(String, Instant, IssuerKeyResolver)} lists
     */
    public static ExecutionContract verify(byte[] manifestBytes, Instant evaluationTime) {
        Objects.requireNonNull(manifestBytes, "manifestBytes must not be null");
        return verify(JsonCanonicalizer.decodeUtf8(manifestBytes), evaluationTime);
    }

    /**
     * Verifies the given license manifest JSON string against a specific evaluation timestamp.
     *
     * @param manifestJson   raw JSON string
     * @param evaluationTime evaluation instant for temporal validity
     * @return materialized execution contract
     * @throws ContractBreachException with {@code EX-LIC-0001} if the manifest is not valid UTF-8 or JSON,
     *                                 breaks the v1 schema or fails its signature; {@code EX-LIC-0002} if
     *                                 its issuer key is unknown; {@code EX-LIC-0003} if it expired beyond
     *                                 its grace period; {@code EX-LIC-0006} if it is not yet valid;
     *                                 {@code EX-LIC-0009} to {@code EX-LIC-0011} or {@code EX-LIC-0013} if
     *                                 it breaks the scope of its issuer key, as
     *                                 {@link #verifyScoped(String, Instant, IssuerKeyResolver)} lists
     */
    public static ExecutionContract verify(String manifestJson, Instant evaluationTime) {
        return verify(manifestJson, evaluationTime, IssuerKeyResolver.embedded());
    }

    /**
     * Verifies the given license manifest JSON string against a specific evaluation timestamp,
     * resolving the issuer key through {@code keyResolver} instead of the embedded trust roots.
     *
     * <p>The kernel bootstrap never calls this overload; a contract it returns is not bound into the
     * kernel scope. It exists so a manifest can be verified against a key the caller holds, such as
     * a test issuer key.
     *
     * @param manifestJson   raw JSON string
     * @param evaluationTime evaluation instant for temporal validity
     * @param keyResolver    resolves {@code issuer.keyId} to an Ed25519 public key
     * @return materialized execution contract
     * @throws ContractBreachException with {@code EX-LIC-0001} if the manifest is not valid UTF-8 or JSON,
     *                                 breaks the v1 schema or fails its signature; {@code EX-LIC-0002} if
     *                                 its issuer key is unknown; {@code EX-LIC-0003} if it expired beyond
     *                                 its grace period; {@code EX-LIC-0006} if it is not yet valid;
     *                                 {@code EX-LIC-0009} to {@code EX-LIC-0011} or {@code EX-LIC-0013} if
     *                                 it breaks the scope of its issuer key, as
     *                                 {@link #verifyScoped(String, Instant, IssuerKeyResolver)} lists
     */
    public static ExecutionContract verify(
            String manifestJson, Instant evaluationTime, IssuerKeyResolver keyResolver) {
        return verifyScoped(manifestJson, evaluationTime, keyResolver).contract();
    }

    /**
     * Verifies the given license manifest JSON string like
     * {@link #verify(String, Instant, IssuerKeyResolver)} and returns, with the contract, the key that
     * signed it and the {@code issuer.issuedAt} instant it carries.
     *
     * <p>The checks run in a fixed order, which decides the code when several rules fail: parse; signature
     * block, issuer key and its resolution ({@code EX-LIC-0002}); signature ({@code EX-LIC-0001}); schema
     * ({@code EX-LIC-0001}); the key's scope ({@code EX-LIC-0009}, {@code EX-LIC-0010}, {@code EX-LIC-0011},
     * then {@code EX-LIC-0013} for a key with a workload ceiling and {@code EX-LIC-0001} for a key without
     * one that sets the workload envelope to {@code HARD}); the validity window ({@code EX-LIC-0006},
     * {@code EX-LIC-0003}). No enforcement rule softens a scope breach.
     *
     * <p>The kernel bootstrap never calls this overload with a resolver of the caller's choosing; a contract
     * it returns is not bound into the kernel scope.
     *
     * @param manifestJson   raw JSON string
     * @param evaluationTime evaluation instant for temporal validity
     * @param keyResolver    resolves {@code issuer.keyId} to a scoped Ed25519 public key
     * @return the materialized contract, the issuer key that signed it and its {@code issuedAt}
     * @throws ContractBreachException with {@code EX-LIC-0001} if the manifest is not valid UTF-8 or JSON,
     *                                 breaks the v1 schema or fails its signature; {@code EX-LIC-0002} if
     *                                 its issuer key is unknown; {@code EX-LIC-0009} if it authorizes an
     *                                 environment outside the key's scope; {@code EX-LIC-0010} if its
     *                                 validity is outside what the key allows; {@code EX-LIC-0011} if its
     *                                 workload envelope is above the key's ceiling or absent;
     *                                 {@code EX-LIC-0013} if a key with a ceiling signed a capability or
     *                                 environment rule below {@code HARD}; {@code EX-LIC-0003} if it expired
     *                                 beyond its grace period; {@code EX-LIC-0006} if it is not yet valid
     * @since 0.13
     */
    public static VerifiedManifest verifyScoped(
            String manifestJson, Instant evaluationTime, IssuerKeyResolver keyResolver) {
        Objects.requireNonNull(manifestJson, "manifestJson must not be null");
        Objects.requireNonNull(evaluationTime, "evaluationTime must not be null");
        Objects.requireNonNull(keyResolver, "keyResolver must not be null");

        Map<String, Object> root = JsonCanonicalizer.parseJsonObject(manifestJson);

        byte[] signatureBytes = signatureBytes(root);
        TrustedIssuerKey key = keyResolver.resolve(issuerKeyId(root));
        verifySignature(key.publicKey(), JsonCanonicalizer.canonicalizeDetached(root, "signature"), signatureBytes);

        Materialized materialized = materialize(root);
        checkScope(key, materialized.contract(), materialized.issuedAt());
        checkTemporalValidity(materialized.contract(), evaluationTime);
        return new VerifiedManifest(materialized.contract(), key, materialized.issuedAt());
    }

    /**
     * A verified manifest: its contract, the issuer key that signed it and the instant the issuer says it
     * signed.
     *
     * @param contract  the materialized, immutable contract
     * @param key       the issuer key whose signature verified, with its scope
     * @param issuedAt  {@code issuer.issuedAt}, or {@code null} when the manifest carries none
     * @since 0.13
     */
    public value record VerifiedManifest(ExecutionContract contract, TrustedIssuerKey key, Instant issuedAt) {

        /**
         * Compact constructor rejecting a null contract or key.
         */
        public VerifiedManifest {
            Objects.requireNonNull(contract, "contract must not be null");
            Objects.requireNonNull(key, "key must not be null");
        }
    }

    private value record Materialized(ExecutionContract contract, Instant issuedAt) {
    }

    // ── Signature ───────────────────────────────────────────────────────────

    private static byte[] signatureBytes(Map<String, Object> root) {
        Map<?, ?> signature = requiredObject(root, "signature");
        if (!"Ed25519".equals(signature.get("algorithm"))) {
            throw breach("signature.algorithm must be exactly \"Ed25519\"");
        }
        if (!"RFC-8785".equals(signature.get("canonicalization"))) {
            throw breach("signature.canonicalization must be exactly \"RFC-8785\"");
        }
        String value = requiredString(signature, "value", "signature.value");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0001, "Malformed Base64 signature in manifest", e);
        }
        if (bytes.length != ED25519_SIGNATURE_LENGTH) {
            throw breach("Ed25519 signature must be exactly 64 bytes");
        }
        return bytes;
    }

    private static String issuerKeyId(Map<String, Object> root) {
        if (!(root.get("issuer") instanceof Map<?, ?> issuer)) {
            throw breach("Manifest missing 'issuer' block");
        }
        if (!(issuer.get("keyId") instanceof String keyId) || keyId.isBlank()) {
            throw breach("issuer.keyId must be a non-blank string");
        }
        return keyId;
    }

    private static void verifySignature(PublicKey publicKey, byte[] canonicalPayload, byte[] signatureBytes) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(canonicalPayload);
            if (!signature.verify(signatureBytes)) {
                throw breach("Cryptographic license signature verification failed");
            }
        } catch (GeneralSecurityException ex) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0001,
                    "Signature verification error: " + ex.getMessage(),
                    ex
            );
        }
    }

    // ── Schema and materialization ──────────────────────────────────────────

    private static Materialized materialize(Map<String, Object> root) {
        for (String key : root.keySet()) {
            if (!TOP_LEVEL_KEYS.contains(key)) {
                throw breach("Unknown top-level field '" + key + "' in a v1 manifest");
            }
        }
        if (!SCHEMA_V1.equals(root.get("$schema"))) {
            throw breach("$schema must be exactly \"" + SCHEMA_V1 + "\"");
        }
        Instant issuedAt = issuerIssuedAt(root);

        // Contract block
        Map<?, ?> contract = requiredObject(root, "contract");
        String contractId = requiredString(contract, "id", "contract.id");
        optionalString(contract, "framework", "contract.framework");
        String commercialModel = requiredEnum(
                contract, "commercialModel", "contract.commercialModel", VALID_COMMERCIAL_MODELS);

        // Entitlement block
        Map<?, ?> entitlement = requiredObject(root, "entitlement");
        String edition = requiredEnum(entitlement, "edition", "entitlement.edition", VALID_EDITIONS);
        String sku = requiredString(entitlement, "sku", "entitlement.sku");
        String licenseMode = requiredEnum(
                entitlement, "licenseMode", "entitlement.licenseMode", VALID_LICENSE_MODES);
        optionalString(entitlement, "workloadProfileRef", "entitlement.workloadProfileRef");
        Set<String> capabilities = requiredStringSet(entitlement, "capabilities", "entitlement.capabilities");
        for (String capability : capabilities) {
            if (!ExecutionContract.isCapabilityId(capability)) {
                throw breach("entitlement.capabilities: '" + capability + "' is not a lowercase kebab-case identifier");
            }
        }

        // Execution block
        Map<?, ?> execution = requiredObject(root, "execution");
        Set<String> environments = requiredStringSet(execution, "environments", "execution.environments");
        if (environments.isEmpty()) {
            throw breach("execution.environments must name at least one environment");
        }
        for (String environment : environments) {
            if (!VALID_ENVIRONMENTS.contains(environment)) {
                throw breach("execution.environments: '" + environment + "' is not one of " + VALID_ENVIRONMENTS);
            }
        }
        int authorizedInstances = (int) requiredInteger(
                execution, "authorizedInstances", "execution.authorizedInstances", Integer.MAX_VALUE);
        WorkloadEnvelope envelope = workloadEnvelope(execution);

        Instant validFrom = requiredInstant(execution, "validFrom", "execution.validFrom");
        Instant validUntil = validUntil(execution, licenseMode);
        if (validUntil.isBefore(validFrom)) {
            throw breach("execution.validUntil precedes execution.validFrom");
        }
        int gracePeriodDays = execution.containsKey("gracePeriodDays")
                ? (int) requiredInteger(execution, "gracePeriodDays", "execution.gracePeriodDays", Integer.MAX_VALUE)
                : DEFAULT_GRACE_PERIOD_DAYS;

        ExecutionContract built = new ExecutionContract(
                contractId,
                commercialModel,
                edition,
                licenseMode,
                sku,
                capabilities,
                environments,
                authorizedInstances,
                envelope,
                validFrom,
                validUntil,
                gracePeriodDays,
                enforcementRules(root)
        );
        return new Materialized(built, issuedAt);
    }

    /** The last valid instant: required, except under perpetual internal use, where it never ends. */
    private static Instant validUntil(Map<?, ?> execution, String licenseMode) {
        if (execution.containsKey("validUntil")) {
            return requiredInstant(execution, "validUntil", "execution.validUntil");
        }
        if (PERPETUAL_INTERNAL.equals(licenseMode)) {
            return Instant.MAX;
        }
        throw breach("execution.validUntil is required unless licenseMode is " + PERPETUAL_INTERNAL);
    }

    private static void checkTemporalValidity(ExecutionContract contract, Instant evaluationTime) {
        String contractId = contract.contractId();
        Instant validFrom = contract.validFrom();
        Instant validUntil = contract.validUntil();
        int gracePeriodDays = contract.gracePeriodDays();
        if (evaluationTime.isBefore(validFrom)) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0006,
                    "License manifest is not yet valid",
                    contractId, validFrom
            );
        }

        Instant graceDeadline = graceDeadline(validUntil, gracePeriodDays);

        if (!Instant.MAX.equals(graceDeadline) && evaluationTime.isAfter(graceDeadline)) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0003,
                    "License manifest expired beyond grace period",
                    contractId, validUntil, gracePeriodDays
            );
        }

        if (!Instant.MAX.equals(validUntil) && evaluationTime.isAfter(validUntil)) {
            ContractViolationReporter.report(new ContractViolation(
                    contractId,
                    "gracePeriod",
                    EnforcementLevel.SOFT,
                    "validUntil " + validUntil + " passed; grace ends " + graceDeadline));
        }
    }

    /** The last instant a manifest boots: {@code validUntil} plus the grace period, or {@link Instant#MAX}. */
    private static Instant graceDeadline(Instant validUntil, int gracePeriodDays) {
        if (Instant.MAX.equals(validUntil)
                || validUntil.isAfter(Instant.MAX.minus(gracePeriodDays + 1L, ChronoUnit.DAYS))) {
            return Instant.MAX;
        }
        return validUntil.plus(gracePeriodDays, ChronoUnit.DAYS);
    }

    // ── Key scope ───────────────────────────────────────────────────────────

    private static void checkScope(TrustedIssuerKey key, ExecutionContract contract, Instant issuedAt) {
        IssuerKeyScope scope = key.scope();
        checkEnvironmentScope(key, contract);
        if (scope.maxValidity() != null) {
            checkValidityScope(key, contract, issuedAt);
        }
        if (scope.workloadCeiling() != null) {
            checkCeilingScope(key, contract);
            checkDeveloperRules(key, contract);
        } else if (contract.getEnforcement(ExecutionContract.WORKLOAD_ENVELOPE_CONSTRAINT)
                == EnforcementLevel.HARD) {
            throw breach("enforcementRules.workloadEnvelope must not be HARD under issuer key " + key.keyId()
                    + ", which has no workload ceiling");
        }
    }

    private static void checkEnvironmentScope(TrustedIssuerKey key, ExecutionContract contract) {
        Set<String> allowed = key.scope().environments().stream()
                .map(ExecutionEnvironment::id)
                .collect(Collectors.toSet());
        List<String> outside = contract.authorizedEnvironments().stream()
                .filter(environment -> !allowed.contains(environment))
                .sorted()
                .toList();
        if (!outside.isEmpty()) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0009,
                    "License manifest authorizes environments outside the scope of issuer key " + key.keyId(),
                    key.keyId(), outside);
        }
    }

    private static void checkValidityScope(TrustedIssuerKey key, ExecutionContract contract, Instant issuedAt) {
        Duration maxValidity = key.scope().maxValidity();
        Instant lastBoot = graceDeadline(contract.validUntil(), contract.gracePeriodDays());
        String rule = null;
        if (issuedAt == null) {
            rule = "issuer.issuedAt is required under issuer key " + key.keyId();
        } else if (PERPETUAL_INTERNAL.equals(contract.licenseMode())
                || Instant.MAX.equals(contract.validUntil())) {
            rule = "entitlement.licenseMode " + PERPETUAL_INTERNAL + " is not allowed under issuer key "
                    + key.keyId();
        } else if (contract.validFrom().isBefore(issuedAt)) {
            rule = "execution.validFrom precedes issuer.issuedAt";
        } else if (lastBoot.isAfter(issuedAt.plus(maxValidity))) {
            rule = "execution.validUntil plus the grace period is more than " + maxValidity.toDays()
                    + " days after issuer.issuedAt";
        }
        if (rule != null) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0010,
                    "License manifest validity is outside the scope of issuer key " + key.keyId() + ": " + rule,
                    key.keyId(), issuedAt, lastBoot, maxValidity.toDays());
        }
    }

    private static void checkCeilingScope(TrustedIssuerKey key, ExecutionContract contract) {
        WorkloadEnvelope ceiling = key.scope().workloadCeiling();
        WorkloadEnvelope envelope = contract.envelope();
        if (envelope.maxThroughputRps() > ceiling.maxThroughputRps()
                || envelope.maxConnections() > ceiling.maxConnections()
                || envelope.growthAllowancePercent() > ceiling.growthAllowancePercent()) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0011,
                    "License manifest workload envelope is above the ceiling of issuer key " + key.keyId()
                            + " or absent",
                    key.keyId(), envelope.maxThroughputRps(), envelope.maxConnections(),
                    envelope.growthAllowancePercent());
        }
    }

    private static void checkDeveloperRules(TrustedIssuerKey key, ExecutionContract contract) {
        for (String rule : List.of(ExecutionContract.CAPABILITY_CONSTRAINT, ExecutionContract.ENVIRONMENT_CONSTRAINT)) {
            EnforcementLevel level = contract.getEnforcement(rule);
            if (level != EnforcementLevel.HARD) {
                throw new ContractBreachException(
                        KernelErrorCodes.EX_LIC_0013,
                        "Issuer key " + key.keyId() + " has a workload ceiling, so enforcementRules." + rule
                                + " must be HARD",
                        key.keyId(), rule, level.name());
            }
        }
    }

    private static WorkloadEnvelope workloadEnvelope(Map<?, ?> execution) {
        if (!execution.containsKey("workloadEnvelope")) {
            return WorkloadEnvelope.UNLIMITED;
        }
        Map<?, ?> envelope = requiredObject(execution, "workloadEnvelope", "execution.workloadEnvelope");
        long maxThroughputRps = envelope.containsKey("maxThroughputRps")
                ? requiredInteger(envelope, "maxThroughputRps", "execution.workloadEnvelope.maxThroughputRps",
                        (long) MAX_SAFE_INTEGER)
                : Long.MAX_VALUE;
        int maxConnections = envelope.containsKey("maxConnections")
                ? (int) requiredInteger(envelope, "maxConnections", "execution.workloadEnvelope.maxConnections",
                        Integer.MAX_VALUE)
                : Integer.MAX_VALUE;
        int growthAllowance = envelope.containsKey("growthAllowancePercent")
                ? (int) requiredInteger(envelope, "growthAllowancePercent",
                        "execution.workloadEnvelope.growthAllowancePercent", Integer.MAX_VALUE)
                : 0;
        return new WorkloadEnvelope(maxThroughputRps, maxConnections, growthAllowance);
    }

    private static Map<String, EnforcementLevel> enforcementRules(Map<String, Object> root) {
        Map<String, EnforcementLevel> rules = new HashMap<>(ExecutionContract.DEFAULT_ENFORCEMENT);
        if (!root.containsKey("enforcementRules")) {
            return rules;
        }
        Map<?, ?> declared = requiredObject(root, "enforcementRules");
        for (Map.Entry<?, ?> entry : declared.entrySet()) {
            String constraint = (String) entry.getKey();
            if (!ExecutionContract.DEFAULT_ENFORCEMENT.containsKey(constraint)) {
                throw breach("enforcementRules: unknown constraint '" + constraint + "'");
            }
            if (!(entry.getValue() instanceof String level)) {
                throw breach("enforcementRules." + constraint + " must be HARD, SOFT or AUDIT");
            }
            rules.put(constraint, switch (level) {
                case "HARD" -> EnforcementLevel.HARD;
                case "SOFT" -> EnforcementLevel.SOFT;
                case "AUDIT" -> EnforcementLevel.AUDIT;
                default -> throw breach("enforcementRules." + constraint + " must be HARD, SOFT or AUDIT, was '"
                        + level + "'");
            });
        }
        return rules;
    }

    // ── Strict field accessors ──────────────────────────────────────────────

    private static Map<?, ?> requiredObject(Map<?, ?> map, String key) {
        return requiredObject(map, key, key);
    }

    private static Map<?, ?> requiredObject(Map<?, ?> map, String key, String path) {
        if (map.get(key) instanceof Map<?, ?> object) {
            return object;
        }
        throw breach(map.containsKey(key) ? path + " must be an object" : "Manifest missing '" + path + "' block");
    }

    private static String requiredString(Map<?, ?> map, String key, String path) {
        if (map.get(key) instanceof String value && !value.isBlank()) {
            return value;
        }
        throw breach(path + " must be a non-blank string");
    }

    private static void optionalString(Map<?, ?> map, String key, String path) {
        if (map.containsKey(key) && !(map.get(key) instanceof String)) {
            throw breach(path + " must be a string");
        }
    }

    private static String requiredEnum(Map<?, ?> map, String key, String path, Set<String> allowed) {
        String value = requiredString(map, key, path);
        if (!allowed.contains(value)) {
            throw breach(path + " must be one of " + allowed + ", was '" + value + "'");
        }
        return value;
    }

    private static Set<String> requiredStringSet(Map<?, ?> map, String key, String path) {
        if (!(map.get(key) instanceof List<?> list)) {
            throw breach(path + " must be an array of strings");
        }
        Set<String> values = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof String value)) {
                throw breach(path + " must contain only strings");
            }
            if (!values.add(value)) {
                throw breach(path + " contains '" + value + "' twice");
            }
        }
        return values;
    }

    /**
     * Reads a non-negative integral JSON number no larger than {@code max}. A fraction, a negative value,
     * a value above {@code max} or a non-number is rejected, never truncated or wrapped.
     */
    private static long requiredInteger(Map<?, ?> map, String key, String path, long max) {
        if (!(map.get(key) instanceof Double value)) {
            throw breach(path + " must be a number");
        }
        double number = value;
        if (number != Math.rint(number) || number < 0 || number > max) {
            throw breach(path + " must be an integer between 0 and " + max);
        }
        return (long) number;
    }

    private static Instant requiredInstant(Map<?, ?> map, String key, String path) {
        String value = requiredString(map, key, path);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0001, path + " is not an ISO-8601 instant", e);
        }
    }

    /** Reads the issuer block's optional fields and returns {@code issuer.issuedAt}, or {@code null} when absent. */
    private static Instant issuerIssuedAt(Map<String, Object> root) {
        Map<?, ?> issuer = requiredObject(root, "issuer");
        optionalString(issuer, "authority", "issuer.authority");
        return issuer.containsKey("issuedAt") ? requiredInstant(issuer, "issuedAt", "issuer.issuedAt") : null;
    }

    private static ContractBreachException breach(String message) {
        return new ContractBreachException(KernelErrorCodes.EX_LIC_0001, message);
    }
}
