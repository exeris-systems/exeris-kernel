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
import eu.exeris.kernel.tck.contract.security.AbstractPasswordEncoderTck;

class Argon2idPasswordEncoderTckTest extends AbstractPasswordEncoderTck {

	@Override
	protected KernelPasswordEncoder encoderWithConfig(PasswordEncoderConfig config) {
		return new Argon2idPasswordEncoder(config);
	}
}
