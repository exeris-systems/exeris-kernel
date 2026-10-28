/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslRuntime;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.tck.contract.abi.AbstractAbiSymbolResolutionTck;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Community binding of {@link AbstractAbiSymbolResolutionTck}: the manifest is every downcall row of
 * {@link CommunityForeignCall}, resolved from {@code libssl} as the provider resolves it. Upcalls name
 * no native symbol and are not part of the manifest.
 *
 * <p>Unpinned, a host without OpenSSL aborts the cases; pinned with
 * {@code -Dexeris.test.expectedOpenSslMajor}, a failed load fails them.
 */
@DisplayName("Community OpenSSL ABI symbol resolution")
class CommunityAbiSymbolResolutionTckTest extends AbstractAbiSymbolResolutionTck {

    private static final String PINNED_MAJOR_PROPERTY = "exeris.test.expectedOpenSslMajor";

    private CoreOpenSslRuntime runtime;

    @Override
    protected List<AbiSymbol> manifest() {
        return Arrays.stream(CommunityForeignCall.values())
                .filter(call -> !call.isUpcall())
                .map(call -> new AbiSymbol("libssl", call.symbol(),
                        call.resolution() == CommunityForeignCall.Resolution.REQUIRED
                                ? Resolution.REQUIRED : Resolution.OPTIONAL,
                        Platform.ANY, null))
                .toList();
    }

    @Override
    protected Optional<MemorySegment> resolve(AbiSymbol symbol) {
        return runtime().findSsl(symbol.name());
    }

    @Override
    protected boolean pinnedMatrixRun() {
        String pinned = System.getProperty(PINNED_MAJOR_PROPERTY);
        return pinned != null && !pinned.isBlank();
    }

    private CoreOpenSslRuntime runtime() {
        if (runtime == null) {
            try {
                runtime = CoreOpenSslLoader.load(Arena.global());
            } catch (CryptoBootstrapException notLoadable) {
                if (pinnedMatrixRun()) {
                    throw notLoadable;
                }
                assumeTrue(false, "OpenSSL is not loadable on this host: " + notLoadable.getMessage());
            }
        }
        return runtime;
    }
}
