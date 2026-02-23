/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.exceptions.crypto;

import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;

/**
 * Thrown by {@link KernelCryptoProvider#createTlsEngine} when the crypto engine
 * cannot be initialised (e.g., missing native OpenSSL library, invalid certificate,
 * insufficient off-heap budget).
 *
 * <h2>rawArgs Binary Layout</h2>
 * <pre>
 * index 0 → String providerName  (which provider failed to initialise)
 * index 1 → String reason        (failure cause — static constant, never formatted)
 * </pre>
 *
 * @since 0.5.0
 */
public final class CryptoBootstrapException extends TlsException {

    public CryptoBootstrapException(String providerName, String reason) {
        super(reason);
    }

    public CryptoBootstrapException(String providerName, String reason, Throwable cause) {
        super(reason, cause);
    }
}

