/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.security.credentials;

/**
 * Immutable Argon2id tuning parameters.
 *
 * <p>OWASP-minimum (2024): {@code memoryCostKib=65_536, iterations=3, parallelism=1, hashLength=32}.
 *
 * @since 0.5.0
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

	/** OWASP-recommended defaults for Argon2id (2024). */
	public static PasswordEncoderConfig defaults() {
		return new PasswordEncoderConfig(65_536, 3, 1, 32);
	}
}
