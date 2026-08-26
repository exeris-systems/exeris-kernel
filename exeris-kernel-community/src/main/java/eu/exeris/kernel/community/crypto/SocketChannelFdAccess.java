/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;

import java.nio.channels.SocketChannel;

/**
 * Resolves SocketChannel file descriptors for community TLS FD-owner binding.
 *
 * @since 0.5.0
 */
public final class SocketChannelFdAccess {

	private static final boolean RUNTIME_FD_ACCESS_AVAILABLE = probeRuntimeFdAccessAvailability();

	private SocketChannelFdAccess() {
	}

	public static int requireFd(SocketChannel channel) {
		if (channel == null) {
			throw new TlsHandshakeException("SocketChannel must not be null");
		}
		return SocketChannelFdReflectionResolver.resolve(channel);
	}

	public static boolean isRuntimeFdAccessAvailable() {
		return RUNTIME_FD_ACCESS_AVAILABLE;
	}

	public static boolean canResolveFd(SocketChannel channel) {
		if (channel == null) {
			return false;
		}
		try {
			requireFd(channel);
			return true;
		} catch (TlsHandshakeException _) {
			return false;
		}
	}

	private static boolean probeRuntimeFdAccessAvailability() {
		return SocketChannelFdReflectionResolver.isRuntimeResolutionPathAvailable();
	}
}