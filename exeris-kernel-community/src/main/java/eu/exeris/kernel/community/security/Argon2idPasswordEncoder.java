/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.spi.security.credentials.KernelPasswordEncoder;
import eu.exeris.kernel.spi.security.credentials.PasswordEncoderConfig;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Community {@link KernelPasswordEncoder} using Argon2id via Bouncy Castle.
 *
 * <p>Produces PHC-format output: {@code $argon2id$v=19$m=...,t=...,p=...$<salt>$<hash>}.
 * Salt is 16 bytes from {@link SecureRandom}. The raw password bytes and the freshly computed
 * hash are zeroed before {@link #encode(char[])} or {@link #matches(char[], String)} returns;
 * the salt and a hash decoded from an already-encoded PHC string are not.
 *
 * @since 0.5
 */
public final class Argon2idPasswordEncoder implements KernelPasswordEncoder {

	private static final int SALT_LENGTH = 16;
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final PasswordEncoderConfig config;

	/**
	 * Configures Argon2id hashing and verification with the given tuning parameters.
	 *
	 * @param config the memory cost, iteration count, parallelism and hash length applied to
	 *               every {@link #encode(char[])} call
	 * @throws IllegalArgumentException if {@code config} is {@code null}
	 */
	public Argon2idPasswordEncoder(PasswordEncoderConfig config) {
		if (config == null) {
			throw new IllegalArgumentException("config must not be null");
		}
		this.config = config;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @implNote Generates a fresh 16-byte {@link SecureRandom} salt for every call and hashes
	 *           with Bouncy Castle's Argon2id generator using this encoder's configured cost
	 *           parameters, encoding the result in the PHC form described in the type comment.
	 *           The raw password bytes and the computed hash are zeroed before returning.
	 */
	@Override
	public String encode(char[] raw) {
		if (raw == null || raw.length == 0) {
			throw new IllegalArgumentException("raw must not be null or empty");
		}
		byte[] salt = new byte[SALT_LENGTH];
		SECURE_RANDOM.nextBytes(salt);
		byte[] rawBytes = toBytes(raw);
		try {
			byte[] hash = argon2id(rawBytes, salt, config.memoryCostKib(), config.iterations(),
					config.parallelism(), config.hashLength());
			try {
				return format(salt, hash);
			} finally {
				Arrays.fill(hash, (byte) 0);
			}
		} finally {
			Arrays.fill(rawBytes, (byte) 0);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws IllegalArgumentException if {@code raw} or {@code encoded} is {@code null} or
	 *         empty, or if {@code encoded} is not a well-formed {@code $argon2id$v=19$...} PHC
	 *         string
	 * @implNote Parses {@code encoded}, re-derives a candidate hash with the parsed salt and
	 *           cost parameters using Bouncy Castle's Argon2id generator, and compares it to the
	 *           stored hash in constant time. The raw password bytes and the candidate hash are
	 *           zeroed before returning.
	 */
	@Override
	public boolean matches(char[] raw, String encoded) {
		if (raw == null || raw.length == 0) {
			throw new IllegalArgumentException("raw must not be null or empty");
		}
		if (encoded == null || encoded.isEmpty()) {
			throw new IllegalArgumentException("encoded must not be null or empty");
		}
		byte[] rawBytes = toBytes(raw);
		try {
			PhcFields phc = PhcFields.parse(encoded);
			byte[] candidate = argon2id(rawBytes, phc.salt(),
					phc.memoryCostKib(), phc.iterations(), phc.parallelism(), phc.hash().length);
			try {
				return constantTimeEquals(candidate, phc.hash());
			} finally {
				Arrays.fill(candidate, (byte) 0);
			}
		} finally {
			Arrays.fill(rawBytes, (byte) 0);
		}
	}

	private static byte[] argon2id(byte[] password, byte[] salt,
			int memoryCostKib, int iterations, int parallelism, int hashLength) {
		Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
				.withSalt(salt)
				.withMemoryAsKB(memoryCostKib)
				.withIterations(iterations)
				.withParallelism(parallelism)
				.withVersion(Argon2Parameters.ARGON2_VERSION_13)
				.build();
		Argon2BytesGenerator generator = new Argon2BytesGenerator();
		generator.init(params);
		byte[] hash = new byte[hashLength];
		generator.generateBytes(password, hash);
		return hash;
	}

	private String format(byte[] salt, byte[] hash) {
		Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
		return "$argon2id$v=19$m=" + config.memoryCostKib()
				+ ",t=" + config.iterations()
				+ ",p=" + config.parallelism()
				+ "$" + encoder.encodeToString(salt)
				+ "$" + encoder.encodeToString(hash);
	}

	@SuppressWarnings("PMD.UseVarargs")
	private static byte[] toBytes(char[] chars) {
		byte[] bytes = new byte[chars.length * 2];
		for (int i = 0; i < chars.length; i++) {
			bytes[i * 2]     = (byte) (chars[i] >> 8);
			bytes[i * 2 + 1] = (byte) chars[i];
		}
		return bytes;
	}

	private static boolean constantTimeEquals(byte[] left, byte[] right) {
		if (left.length != right.length) {
			return false;
		}
		int result = 0;
		for (int i = 0; i < left.length; i++) {
			result |= left[i] ^ right[i];
		}
		return result == 0;
	}

	@SuppressWarnings("java:S6218")
	private record PhcFields(byte[] salt, byte[] hash, int memoryCostKib, int iterations, int parallelism) {

		/* default */ static PhcFields parse(String encoded) {
			String[] parts = encoded.split("\\$", -1);
			if (parts.length != 6 || !"argon2id".equals(parts[1])) {
				throw new IllegalArgumentException("Invalid PHC string: expected $argon2id$ format");
			}
			if (!"v=19".equals(parts[2])) {
				throw new IllegalArgumentException(
						"Invalid PHC string: unsupported version '" 
                        + parts[2] + "', expected v=19");
			}
			int[] params = parseParams(parts[3]);
			Base64.Decoder dec = Base64.getDecoder();
			try {
				return new PhcFields(dec.decode(parts[4]), dec.decode(parts[5]),
						params[0], params[1], params[2]);
			} catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("Invalid PHC string: base64 decode failed", ex);
			}
		}

		/* default */ static int[] parseParams(String paramSegment) {
			int memoryCostKib = 0;
			int iterations = 0;
			int parallelism = 0;
			for (String token : paramSegment.split(",")) {
				if (token.startsWith("m=")) {
					memoryCostKib = Integer.parseInt(token.substring(2));
				} else if (token.startsWith("t=")) {
					iterations = Integer.parseInt(token.substring(2));
				} else if (token.startsWith("p=")) {
					parallelism = Integer.parseInt(token.substring(2));
				}
			}
			if (memoryCostKib <= 0 || iterations <= 0 || parallelism <= 0) {
				throw new IllegalArgumentException(
						"Invalid PHC string: missing or invalid m/t/p parameters");
			}
			return new int[]{memoryCostKib, iterations, parallelism};
		}
	}
}
