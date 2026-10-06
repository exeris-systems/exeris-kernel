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

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
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
 *   <li>Extracts {@code issuer.keyId} and resolves the trusted public key from {@link TrustedIssuerKeyStore}
 *       (or, outside bootstrap, from a caller-supplied {@link IssuerKeyResolver}).</li>
 *   <li>Canonicalizes the parsed tree (excluding the detached {@code "signature"} block) using RFC 8785, so
 *       the bytes verified are the bytes of the values read.</li>
 *   <li>Verifies the 64-byte Ed25519 signature via JDK {@link Signature}.</li>
 *   <li>Checks every field against the v1 schema of ADR-088 §1: exact types, exact enumerations, and the
 *       required fields. A value of the wrong type or outside its enumeration is rejected, never coerced or
 *       replaced by a default.</li>
 *   <li>Performs temporal validation against {@code validFrom}, {@code validUntil}, and {@code gracePeriodDays}.</li>
 *   <li>Reports a {@code SOFT} {@code gracePeriod} violation — a warning and a
 *       {@link ContractEnforcementEvent} — when operating within the grace period.</li>
 *   <li>Materializes the immutable {@link ExecutionContract}.</li>
 * </ol>
 *
 * <p>Every schema violation fails with {@code EX-LIC-0001}; the manifest is signed, so a malformed one is
 * an issuer defect and is never read leniently.
 *
 * @since 0.13
 */
@SuppressWarnings({
        "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity",
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
     * Verifies the given license manifest JSON string against the current timestamp.
     *
     * @param manifestJson raw JSON string
     * @return materialized execution contract
     * @throws ContractBreachException with {@code EX-LIC-0001} if the manifest is not valid UTF-8 or JSON,
     *                                 breaks the v1 schema or fails its signature; {@code EX-LIC-0002} if
     *                                 its issuer key is unknown; {@code EX-LIC-0003} if it expired beyond
     *                                 its grace period; {@code EX-LIC-0006} if it is not yet valid
     */
    public static ExecutionContract verify(String manifestJson) {
        return verify(manifestJson, Instant.now());
    }

    /**
     * Verifies the given license manifest JSON bytes against the current timestamp.
     *
     * @param manifestBytes UTF-8 JSON bytes
     * @return materialized execution contract
     * @throws ContractBreachException with {@code EX-LIC-0001} if the manifest is not valid UTF-8 or JSON,
     *                                 breaks the v1 schema or fails its signature; {@code EX-LIC-0002} if
     *                                 its issuer key is unknown; {@code EX-LIC-0003} if it expired beyond
     *                                 its grace period; {@code EX-LIC-0006} if it is not yet valid
     */
    public static ExecutionContract verify(byte[] manifestBytes) {
        return verify(manifestBytes, Instant.now());
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
     *                                 its grace period; {@code EX-LIC-0006} if it is not yet valid
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
     *                                 its grace period; {@code EX-LIC-0006} if it is not yet valid
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
     *                                 its grace period; {@code EX-LIC-0006} if it is not yet valid
     */
    public static ExecutionContract verify(
            String manifestJson, Instant evaluationTime, IssuerKeyResolver keyResolver) {
        Objects.requireNonNull(manifestJson, "manifestJson must not be null");
        Objects.requireNonNull(evaluationTime, "evaluationTime must not be null");
        Objects.requireNonNull(keyResolver, "keyResolver must not be null");

        Map<String, Object> root = JsonCanonicalizer.parseJsonObject(manifestJson);

        byte[] signatureBytes = signatureBytes(root);
        PublicKey publicKey = keyResolver.resolve(issuerKeyId(root));
        verifySignature(publicKey, JsonCanonicalizer.canonicalizeDetached(root, "signature"), signatureBytes);

        return materialize(root, evaluationTime);
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

    private static ExecutionContract materialize(Map<String, Object> root, Instant evaluationTime) {
        for (String key : root.keySet()) {
            if (!TOP_LEVEL_KEYS.contains(key)) {
                throw breach("Unknown top-level field '" + key + "' in a v1 manifest");
            }
        }
        if (!SCHEMA_V1.equals(root.get("$schema"))) {
            throw breach("$schema must be exactly \"" + SCHEMA_V1 + "\"");
        }
        Map<?, ?> issuer = requiredObject(root, "issuer");
        optionalString(issuer, "authority", "issuer.authority");
        optionalInstant(issuer, "issuedAt", "issuer.issuedAt");

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
        Instant validUntil;
        if (execution.containsKey("validUntil")) {
            validUntil = requiredInstant(execution, "validUntil", "execution.validUntil");
        } else if (PERPETUAL_INTERNAL.equals(licenseMode)) {
            validUntil = Instant.MAX;
        } else {
            throw breach("execution.validUntil is required unless licenseMode is " + PERPETUAL_INTERNAL);
        }
        if (validUntil.isBefore(validFrom)) {
            throw breach("execution.validUntil precedes execution.validFrom");
        }
        int gracePeriodDays = execution.containsKey("gracePeriodDays")
                ? (int) requiredInteger(execution, "gracePeriodDays", "execution.gracePeriodDays", Integer.MAX_VALUE)
                : DEFAULT_GRACE_PERIOD_DAYS;

        checkTemporalValidity(contractId, evaluationTime, validFrom, validUntil, gracePeriodDays);

        return new ExecutionContract(
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
    }

    private static void checkTemporalValidity(
            String contractId, Instant evaluationTime, Instant validFrom, Instant validUntil, int gracePeriodDays) {
        if (evaluationTime.isBefore(validFrom)) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0006,
                    "License manifest is not yet valid",
                    contractId, validFrom
            );
        }

        Instant graceDeadline;
        if (Instant.MAX.equals(validUntil)
                || validUntil.isAfter(Instant.MAX.minus(gracePeriodDays + 1L, ChronoUnit.DAYS))) {
            graceDeadline = Instant.MAX;
        } else {
            graceDeadline = validUntil.plus(gracePeriodDays, ChronoUnit.DAYS);
        }

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

    private static void optionalInstant(Map<?, ?> map, String key, String path) {
        if (map.containsKey(key)) {
            requiredInstant(map, key, path);
        }
    }

    private static ContractBreachException breach(String message) {
        return new ContractBreachException(KernelErrorCodes.EX_LIC_0001, message);
    }
}
