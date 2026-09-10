/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.security.credentials;

/**
 * Immutable Argon2id tuning parameters, each floored so a mis-typed number is rejected at
 * construction rather than silently weakening every credential the encoder produces.
 *
 * <p>OWASP-minimum (2024): {@code memoryCostKib=65_536, iterations=3, parallelism=1, hashLength=32}.
 *
 * @param memoryCostKib Argon2id memory cost in kibibytes — the parameter that makes parallel GPU
 *                      and ASIC cracking expensive; must be at least {@code 8192}
 * @param iterations    number of Argon2id passes over that memory; must be at least {@code 1}
 * @param parallelism   number of parallel lanes the derivation may use; must be at least {@code 1}
 * @param hashLength    length in bytes of the derived hash written into the PHC string; must be at
 *                      least {@code 16}
 * @since 0.5
 */
public record PasswordEncoderConfig(
		int memoryCostKib,
		int iterations,
		int parallelism,
		int hashLength) {

	private static final int MIN_MEMORY_COST_KIB = 8_192;
	private static final int MIN_ITERATIONS = 1;
	private static final int MIN_PARALLELISM = 1;
	private static final int MIN_HASH_LENGTH = 16;

	/**
	 * Compact constructor — rejects any parameter below its OWASP floor instead of clamping it, so
	 * a deployment cannot end up hashing with weaker parameters than it asked for.
	 *
	 * @throws IllegalArgumentException if {@code memoryCostKib < 8192}, {@code iterations < 1},
	 *                                  {@code parallelism < 1}, or {@code hashLength < 16}
	 */
	public PasswordEncoderConfig {
		if (memoryCostKib < MIN_MEMORY_COST_KIB) {
			throw new IllegalArgumentException("memoryCostKib must be >= 8192");
		}
		if (iterations < MIN_ITERATIONS) {
			throw new IllegalArgumentException("iterations must be >= 1");
		}
		if (parallelism < MIN_PARALLELISM) {
			throw new IllegalArgumentException("parallelism must be >= 1");
		}
		if (hashLength < MIN_HASH_LENGTH) {
			throw new IllegalArgumentException("hashLength must be >= 16");
		}
	}

	/**
	 * The OWASP-recommended Argon2id parameters (2024), and the configuration a deployment gets
	 * when it declares none.
	 *
	 * @return a configuration of {@code memoryCostKib=65536, iterations=3, parallelism=1,
	 *         hashLength=32}; never {@code null}
	 */
	public static PasswordEncoderConfig defaults() {
		return new PasswordEncoderConfig(65_536, 3, 1, 32);
	}
}
