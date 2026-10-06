/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract;

import eu.exeris.kernel.core.contract.crypto.IssuerKeyResolver;
import eu.exeris.kernel.core.contract.crypto.JsonCanonicalizer;
import eu.exeris.kernel.core.contract.crypto.LicenseManifestVerifier;
import eu.exeris.kernel.core.contract.jfr.ContractResolvedEvent;
import eu.exeris.kernel.core.contract.jfr.ContractViolationReporter;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.contract.ContractViolation;
import eu.exeris.kernel.spi.contract.EnforcementLevel;
import eu.exeris.kernel.spi.contract.EntitlementRequirement;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.contract.ExecutionEnvironment;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Contract gate of the kernel bootstrap (ADR-088 §5, ADR-089 obligation 2): a step of
 * {@code KernelBootstrap.boot()} that runs once, after configuration and before any subsystem is
 * initialized, so a refused boot has allocated no off-heap memory and bound no socket.
 *
 * <p>It decides which {@link ExecutionContract} the kernel runs under:
 * <ul>
 *   <li>No manifest, and either the declared {@link ExecutionEnvironment} needs no entitlement or no
 *       {@link EntitlementRequirement} is on the classpath: the Community fallback. The Community
 *       edition is unrestricted, so a kernel carrying no entitlement-requiring code boots in every
 *       environment without a manifest.</li>
 *   <li>No manifest, a production-class environment, and at least one requirement: the boot stops
 *       with {@code EX-LIC-0005}. In any other environment the same situation is reported as a
 *       {@code SOFT} warning, because entitlement-requiring code then runs ungated.</li>
 *   <li>A manifest: it is verified against the embedded trust roots, whatever the environment. In a
 *       production-class environment the contract must then authorize that environment and every
 *       required capability, at the levels its enforcement rules set: a {@code HARD} breach stops the
 *       boot, a {@code SOFT} or {@code AUDIT} one is reported through {@link ContractViolationReporter}
 *       and the boot continues.</li>
 * </ul>
 *
 * <p>The environment comes only from the configuration key {@value ExecutionEnvironment#CONFIG_KEY};
 * the kernel profile, which governs how much an error discloses, plays no part in it.
 *
 * <p>Every {@link EntitlementRequirement} on the classpath is loaded and checked in every environment; one
 * that cannot be loaded, throws, or returns {@code null}, an empty set or a malformed capability
 * identifier stops the boot with {@code EX-LIC-0008}.
 *
 * <p>The manifest is read from the file the configuration key {@value #MANIFEST_PATH_KEY} names. When
 * that key is bound, the file must be readable — a blank, missing or unreadable configured path stops the
 * boot rather than letting another manifest, or none, take its place. When the key is unbound, the kernel
 * reads {@code license-manifest.json} from the working directory, then from the classpath. A manifest
 * larger than 64 KiB is refused.
 *
 * <p>Each decision is recorded as one {@link ContractResolvedEvent}: environment, manifest source, bound
 * contract or refusing error code, and duration.
 *
 * @since 0.13
 */
public final class ContractBootstrapStep {

    /** Configuration key naming the manifest file; when bound, no other location is consulted. */
    public static final String MANIFEST_PATH_KEY = "license.manifest.path";

    private static final String NO_SOURCE = "none";

    private ContractBootstrapStep() {}

    /**
     * Resolves the declared environment, the classpath's entitlement requirements and the license
     * manifest, decides the execution contract, and records the decision as a {@link ContractResolvedEvent}.
     *
     * @param config      active configuration provider; {@code null} declares no environment
     * @param classLoader loader that discovers {@link EntitlementRequirement} implementations and the
     *                    classpath manifest
     * @return the contract the kernel runs under
     * @throws ContractBreachException if a requirement breaks its contract, a manifest fails verification,
     *                                 or the environment requires an entitlement no manifest grants
     * @throws ConfigProvider.ConfigProviderException with {@code EX-CFG-1002} if the declared
     *                                 environment names none of the six environment classes
     */
    public static ExecutionContract run(ConfigProvider config, ClassLoader classLoader) {
        long start = System.nanoTime();
        String environmentId = "";
        String source = NO_SOURCE;
        try {
            ExecutionEnvironment environment = declaredEnvironment(config);
            environmentId = environment.id();
            Set<String> required = RequirementDiscovery.requiredCapabilities(classLoader);
            ManifestLocator.ManifestSource manifest = ManifestLocator.resolve(
                    config == null ? Optional.empty() : config.getString(MANIFEST_PATH_KEY),
                    Path.of(ManifestLocator.DEFAULT_MANIFEST_FILENAME),
                    classLoader);
            source = manifest == null ? NO_SOURCE : manifest.source();
            ExecutionContract contract = decide(
                    environment, required, manifest == null ? null : manifest.bytes(),
                    Instant.now(), IssuerKeyResolver.embedded());
            ContractResolvedEvent.emit(environmentId, source, contract.contractId(), contract.edition(),
                    "bound", System.nanoTime() - start);
            return contract;
        } catch (ExerisKernelException refused) {
            ContractResolvedEvent.emit(environmentId, source, "", "", refused.errorCode(),
                    System.nanoTime() - start);
            throw refused;
        }
    }

    /**
     * Decides the execution contract from already-resolved inputs.
     *
     * @param environment          declared environment
     * @param requiredCapabilities capabilities the classpath's requirements declare
     * @param manifestBytes        manifest content, or {@code null} when no manifest was found
     * @param now                  evaluation instant for temporal validity
     * @param keyResolver          issuer key resolution; {@link #run} always passes the embedded one
     * @return the contract the kernel runs under
     */
    /* default */ static ExecutionContract decide(
            ExecutionEnvironment environment,
            Set<String> requiredCapabilities,
            byte[] manifestBytes,
            Instant now,
            IssuerKeyResolver keyResolver) {
        List<String> required = List.copyOf(new TreeSet<>(requiredCapabilities));
        if (manifestBytes == null) {
            return withoutManifest(environment, required);
        }
        ExecutionContract contract = LicenseManifestVerifier.verify(
                JsonCanonicalizer.decodeUtf8(manifestBytes), now, keyResolver);
        if (environment.requiresEntitlement()) {
            contract.assertEnvironment(environment.id()).ifPresent(ContractViolationReporter::report);
            for (String capability : required) {
                contract.assertCapability(capability).ifPresent(ContractViolationReporter::report);
            }
        }
        return contract;
    }

    private static ExecutionContract withoutManifest(ExecutionEnvironment environment, List<String> required) {
        ExecutionContract fallback = ExecutionContract.communityFallback();
        if (required.isEmpty()) {
            return fallback;
        }
        if (environment.requiresEntitlement()) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0005,
                    "No license manifest found, and environment '" + environment.id()
                            + "' requires an entitlement for " + required,
                    environment.id(),
                    required);
        }
        ContractViolationReporter.report(new ContractViolation(
                fallback.contractId(),
                ExecutionContract.ENVIRONMENT_CONSTRAINT,
                EnforcementLevel.SOFT,
                "entitlement-requiring capabilities " + required + " run without a license manifest"
                        + " because environment '" + environment.id() + "' is not a production class;"
                        + " declare the environment if this is production"));
        return fallback;
    }

    /* default */ static ExecutionEnvironment declaredEnvironment(ConfigProvider config) {
        if (config == null) {
            return ExecutionEnvironment.DEVELOPMENT;
        }
        Optional<String> declared = config.getString(ExecutionEnvironment.CONFIG_KEY)
                .filter(value -> !value.isBlank());
        if (declared.isEmpty()) {
            return ExecutionEnvironment.DEVELOPMENT;
        }
        return ExecutionEnvironment.fromId(declared.get())
                .orElseThrow(() -> ConfigProvider.ConfigProviderException.typeMismatch(
                        ExecutionEnvironment.CONFIG_KEY,
                        Arrays.stream(ExecutionEnvironment.values())
                                .map(ExecutionEnvironment::id)
                                .collect(Collectors.joining(" | ")),
                        declared.get()));
    }

}
