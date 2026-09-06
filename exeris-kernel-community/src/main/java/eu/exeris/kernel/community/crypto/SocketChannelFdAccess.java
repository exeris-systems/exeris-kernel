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
 * @since 0.5
 */
public final class SocketChannelFdAccess {

	private static final boolean RUNTIME_FD_ACCESS_AVAILABLE = probeRuntimeFdAccessAvailability();

	private SocketChannelFdAccess() {
	}

	/**
	 * Resolves {@code channel}'s raw file descriptor via
	 * {@link SocketChannelFdReflectionResolver}.
	 *
	 * @param channel the socket channel to resolve
	 * @return the channel's raw file descriptor
	 * @throws TlsHandshakeException ({@code EX-NET-2001}) if {@code channel} is {@code null}, or
	 *         if no reflective path to its file descriptor is available
	 */
	public static int requireFd(SocketChannel channel) {
		if (channel == null) {
			throw new TlsHandshakeException("SocketChannel must not be null");
		}
		return SocketChannelFdReflectionResolver.resolve(channel);
	}

	/**
	 * Returns {@code true} if this JVM exposes a reflective path to a {@link SocketChannel}'s
	 * raw file descriptor, probed once at class initialization.
	 *
	 * @return whether reflective file descriptor access is available in this JVM
	 */
	public static boolean isRuntimeFdAccessAvailable() {
		return RUNTIME_FD_ACCESS_AVAILABLE;
	}

	/**
	 * Returns {@code true} if {@link #requireFd(SocketChannel)} would resolve a file descriptor
	 * for {@code channel} without throwing; returns {@code false} for a {@code null} channel or
	 * one whose file descriptor cannot be resolved.
	 *
	 * @param channel the socket channel to probe, or {@code null}
	 * @return whether the channel's file descriptor can be resolved
	 */
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