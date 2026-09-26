/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.security;

import eu.exeris.kernel.spi.security.credentials.KernelPasswordEncoder;
import eu.exeris.kernel.spi.security.credentials.PasswordEncoderConfig;
import eu.exeris.kernel.tck.contract.security.AbstractPasswordEncoderTck;

class Argon2idPasswordEncoderTckTest extends AbstractPasswordEncoderTck {

	@Override
	protected KernelPasswordEncoder encoderWithConfig(PasswordEncoderConfig config) {
		return new Argon2idPasswordEncoder(config);
	}
}
