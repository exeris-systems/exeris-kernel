/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;

import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves SocketChannel file descriptors for community TLS FD-owner binding.
 *
 * @since 0.5.0
 */
public final class SocketChannelFdAccess {

	private static final Field FILE_DESCRIPTOR_FD_FIELD = resolveFileDescriptorFdField();
	private static final ConcurrentMap<Class<?>, Field> CHANNEL_FD_FIELDS = new ConcurrentHashMap<>();
	private static final String FD_ACCESS_DENIED_MESSAGE =
			"SocketChannel FD access denied via FileDescriptor field; "
					+ "use bindFileDescriptor(int)";

	private SocketChannelFdAccess() {
	}

	public static int requireFd(SocketChannel channel) {
		if (channel == null) {
			throw new TlsHandshakeException("SocketChannel must not be null");
		}
		if (FILE_DESCRIPTOR_FD_FIELD == null) {
			throw new TlsHandshakeException(FD_ACCESS_DENIED_MESSAGE);
		}
		Field channelFdField = resolveCachedChannelFdField(channel.getClass());
		return extractFileDescriptorInt(channel, channelFdField);
	}

	private static int extractFileDescriptorInt(SocketChannel channel, Field channelFdField) {
		try {
			FileDescriptor fileDescriptor = (FileDescriptor) channelFdField.get(channel);
			if (fileDescriptor == null) {
				throw new TlsHandshakeException(FD_ACCESS_DENIED_MESSAGE);
			}
			int fileDescriptorInt = FILE_DESCRIPTOR_FD_FIELD.getInt(fileDescriptor);
			if (fileDescriptorInt < 0) {
				throw new TlsHandshakeException(
						"SocketChannel FD access returned invalid descriptor; "
								+ "use bindFileDescriptor(int)");
			}
			return fileDescriptorInt;
		} catch (IllegalAccessException exception) {
			throw new TlsHandshakeException(FD_ACCESS_DENIED_MESSAGE, exception);
		}
	}

	public static boolean isRuntimeFdAccessAvailable() {
		return FILE_DESCRIPTOR_FD_FIELD != null;
	}

	public static boolean canResolveFd(SocketChannel channel) {
		if (channel == null) {
			return false;
		}
		try {
			requireFd(channel);
			return true;
		} catch (TlsHandshakeException exception) {
			return false;
		}
	}

	@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
	private static Field resolveFileDescriptorFdField() {
		// Necessary for reflection access to private native FD field;
		// modern Java module system requires explicit permission 
		// via --add-opens java.base/java.io=ALL-UNNAMED
		try {
			Field fdField = FileDescriptor.class.getDeclaredField("fd");
			fdField.setAccessible(true);
			return fdField;
		} catch (NoSuchFieldException | InaccessibleObjectException exception) {
			return null;
		}
	}

	@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
	private static Field resolveChannelFdField(Class<?> channelType) {
		// Necessary for reflection access to private native FD field;
		// modern Java module system requires explicit permission 
		// via --add-opens java.base/sun.nio.ch=ALL-UNNAMED
		Class<?> current = channelType;
		while (current != null) {
			try {
				Field fdField = current.getDeclaredField("fd");
				if (!FileDescriptor.class.isAssignableFrom(fdField.getType())) {
					current = current.getSuperclass();
					continue;
				}
				fdField.setAccessible(true);
				return fdField;
			} catch (NoSuchFieldException exception) {
				current = current.getSuperclass();
			} catch (SecurityException | InaccessibleObjectException exception) {
				throw new TlsHandshakeException(FD_ACCESS_DENIED_MESSAGE, exception);
			}
		}
		throw new TlsHandshakeException(
				"SocketChannel implementation does not expose FileDescriptor field 'fd'; "
						+ "use bindFileDescriptor(int)");
	}

	private static Field resolveCachedChannelFdField(Class<?> channelType) {
		return CHANNEL_FD_FIELDS.computeIfAbsent(channelType, SocketChannelFdAccess::resolveChannelFdField);
	}
}