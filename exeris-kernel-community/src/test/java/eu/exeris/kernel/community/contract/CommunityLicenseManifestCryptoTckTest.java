/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.contract;

import eu.exeris.kernel.core.contract.crypto.JsonCanonicalizer;
import eu.exeris.kernel.core.contract.crypto.LicenseManifestVerifier;
import eu.exeris.kernel.core.contract.crypto.IssuerKeyResolver;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.tck.contract.AbstractLicenseManifestCryptoTck;
import org.junit.jupiter.api.DisplayName;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;

@DisplayName("Community: License Manifest Crypto TCK")
class CommunityLicenseManifestCryptoTckTest extends AbstractLicenseManifestCryptoTck {

    @Override
    protected ExecutionContract verifyManifest(
            String manifestJson, Instant evaluationTime, String issuerKeyId, PublicKey issuerKey) {
        return LicenseManifestVerifier.verify(
                manifestJson, evaluationTime, IssuerKeyResolver.of(Map.of(issuerKeyId, issuerKey)));
    }

    @Override
    protected byte[] canonicalizeJson(String json) {
        return JsonCanonicalizer.canonicalize(json);
    }

}
