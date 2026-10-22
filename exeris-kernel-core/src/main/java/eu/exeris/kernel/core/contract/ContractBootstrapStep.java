/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract;

import eu.exeris.kernel.core.contract.crypto.IssuerKeyResolver;
import eu.exeris.kernel.core.contract.crypto.JsonCanonicalizer;
import eu.exeris.kernel.core.contract.crypto.LicenseManifestVerifier;
import eu.exeris.kernel.core.contract.crypto.TrustedIssuerKey;
import eu.exeris.kernel.core.contract.jfr.ContractResolvedEvent;
import eu.exeris.kernel.core.contract.jfr.ContractViolationReporter;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.contract.EntitlementClass;
import eu.exeris.kernel.spi.contract.EntitlementRequirement;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.contract.ExecutionEnvironment;
import eu.exeris.kernel.spi.contract.WorkloadAdmission;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;
import eu.exeris.kernel.spi.time.TimeKernelProviders;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Contract gate of the kernel bootstrap (ADR-088 §5, ADR-089 obligation 2): a step of
 * {@code KernelBootstrap.boot()} that runs once, after configuration and before any subsystem is
 * initialized, so a refused boot has allocated no off-heap memory and bound no socket.
 *
 * <p>It decides which {@link ExecutionContract} the kernel runs under, and which {@link WorkloadAdmission}
 * its drivers consult, in every environment alike:
 * <ul>
 *   <li>No requirement and no manifest: the Community fallback and {@link WorkloadAdmission#UNGATED}. The
 *       Community edition is unrestricted, so a kernel carrying no entitlement-requiring code boots in every
 *       environment without a manifest.</li>
 *   <li>At least one {@link EntitlementRequirement} and no manifest: the boot stops with
 *       {@code EX-LIC-0005}, development included.</li>
 *   <li>A manifest, whether or not a requirement is declared: it is verified against the embedded trust roots
 *       inside the scope of the key that signed it, and the declared environment must lie in that scope
 *       ({@code EX-LIC-0009}, {@code HARD} whatever the enforcement rules say).</li>
 *   <li>A manifest and at least one requirement: the key must also be allowed to entitle the class of every
 *       declared requirement, whether or not the manifest lists it ({@code EX-LIC-0012}); then the contract
 *       must authorize the declared environment and every required capability at the levels its enforcement
 *       rules set. A {@code HARD} breach stops the boot; a {@code SOFT} or {@code AUDIT} one is reported
 *       through {@link ContractViolationReporter} and the boot continues.</li>
 *   <li>A manifest signed by a developer key and at least one requirement: the decision carries a
 *       {@link DeveloperWorkloadAdmission} holding the manifest's envelope at {@code HARD}. Any other
 *       combination carries {@link WorkloadAdmission#UNGATED}.</li>
 * </ul>
 *
 * <p>The environment comes only from the configuration key {@value ExecutionEnvironment#CONFIG_KEY};
 * the kernel profile, which governs how much an error discloses, plays no part in it.
 *
 * <p>Every {@link EntitlementRequirement} on the classpath is loaded and checked in every environment; one
 * that cannot be loaded, throws, or returns {@code null}, an empty set, a malformed capability
 * identifier or a {@code null} class, or declares one identifier under two classes, stops the boot with
 * {@code EX-LIC-0008}.
 *
 * <p>The manifest is read from the file the configuration key {@value #MANIFEST_PATH_KEY} names. When
 * that key is bound, the file must be readable — a blank, missing or unreadable configured path stops the
 * boot rather than letting another manifest, or none, take its place. When the key is unbound, the kernel
 * reads {@code license-manifest.json} from the working directory, then from the classpath. A manifest
 * larger than 64 KiB is refused.
 *
 * <p>Validity is judged at the instant the kernel {@link TimeKernelProviders#timeSource() time source}
 * reports, which is the platform clock until a time provider is bound.
 *
 * <p>Each decision is recorded as one {@link ContractResolvedEvent}: environment, manifest source, bound
 * contract or refusing error code, the signing key and whether it is a developer key, and duration.
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
     * @return the contract the kernel runs under and the workload gate its drivers consult;
     *         the gate is {@link WorkloadAdmission#UNGATED} until a contract activates a developer envelope
     * @throws ContractBreachException with {@code EX-LIC-0001} (a manifest that cannot be read or fails the
     *                                 schema or its signature), {@code EX-LIC-0002} (unknown issuer key),
     *                                 {@code EX-LIC-0003} / {@code EX-LIC-0006} (outside its validity window),
     *                                 {@code EX-LIC-0004} / {@code EX-LIC-0007} (a {@code HARD} capability or
     *                                 environment breach), {@code EX-LIC-0005} (a declared requirement and no
     *                                 manifest), {@code EX-LIC-0008} (an {@code EntitlementRequirement} that
     *                                 breaks its contract), {@code EX-LIC-0009} to {@code EX-LIC-0011} and
     *                                 {@code EX-LIC-0013} (outside the scope of the issuer key) or
     *                                 {@code EX-LIC-0012} (a requirement of a class the key may not entitle)
     * @throws ConfigProvider.ConfigProviderException with {@code EX-CFG-1002} if the declared
     *                                 environment names none of the six environment classes
     */
    public static ContractDecision run(ConfigProvider config, ClassLoader classLoader) {
        return run(config, classLoader, IssuerKeyResolver.embedded());
    }

    /**
     * Runs the gate against an injected issuer key resolution, so a test can sign the manifest it reads.
     *
     * @param config      active configuration provider; {@code null} declares no environment
     * @param classLoader loader that discovers requirements and the classpath manifest
     * @param keyResolver issuer key resolution; {@link #run(ConfigProvider, ClassLoader)} passes the embedded one
     * @return the contract the kernel runs under and the workload gate its drivers consult
     */
    /* default */ static ContractDecision run(ConfigProvider config, ClassLoader classLoader,
                                              IssuerKeyResolver keyResolver) {
        long start = System.nanoTime();
        String environmentId = "";
        String source = NO_SOURCE;
        ResolvedKey resolved = new ResolvedKey();
        try {
            ExecutionEnvironment environment = declaredEnvironment(config);
            environmentId = environment.id();
            RequirementDiscovery.DeclaredRequirements required = RequirementDiscovery.declared(classLoader);
            ManifestLocator.ManifestSource manifest = ManifestLocator.resolve(
                    config == null ? Optional.empty() : config.getString(MANIFEST_PATH_KEY),
                    Path.of(ManifestLocator.DEFAULT_MANIFEST_FILENAME),
                    classLoader);
            source = manifest == null ? NO_SOURCE : manifest.source();
            ContractDecision decision = decide(
                    environment, required, manifest == null ? null : manifest.bytes(),
                    TimeKernelProviders.timeSource().wallTime(), keyResolver, resolved);
            ContractResolvedEvent.emit(new ContractResolvedEvent.Resolution(
                    environmentId, source, decision.contract().contractId(), decision.contract().edition(),
                    resolved.keyId, decision.admission().enforced(), "bound"), System.nanoTime() - start);
            return decision;
        } catch (ExerisKernelException refused) {
            ContractResolvedEvent.emit(new ContractResolvedEvent.Resolution(
                    environmentId, source, "", "", resolved.keyId, false, refused.errorCode()),
                    System.nanoTime() - start);
            throw refused;
        }
    }

    /**
     * Decides the execution contract and the workload gate from already-resolved inputs, against an issuer key
     * resolution of the caller's choosing.
     *
     * <p>This is the gate's own decision, exposed so that a conformance suite and a tool that signs licences can
     * run it under a key they hold: the policy is the one {@link #run(ConfigProvider, ClassLoader)} applies,
     * in every environment alike. Its result is never bound into a kernel scope, and the bootstrap path never
     * accepts a resolver.
     *
     * <p>The order of the checks decides which code wins. The manifest is verified first, by
     * {@link LicenseManifestVerifier#verifyScoped}: signature, schema, the key's scope, then the validity
     * window. Only then do the checks of this gate run: the class of every requirement ({@code EX-LIC-0012}),
     * the declared environment against the key's scope ({@code EX-LIC-0009}), and the contract's own
     * environment and capability checks. A manifest that is both expired and unable to entitle a declared
     * class therefore reports its time code first.
     *
     * @param environment  declared environment
     * @param requirements capability identifiers each entitlement class's requirements declare; empty when no
     *                     requirement is on the classpath
     * @param manifest     manifest content, or {@code null} when no manifest was found
     * @param now          evaluation instant for temporal validity
     * @param keyResolver  issuer key resolution
     * @return the contract the kernel would run under and the workload gate its drivers would consult
     * @throws ContractBreachException as {@link #run(ConfigProvider, ClassLoader)} lists
     * @since 0.13
     */
    public static ContractDecision decide(
            ExecutionEnvironment environment,
            Map<EntitlementClass, Set<String>> requirements,
            byte[] manifest,
            Instant now,
            IssuerKeyResolver keyResolver) {
        SortedMap<String, EntitlementClass> byId = new TreeMap<>();
        SortedMap<String, String> providerById = new TreeMap<>();
        requirements.forEach((entitlementClass, ids) -> ids.forEach(id -> {
            byId.put(id, entitlementClass);
            providerById.put(id, "declared:" + entitlementClass.id());
        }));
        return decide(environment, new RequirementDiscovery.DeclaredRequirements(byId, providerById),
                manifest, now, keyResolver);
    }

    /* default */ static ContractDecision decide(
            ExecutionEnvironment environment,
            RequirementDiscovery.DeclaredRequirements requirements,
            byte[] manifestBytes,
            Instant now,
            IssuerKeyResolver keyResolver) {
        return decide(environment, requirements, manifestBytes, now, keyResolver, new ResolvedKey());
    }

    private static ContractDecision decide(
            ExecutionEnvironment environment,
            RequirementDiscovery.DeclaredRequirements requirements,
            byte[] manifestBytes,
            Instant now,
            IssuerKeyResolver keyResolver,
            ResolvedKey resolved) {
        if (manifestBytes == null) {
            if (requirements.isEmpty()) {
                return new ContractDecision(ExecutionContract.communityFallback(), WorkloadAdmission.UNGATED);
            }
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0005,
                    "No license manifest found, and code on the classpath requires an entitlement for "
                            + requirements.byId().keySet(),
                    environment.id(),
                    List.copyOf(requirements.byId().keySet()));
        }
        // The key is recorded when it is resolved, so a refusal raised inside the verifier still names it.
        IssuerKeyResolver recording = keyId -> {
            TrustedIssuerKey resolvedKey = keyResolver.resolve(keyId);
            resolved.keyId = resolvedKey.keyId();
            return resolvedKey;
        };
        LicenseManifestVerifier.VerifiedManifest verified = LicenseManifestVerifier.verifyScoped(
                JsonCanonicalizer.decodeUtf8(manifestBytes), now, recording);
        ExecutionContract contract = verified.contract();
        TrustedIssuerKey key = verified.key();
        if (!requirements.isEmpty()) {
            assertClassesEntitled(key, requirements);
        }
        assertDeclaredEnvironmentInScope(key, environment);
        if (requirements.isEmpty()) {
            return new ContractDecision(contract, WorkloadAdmission.UNGATED);
        }
        contract.assertEnvironment(environment.id()).ifPresent(ContractViolationReporter::report);
        for (String capability : requirements.byId().keySet()) {
            contract.assertCapability(capability).ifPresent(ContractViolationReporter::report);
        }
        WorkloadAdmission admission = key.scope().isDeveloperScope()
                ? new DeveloperWorkloadAdmission(contract.contractId(), contract.envelope())
                : WorkloadAdmission.UNGATED;
        return new ContractDecision(contract, admission);
    }

    /** The key must be allowed to entitle the class of every requirement, whether or not the manifest lists it. */
    private static void assertClassesEntitled(
            TrustedIssuerKey key, RequirementDiscovery.DeclaredRequirements requirements) {
        for (Map.Entry<String, EntitlementClass> declared : requirements.byId().entrySet()) {
            if (!key.scope().classes().contains(declared.getValue())) {
                throw new ContractBreachException(
                        KernelErrorCodes.EX_LIC_0012,
                        "Issuer key " + key.keyId() + " may not entitle the " + declared.getValue().id()
                                + " requirement declared for " + declared.getKey(),
                        key.keyId(), declared.getValue().id(), requirements.providerById().get(declared.getKey()));
            }
        }
    }

    /** The declared environment is outside what the key may entitle whatever the manifest and its rules say. */
    private static void assertDeclaredEnvironmentInScope(TrustedIssuerKey key, ExecutionEnvironment environment) {
        if (!key.scope().environments().contains(environment)) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0009,
                    "Declared environment '" + environment.id() + "' is outside the scope of issuer key "
                            + key.keyId(),
                    key.keyId(), List.of(environment.id()));
        }
    }

    /** The key a manifest was verified with, for the decision record; confined to the booting thread. */
    private static final class ResolvedKey {
        private String keyId = "";
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
