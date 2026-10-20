/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.openssl;

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
 * Core binding of {@link AbstractAbiSymbolResolutionTck}: the manifest is every {@link OpenSslDowncall}
 * row, resolved through the same library scope the loader binds it from.
 *
 * <p>Unpinned, a host without OpenSSL aborts the cases; pinned with
 * {@code -Dexeris.test.expectedOpenSslMajor}, a failed load fails them.
 */
@DisplayName("Core OpenSSL ABI symbol resolution")
class CoreOpenSslAbiSymbolResolutionTckTest extends AbstractAbiSymbolResolutionTck {

    private static final String PINNED_MAJOR_PROPERTY = "exeris.test.expectedOpenSslMajor";

    private CoreOpenSslRuntime runtime;

    @Override
    protected List<AbiSymbol> manifest() {
        return Arrays.stream(OpenSslDowncall.values())
                .map(d -> new AbiSymbol(libraryLabel(d.library()), d.symbol(),
                        d.resolution() == OpenSslDowncall.Resolution.REQUIRED
                                ? Resolution.REQUIRED : Resolution.OPTIONAL,
                        Platform.ANY, null))
                .toList();
    }

    @Override
    protected Optional<MemorySegment> resolve(AbiSymbol symbol) {
        CoreOpenSslRuntime loaded = runtime();
        return switch (symbol.library()) {
            case "libssl" -> loaded.findSsl(symbol.name());
            case "libcrypto" -> loaded.findCrypto(symbol.name());
            default -> loaded.find(symbol.name());
        };
    }

    @Override
    protected boolean pinnedMatrixRun() {
        String pinned = System.getProperty(PINNED_MAJOR_PROPERTY);
        return pinned != null && !pinned.isBlank();
    }

    private CoreOpenSslRuntime runtime() {
        if (runtime == null) {
            try {
                // CHECKSTYLE:OFF DirectArena - bootstrap loads symbols for the JVM lifetime
                runtime = CoreOpenSslLoader.load(Arena.global());
                // CHECKSTYLE:ON
            } catch (CryptoBootstrapException notLoadable) {
                if (pinnedMatrixRun()) {
                    throw notLoadable;
                }
                assumeTrue(false, "OpenSSL is not loadable on this host: " + notLoadable.getMessage());
            }
        }
        return runtime;
    }

    private static String libraryLabel(OpenSslDowncall.Library library) {
        return switch (library) {
            case SSL -> "libssl";
            case CRYPTO -> "libcrypto";
            case EITHER -> "libssl+libcrypto";
        };
    }
}
