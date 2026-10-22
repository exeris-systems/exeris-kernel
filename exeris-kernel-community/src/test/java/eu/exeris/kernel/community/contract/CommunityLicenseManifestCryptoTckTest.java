/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.contract;

import eu.exeris.kernel.core.contract.ContractBootstrapStep;
import eu.exeris.kernel.core.contract.crypto.IssuerKeyResolver;
import eu.exeris.kernel.core.contract.crypto.IssuerKeyScope;
import eu.exeris.kernel.core.contract.crypto.JsonCanonicalizer;
import eu.exeris.kernel.core.contract.crypto.LicenseManifestVerifier;
import eu.exeris.kernel.core.contract.crypto.TrustedIssuerKey;
import eu.exeris.kernel.spi.contract.EntitlementClass;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.contract.ExecutionEnvironment;
import eu.exeris.kernel.tck.contract.AbstractLicenseManifestCryptoTck;
import org.junit.jupiter.api.DisplayName;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@DisplayName("Community: License Manifest Crypto TCK")
class CommunityLicenseManifestCryptoTckTest extends AbstractLicenseManifestCryptoTck {

    @Override
    protected ExecutionContract verifyManifest(
            String manifestJson, Instant evaluationTime, String issuerKeyId, PublicKey issuerKey,
            TckKeyScope scope) {
        return LicenseManifestVerifier.verify(
                manifestJson, evaluationTime, resolverFor(issuerKeyId, issuerKey, scope));
    }

    @Override
    protected ExecutionContract decide(
            String manifestJson, Instant evaluationTime, String issuerKeyId, PublicKey issuerKey,
            TckKeyScope scope, String environment, Map<EntitlementClass, Set<String>> requirements) {
        return ContractBootstrapStep.decide(
                ExecutionEnvironment.fromId(environment).orElseThrow(), requirements,
                manifestJson == null ? null : manifestJson.getBytes(StandardCharsets.UTF_8), evaluationTime,
                resolverFor(issuerKeyId, issuerKey, scope)).contract();
    }

    @Override
    protected byte[] canonicalizeJson(String json) {
        return JsonCanonicalizer.canonicalize(json);
    }

    private static IssuerKeyResolver resolverFor(String issuerKeyId, PublicKey issuerKey, TckKeyScope scope) {
        IssuerKeyScope mapped = new IssuerKeyScope(
                scope.environments().stream()
                        .map(id -> ExecutionEnvironment.fromId(id).orElseThrow())
                        .collect(Collectors.toSet()),
                scope.maxValidity(), scope.ceiling(), scope.classes());
        return IssuerKeyResolver.ofScoped(Map.of(issuerKeyId, new TrustedIssuerKey(issuerKeyId, issuerKey, mapped)));
    }

}
