/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security.credentials;

/**
 * SPI contract for password hashing: turns a plaintext credential into a self-describing PHC string
 * and verifies a candidate against one, without either side of the pair leaving the credential in
 * memory.
 *
 * <p><b>Allocation:</b> allocates ({@link #encode} returns a new PHC {@code String}, and an
 * implementation copies {@code raw} internally; a memory-hard KDF additionally reserves the working
 * memory its {@link PasswordEncoderConfig} declares) — deliberately expensive, never a hot path
 * <p><b>Ownership:</b> the caller owns {@code raw} and zeroes it once the call returns; the
 * implementation owns and zeroes every internal copy it derives from it
 *
 * @implSpec An implementation MUST zero any internal {@code byte[]} or {@code char[]} copy it
 *           creates from {@code raw}, on the failure path as well as the success path.
 * @apiNote  Zero {@code raw} yourself after the call, and do not hand the same array to both
 *           {@code encode} and subsequent business logic — the encoder makes no promise about the
 *           array you still hold.
 *           <p>This interface has <em>no ServiceLoader lifecycle</em>; it is not a kernel bootstrap
 *           subsystem. Instantiate an implementation directly, for example
 *           {@code new Argon2idPasswordEncoder(PasswordEncoderConfig.defaults())}.
 * @since 0.5
 */
public interface KernelPasswordEncoder {

	/**
	 * Hashes {@code raw} with a fresh random salt and returns a PHC string that carries the salt and
	 * every tuning parameter, so {@link #matches} can verify it without external state.
	 *
	 * @param raw plaintext credential; caller must zero after this call
	 * @return PHC string (e.g. {@code $argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>})
	 * @throws IllegalArgumentException if {@code raw} is {@code null} or empty
	 */
	@SuppressWarnings("PMD.UseVarargs")
	String encode(char[] raw);

	/**
	 * Verifies {@code raw} against a PHC string produced by {@link #encode}, re-deriving the hash
	 * with the parameters the string itself carries rather than the encoder's current
	 * configuration.
	 *
	 * @param raw     plaintext credential; caller must zero after this call
	 * @param encoded PHC-format string produced by {@link #encode}
	 * @return {@code true} iff the credential matches
	 * @throws IllegalArgumentException if either argument is {@code null} or empty
	 * @implSpec The final comparison MUST be constant-time: an implementation that short-circuits
	 *           on the first differing byte leaks the hash prefix to a timing attack.
	 */
	boolean matches(char[] raw, String encoded);
}
