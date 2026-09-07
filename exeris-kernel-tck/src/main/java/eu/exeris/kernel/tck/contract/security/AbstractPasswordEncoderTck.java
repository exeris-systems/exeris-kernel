/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.security;

import eu.exeris.kernel.spi.security.credentials.KernelPasswordEncoder;
import eu.exeris.kernel.spi.security.credentials.PasswordEncoderConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TCK contract for {@link KernelPasswordEncoder}.
 *
 * <p>Usage:
 * <pre>
 * class Argon2idPasswordEncoderTckTest extends AbstractPasswordEncoderTck {
 *     {@literal @}Override
 *     protected KernelPasswordEncoder encoderWithConfig(PasswordEncoderConfig config) {
 *         return new Argon2idPasswordEncoder(config);
 *     }
 * }
 * </pre>
 *
 * @since 0.5
 */
@SuppressWarnings("java:S2699")
public abstract class AbstractPasswordEncoderTck {

	/** Returns an encoder configured with the given config. Must return a fresh instance per call. */
	protected abstract KernelPasswordEncoder encoderWithConfig(PasswordEncoderConfig config);

	/** Returns an encoder with default config. Delegates to {@link #encoderWithConfig}. */
	protected final KernelPasswordEncoder encoder() {
		return encoderWithConfig(PasswordEncoderConfig.defaults());
	}

	@Test
	void encode_returnsPhcFormattedString() {
		String encoded = encoder().encode("password".toCharArray());
		assertTrue(encoded.startsWith("$argon2id$"), "Expected PHC argon2id prefix");
	}

	@Test
	void encode_differentCallsProduceDifferentSalts() {
		KernelPasswordEncoder enc = encoder();
		String first = enc.encode("password".toCharArray());
		String second = enc.encode("password".toCharArray());
		assertNotEquals(first, second, "Two encodes of the same password should differ (different salts)");
	}

	@Test
	void matches_trueForCorrectPassword() {
		KernelPasswordEncoder enc = encoder();
		String encoded = enc.encode("correct".toCharArray());
		assertTrue(enc.matches("correct".toCharArray(), encoded));
	}

	@Test
	void matches_falseForWrongPassword() {
		KernelPasswordEncoder enc = encoder();
		String encoded = enc.encode("correct".toCharArray());
		assertFalse(enc.matches("wrong".toCharArray(), encoded));
	}

	@Test
	void matches_falseForTamperedHash() {
		KernelPasswordEncoder enc = encoder();
		String encoded = enc.encode("password".toCharArray());
		String tampered = encoded.substring(0, encoded.length() - 4) + "XXXX";
		assertFalse(enc.matches("password".toCharArray(), tampered));
	}

	@Test
	void encode_rejectsNullInput() {
		KernelPasswordEncoder enc = encoder();
		assertThrows(IllegalArgumentException.class, () -> enc.encode(null));
	}

	@Test
	void encode_rejectsEmptyInput() {
		KernelPasswordEncoder enc = encoder();
		assertThrows(IllegalArgumentException.class, () -> enc.encode(new char[0]));
	}

	@Test
	void matches_rejectsNullRaw() {
		KernelPasswordEncoder enc = encoder();
		String encoded = enc.encode("x".toCharArray());
		assertThrows(IllegalArgumentException.class, () -> enc.matches(null, encoded));
	}

	@Test
	void matches_rejectsNullEncoded() {
		KernelPasswordEncoder enc = encoder();
		assertThrows(IllegalArgumentException.class, () -> enc.matches("x".toCharArray(), null));
	}

	@Test
	void matches_rejectsEmptyRaw() {
		KernelPasswordEncoder enc = encoder();
		String encoded = enc.encode("x".toCharArray());
		assertThrows(IllegalArgumentException.class, () -> enc.matches(new char[0], encoded));
	}

	@Test
	void matches_rejectsEmptyEncoded() {
		KernelPasswordEncoder enc = encoder();
		assertThrows(IllegalArgumentException.class, () -> enc.matches("x".toCharArray(), ""));
	}

	@Test
	void matches_rejectsInvalidPhcString() {
		KernelPasswordEncoder enc = encoder();
		assertThrows(IllegalArgumentException.class,
				() -> enc.matches("x".toCharArray(), "notaphcstring"));
	}

	@Test
	void config_rejectsBelowMinimumMemory() {
		assertThrows(IllegalArgumentException.class,
				() -> new PasswordEncoderConfig(4096, 3, 1, 32));
	}

	@Test
	void config_rejectsBelowMinimumIterations() {
		assertThrows(IllegalArgumentException.class,
				() -> new PasswordEncoderConfig(65_536, 0, 1, 32));
	}

	@Test
	void config_rejectsBelowMinimumParallelism() {
		assertThrows(IllegalArgumentException.class,
				() -> new PasswordEncoderConfig(65_536, 3, 0, 32));
	}

	@Test
	void config_rejectsBelowMinimumHashLength() {
		assertThrows(IllegalArgumentException.class,
				() -> new PasswordEncoderConfig(65_536, 3, 1, 8));
	}

	@Test
	void matches_usesStoredParamsFromPhc_notCurrentConfig() {
		PasswordEncoderConfig encodeConfig = new PasswordEncoderConfig(8_192, 1, 1, 16);
		PasswordEncoderConfig verifyConfig = new PasswordEncoderConfig(16_384, 2, 1, 32);

		KernelPasswordEncoder encoder = encoderWithConfig(encodeConfig);
		String encoded = encoder.encode("password".toCharArray());

		KernelPasswordEncoder verifier = encoderWithConfig(verifyConfig);
		assertTrue(verifier.matches("password".toCharArray(), encoded),
				"matches() must use parameters from PHC string, not from current config");
		assertFalse(verifier.matches("wrong".toCharArray(), encoded),
				"matches() must still reject wrong passwords even with different runtime config");
	}
}
