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
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves SocketChannel file descriptors for community TLS FD-owner binding using reflection.
 *
 * <p>This class attempts to access the file descriptor of a SocketChannel through multiple strategies:
 * <ul>
 *     <li>Direct method access: looks for methods like {@code getFDVal()} 
 *          or {@code fdVal()} that return the descriptor.</li>
 *     <li>Direct field access: looks for fields named {@code fdVal} 
 *          or {@code fd} that hold the descriptor.</li>
 *     <li>FileDescriptor fallback: accesses the channel's FileDescriptor field and 
 *          then its internal descriptor field.</li>
 * </ul>
 *
 * <p>The resolver caches reflective accessors for performance and provides detailed error messages 
 *      when access is not possible, guiding users to use alternative binding methods if necessary.
 *
 * @since 0.6.0
 */
final class SocketChannelFdReflectionResolver {

    private static final String FD_ACCESS_DENIED_MESSAGE =
            "SocketChannel FD access is unavailable via direct channel access or FileDescriptor field; "
                    + "add --add-opens java.base/sun.nio.ch=ALL-UNNAMED and "
                    + "--add-opens java.base/java.io=ALL-UNNAMED, or use bindFileDescriptor(int)";
    private static final String INVALID_FD_MESSAGE =
            "SocketChannel FD access returned invalid descriptor; use bindFileDescriptor(int)";
    private static final String RAW_DESCRIPTOR_UNAVAILABLE_MESSAGE =
            "SocketChannel implementation does not expose a resolvable raw descriptor path; "
                    + "use bindFileDescriptor(int)";

    private SocketChannelFdReflectionResolver() {
    }

    /* default */ static int resolve(SocketChannel channel) {
        Integer directDescriptor = DirectFdResolver.tryResolve(channel);
        return directDescriptor != null ? directDescriptor : FileDescriptorFallback.resolve(channel);
    }

    /* default */ static boolean runtimeResolutionPathAvailable() {
        try (SocketChannel probe = SocketChannel.open()) {
            Class<?> channelType = probe.getClass();
            return DirectFdResolver.hasResolutionPath(channelType)
                    || FileDescriptorFallback.hasResolutionPath(channelType);
        } catch (IOException | SecurityException _) {
            return false;
        }
    }

    /* default */ static boolean isRuntimeResolutionPathAvailable() {
        return runtimeResolutionPathAvailable();
    }

    private static final class DirectFdResolver {

        private static Integer tryResolve(SocketChannel channel) {
            Integer viaMethod = MethodAccessor.tryResolve(channel);
            if (viaMethod != null) {
                return viaMethod;
            }
            return FieldAccessor.tryResolve(channel);
        }

        private static boolean hasResolutionPath(Class<?> channelType) {
            return MethodAccessor.hasAccessor(channelType) || FieldAccessor.hasAccessor(channelType);
        }

        private static Integer toValidatedDescriptor(Object rawDescriptor) {
            if (!(rawDescriptor instanceof Number number)) {
                return null;
            }
            int descriptor = number.intValue();
            if (descriptor < 0) {
                throw new TlsHandshakeException(INVALID_FD_MESSAGE);
            }
            return descriptor;
        }
    }

    private static final class MethodAccessor {

        private static final String[] METHOD_NAMES = {"getFDVal", "fdVal"};
        private static final ConcurrentMap<Class<?>, Method> CACHE = new ConcurrentHashMap<>();

        private static Integer tryResolve(SocketChannel channel) {
            Method accessor = CACHE.computeIfAbsent(channel.getClass(), MethodAccessor::resolveMethod);
            if (accessor == null) {
                return null;
            }
            try {
                return DirectFdResolver.toValidatedDescriptor(accessor.invoke(channel));
            } catch (ReflectiveOperationException | IllegalArgumentException _) {
                return null;
            }
        }

        private static boolean hasAccessor(Class<?> channelType) {
            return CACHE.computeIfAbsent(channelType, MethodAccessor::resolveMethod) != null;
        }

        private static Method resolveMethod(Class<?> channelType) {
            Class<?> current = channelType;
            while (current != null) {
                Method resolved = resolveDeclaredMethod(current);
                if (resolved != null) {
                    return resolved;
                }
                current = current.getSuperclass();
            }
            return null;
        }

        private static Method resolveDeclaredMethod(Class<?> channelType) {
            for (String methodName : METHOD_NAMES) {
                try {
                    Method method = channelType.getDeclaredMethod(methodName);
                    if (!hasDirectFdSignature(method)) {
                        continue;
                    }
                    return method.trySetAccessible() ? method : null;
                } catch (NoSuchMethodException _) {
                    // try next candidate
                } catch (SecurityException | InaccessibleObjectException _) {
                    return null;
                }
            }
            return null;
        }

        private static boolean hasDirectFdSignature(Method method) {
            return method.getParameterCount() == 0
                    && (method.getReturnType() == int.class
                    || Number.class.isAssignableFrom(method.getReturnType()));
        }
    }

    private static final class FieldAccessor {

        private static final String[] FIELD_NAMES = {"fdVal", "fd"};
        private static final ConcurrentMap<Class<?>, Field> CACHE = new ConcurrentHashMap<>();

        private static Integer tryResolve(SocketChannel channel) {
            Field accessor = CACHE.computeIfAbsent(channel.getClass(), FieldAccessor::resolveField);
            if (accessor == null) {
                return null;
            }
            try {
                return DirectFdResolver.toValidatedDescriptor(accessor.get(channel));
            } catch (IllegalAccessException | IllegalArgumentException _) {
                return null;
            }
        }

        private static boolean hasAccessor(Class<?> channelType) {
            return CACHE.computeIfAbsent(channelType, FieldAccessor::resolveField) != null;
        }

        private static Field resolveField(Class<?> channelType) {
            Class<?> current = channelType;
            while (current != null) {
                Field resolved = resolveDeclaredField(current);
                if (resolved != null) {
                    return resolved;
                }
                current = current.getSuperclass();
            }
            return null;
        }

        private static Field resolveDeclaredField(Class<?> channelType) {
            for (String fieldName : FIELD_NAMES) {
                try {
                    Field fdField = channelType.getDeclaredField(fieldName);
                    Class<?> fieldType = fdField.getType();
                    if (fieldType != int.class && fieldType != Integer.class) {
                        continue;
                    }
                    return fdField.trySetAccessible() ? fdField : null;
                } catch (NoSuchFieldException _) {
                    // try next candidate
                } catch (SecurityException | InaccessibleObjectException _) {
                    return null;
                }
            }
            return null;
        }
    }

    private static final class FileDescriptorFallback {

        private static final Field FILE_DESCRIPTOR_FD_FIELD = resolveFileDescriptorFdField();
        private static final ConcurrentMap<Class<?>, Field> CHANNEL_FD_FIELDS = new ConcurrentHashMap<>();

        private static int resolve(SocketChannel channel) {
            if (FILE_DESCRIPTOR_FD_FIELD == null) {
                throw new TlsHandshakeException(FD_ACCESS_DENIED_MESSAGE);
            }
            Field channelFdField = CHANNEL_FD_FIELDS.computeIfAbsent(
                    channel.getClass(), FileDescriptorFallback::resolveChannelFdField);
            return extractFileDescriptorInt(channel, channelFdField);
        }

        private static boolean hasResolutionPath(Class<?> channelType) {
            if (FILE_DESCRIPTOR_FD_FIELD == null) {
                return false;
            }
            try {
                return CHANNEL_FD_FIELDS.computeIfAbsent(channelType,
                        FileDescriptorFallback::resolveChannelFdField) != null;
            } catch (TlsHandshakeException _) {
                return false;
            }
        }

        private static int extractFileDescriptorInt(SocketChannel channel, Field channelFdField) {
            try {
                FileDescriptor fileDescriptor = (FileDescriptor) channelFdField.get(channel);
                if (fileDescriptor == null) {
                    throw new TlsHandshakeException(FD_ACCESS_DENIED_MESSAGE);
                }
                Integer descriptor = DirectFdResolver.toValidatedDescriptor(
                        FILE_DESCRIPTOR_FD_FIELD.get(fileDescriptor));
                if (descriptor == null) {
                    throw new TlsHandshakeException(FD_ACCESS_DENIED_MESSAGE);
                }
                return descriptor;
            } catch (IllegalAccessException exception) {
                throw new TlsHandshakeException(FD_ACCESS_DENIED_MESSAGE, exception);
            }
        }

        private static Field resolveFileDescriptorFdField() {
            try {
                Field fdField = FileDescriptor.class.getDeclaredField("fd");
                return fdField.trySetAccessible() ? fdField : null;
            } catch (NoSuchFieldException | SecurityException | InaccessibleObjectException _) {
                return null;
            }
        }

        private static Field resolveChannelFdField(Class<?> channelType) {
            Class<?> current = channelType;
            while (current != null) {
                try {
                    Field fdField = current.getDeclaredField("fd");
                    if (!FileDescriptor.class.isAssignableFrom(fdField.getType())) {
                        current = current.getSuperclass();
                        continue;
                    }
                    if (!fdField.trySetAccessible()) {
                        throw new TlsHandshakeException(FD_ACCESS_DENIED_MESSAGE);
                    }
                    return fdField;
                } catch (NoSuchFieldException _) {
                    current = current.getSuperclass();
                } catch (SecurityException | InaccessibleObjectException exception) {
                    throw new TlsHandshakeException(FD_ACCESS_DENIED_MESSAGE, exception);
                }
            }
            throw new TlsHandshakeException(RAW_DESCRIPTOR_UNAVAILABLE_MESSAGE);
        }
    }
}
