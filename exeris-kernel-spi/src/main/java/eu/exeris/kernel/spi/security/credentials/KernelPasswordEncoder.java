/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security.credentials;

/**
 * SPI contract for password hashing.
 *
 * <h2>Ownership and Zeroing</h2>
 * <p>The caller owns {@code raw} and MUST zero it after this call returns.
 * The implementation MUST zero any internal {@code byte[]} copies it creates from {@code raw}.
 * Do NOT pass the same array to both {@code encode} and subsequent business logic.
 *
 * <h2>Lifecycle</h2>
 * <p>This interface has <em>no ServiceLoader lifecycle</em>. It is not a kernel bootstrap subsystem.
 * Instantiate the implementation directly:
 * {@code new Argon2idPasswordEncoder(PasswordEncoderConfig.defaults())}.
 *
 * @since 0.5.0
 */
public interface KernelPasswordEncoder {

	/**
	 * Hashes {@code raw} and returns a PHC-format encoded string.
	 *
	 * @param raw plaintext credential; caller must zero after this call
	 * @return PHC string (e.g. {@code $argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>})
	 * @throws IllegalArgumentException if {@code raw} is {@code null} or empty
	 */
	@SuppressWarnings("PMD.UseVarargs")
	String encode(char[] raw);

	/**
	 * Verifies {@code raw} against a previously encoded PHC string.
	 * Comparison MUST be constant-time to prevent timing attacks.
	 *
	 * @param raw     plaintext credential; caller must zero after this call
	 * @param encoded PHC-format string produced by {@link #encode}
	 * @return {@code true} iff the credential matches
	 * @throws IllegalArgumentException if either argument is {@code null} or empty
	 */
	boolean matches(char[] raw, String encoded);
}
