/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.contract;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Immutable typed representation of a verified license manifest and capability entitlement contract.
 *
 * <p>Conforms to ADR-088 and ADR-089. Decided by the kernel's contract gate, a bootstrap step that runs
 * before any subsystem is initialized (ahead of the FOUNDATION phase), bound to
 * {@link eu.exeris.kernel.spi.contract.ContractKernelProviders#EXECUTION_CONTRACT}, and immutable for the lifetime
 * of the JVM.
 *
 * <p>{@link #assertCapability(String)} and {@link #assertEnvironment(String)} are start-time checks: call
 * them from a subsystem's {@code initialize()} or {@code start()}, report what they return, and let a
 * {@code HARD} breach fail the start. On a request path use {@link #allowsCapability(String)}, which
 * allocates nothing.
 *
 * @param contractId             unique contract identifier (e.g. {@code "EXR-2026-CAP-0082"})
 * @param commercialModel        commercial schedule model (e.g. {@code "CAPACITY"}, {@code "STANDARD"})
 * @param edition                platform edition ({@code "community"}, {@code "commercial"}, or {@code "enterprise"})
 * @param licenseMode            licensing mode ({@code "SUBSCRIPTION"} or {@code "PERPETUAL_INTERNAL"})
 * @param sku                    SKU package identifier (e.g. {@code "exeris-sku-api-gateway"})
 * @param entitledCapabilities   unmodifiable set of lowercase kebab-case capability identifiers
 * @param authorizedEnvironments unmodifiable set of deployment environments (e.g. {@code "production"})
 * @param authorizedInstances    maximum number of concurrent authorized nodes/instances; not negative
 * @param envelope               workload parameters (RPS, connections, growth allowance)
 * @param validFrom              timestamp when the contract validity starts
 * @param validUntil             timestamp when nominal validity ends
 * @param gracePeriodDays        days after {@code validUntil} during which SOFT operation is permitted; not negative
 * @param enforcementRules       mapping of constraint keys to their {@link EnforcementLevel}; a constraint
 *                               it omits takes its level from {@link #DEFAULT_ENFORCEMENT}
 * @since 0.13
 */
public record ExecutionContract(
        String contractId,
        String commercialModel,
        String edition,
        String licenseMode,
        String sku,
        Set<String> entitledCapabilities,
        Set<String> authorizedEnvironments,
        int authorizedInstances,
        WorkloadEnvelope envelope,
        Instant validFrom,
        Instant validUntil,
        int gracePeriodDays,
        Map<String, EnforcementLevel> enforcementRules
) {

    /** Constraint key of capability entitlement in {@link #enforcementRules()}. */
    public static final String CAPABILITY_CONSTRAINT = "capability";

    /** Constraint key of environment authorization in {@link #enforcementRules()}. */
    public static final String ENVIRONMENT_CONSTRAINT = "environment";

    /** Constraint key of the authorized instance count in {@link #enforcementRules()}. */
    public static final String AUTHORIZED_INSTANCES_CONSTRAINT = "authorizedInstances";

    /** Constraint key of the workload envelope in {@link #enforcementRules()}. */
    public static final String WORKLOAD_ENVELOPE_CONSTRAINT = "workloadEnvelope";

    /**
     * Level of each known constraint that {@link #enforcementRules()} omits, as ADR-089 §3 assigns it:
     * omitting a rule never weakens it. A constraint outside this map is {@link EnforcementLevel#AUDIT}.
     */
    public static final Map<String, EnforcementLevel> DEFAULT_ENFORCEMENT = Map.of(
            CAPABILITY_CONSTRAINT, EnforcementLevel.HARD,
            ENVIRONMENT_CONSTRAINT, EnforcementLevel.HARD,
            AUTHORIZED_INSTANCES_CONSTRAINT, EnforcementLevel.SOFT,
            WORKLOAD_ENVELOPE_CONSTRAINT, EnforcementLevel.AUDIT
    );

    /**
     * Shape of a capability identifier: lowercase kebab-case segments, optionally dot-separated, as in
     * {@code gateway.rate-limit} (ADR-088 §1, {@code @CapabilityModule.name()}).
     */
    public static final Pattern CAPABILITY_ID_PATTERN =
            Pattern.compile("[a-z0-9]++(?:-[a-z0-9]++)*+(?:\\.[a-z0-9]++(?:-[a-z0-9]++)*+)*+");

    /**
     * Compact constructor defending against null values and providing immutable sets/maps.
     */
    public ExecutionContract {
        Objects.requireNonNull(contractId, "contractId must not be null");
        Objects.requireNonNull(commercialModel, "commercialModel must not be null");
        Objects.requireNonNull(edition, "edition must not be null");
        Objects.requireNonNull(licenseMode, "licenseMode must not be null");
        Objects.requireNonNull(sku, "sku must not be null");
        if (authorizedInstances < 0) {
            throw new IllegalArgumentException("authorizedInstances must not be negative");
        }
        if (gracePeriodDays < 0) {
            throw new IllegalArgumentException("gracePeriodDays must not be negative");
        }
        entitledCapabilities = (entitledCapabilities == null) ? Set.of() : Set.copyOf(entitledCapabilities);
        authorizedEnvironments = (authorizedEnvironments == null) ? Set.of() : Set.copyOf(authorizedEnvironments);
        envelope = (envelope == null) ? WorkloadEnvelope.UNLIMITED : envelope;
        validFrom = (validFrom == null) ? Instant.EPOCH : validFrom;
        validUntil = (validUntil == null) ? Instant.MAX : validUntil;
        enforcementRules = (enforcementRules == null) ? Map.of() : Map.copyOf(enforcementRules);
    }

    /**
     * Checks whether a capability is entitled under this contract.
     *
     * @param capabilityId lowercase kebab-case capability identifier
     * @return {@code true} if entitled, {@code false} otherwise
     */
    public boolean allowsCapability(String capabilityId) {
        return capabilityId != null && entitledCapabilities.contains(capabilityId);
    }

    /**
     * Checks whether an execution environment is authorized under this contract.
     *
     * @param environment environment name (e.g. {@code "development"}, {@code "staging"}, {@code "production"})
     * @return {@code true} if authorized, {@code false} otherwise
     */
    public boolean isEnvironmentAuthorized(String environment) {
        return environment != null && authorizedEnvironments.contains(environment);
    }

    /**
     * Returns the enforcement level for a given constraint key: the level {@link #enforcementRules()}
     * maps it to, else its {@link #DEFAULT_ENFORCEMENT} level, else {@link EnforcementLevel#AUDIT}.
     *
     * @param constraintKey name of the constraint rule (e.g. {@code "capability"}, {@code "environment"})
     * @return the enforcement level that applies to the constraint
     */
    public EnforcementLevel getEnforcement(String constraintKey) {
        if (constraintKey == null) {
            return EnforcementLevel.AUDIT;
        }
        EnforcementLevel mapped = enforcementRules.get(constraintKey);
        return mapped != null ? mapped : DEFAULT_ENFORCEMENT.getOrDefault(constraintKey, EnforcementLevel.AUDIT);
    }

    /**
     * Reports whether {@code candidate} has the shape of a capability identifier.
     *
     * @param candidate candidate identifier
     * @return {@code true} when {@code candidate} matches {@link #CAPABILITY_ID_PATTERN}
     */
    public static boolean isCapabilityId(String candidate) {
        return candidate != null && CAPABILITY_ID_PATTERN.matcher(candidate).matches();
    }

    /**
     * Evaluates capability entitlement and enforces the configured policy.
     *
     * <p>At {@link EnforcementLevel#HARD} an unentitled capability throws; at {@code SOFT} or {@code AUDIT}
     * it is returned as a {@link ContractViolation} for the caller to report, and the caller keeps running.
     *
     * @param capabilityId the capability identifier to enforce
     * @return the violation when the capability is not entitled below {@code HARD}; empty when it is entitled
     * @throws ContractBreachException with EX-LIC-0004 if the capability is not entitled and the level is HARD
     */
    public Optional<ContractViolation> assertCapability(String capabilityId) {
        if (allowsCapability(capabilityId)) {
            return Optional.empty();
        }
        EnforcementLevel level = getEnforcement(CAPABILITY_CONSTRAINT);
        if (level == EnforcementLevel.HARD) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0004,
                    "Capability not entitled under active execution contract",
                    capabilityId
            );
        }
        return Optional.of(
                new ContractViolation(contractId, CAPABILITY_CONSTRAINT, level, String.valueOf(capabilityId)));
    }

    /**
     * Evaluates environment authorization and enforces the configured policy.
     *
     * <p>At {@link EnforcementLevel#HARD} an unauthorized environment throws; at {@code SOFT} or {@code AUDIT}
     * it is returned as a {@link ContractViolation} for the caller to report, and the caller keeps running.
     *
     * @param environment the execution environment to enforce
     * @return the violation when the environment is not authorized below {@code HARD}; empty when it is
     * @throws ContractBreachException with EX-LIC-0007 if the environment is unauthorized and the level is HARD
     */
    public Optional<ContractViolation> assertEnvironment(String environment) {
        if (isEnvironmentAuthorized(environment)) {
            return Optional.empty();
        }
        EnforcementLevel level = getEnforcement(ENVIRONMENT_CONSTRAINT);
        if (level == EnforcementLevel.HARD) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0007,
                    "Environment not authorized under active execution contract",
                    environment
            );
        }
        return Optional.of(
                new ContractViolation(contractId, ENVIRONMENT_CONSTRAINT, level, String.valueOf(environment)));
    }

    /**
     * Creates the contract a kernel runs under when no license manifest is present.
     *
     * <p>The Community edition is unrestricted, so the contract authorizes every
     * {@link ExecutionEnvironment} and entitles no commercial capability.
     *
     * @return default Community Edition execution contract
     */
    public static ExecutionContract communityFallback() {
        return new ExecutionContract(
                "COMMUNITY",
                "STANDARD",
                "community",
                "PERPETUAL_INTERNAL",
                "exeris-sku-community",
                Set.of(),
                Arrays.stream(ExecutionEnvironment.values())
                        .map(ExecutionEnvironment::id)
                        .collect(Collectors.toUnmodifiableSet()),
                Integer.MAX_VALUE,
                WorkloadEnvelope.UNLIMITED,
                Instant.EPOCH,
                Instant.MAX,
                14,
                Map.of(
                        "capability", EnforcementLevel.SOFT,
                        "environment", EnforcementLevel.HARD,
                        "authorizedInstances", EnforcementLevel.AUDIT,
                        "workloadEnvelope", EnforcementLevel.AUDIT
                )
        );
    }
}
