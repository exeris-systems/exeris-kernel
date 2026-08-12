/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * Salt is 16 bytes from {@link SecureRandom}. All internal byte array copies are zeroed on exit.
 *
 * @since 0.5.0
 */
public final class Argon2idPasswordEncoder implements KernelPasswordEncoder {

	private static final int SALT_LENGTH = 16;
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final PasswordEncoderConfig config;

	public Argon2idPasswordEncoder(PasswordEncoderConfig config) {
		if (config == null) {
			throw new IllegalArgumentException("config must not be null");
		}
		this.config = config;
	}

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
	private value record PhcFields(byte[] salt, byte[] hash, int memoryCostKib, int iterations, int parallelism) {

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
