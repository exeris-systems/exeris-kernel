/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * Builds provider stand-ins that differ from a provider under test only in priority and identity.
 *
 * <p>A kernel's provider selection reads {@code priority()} and the implementation class name (its
 * tie-break) and nothing else, so a {@link Proxy} is enough to stage a competitor. Every other call
 * on a rival is a binding bug and fails loudly rather than returning a plausible default.
 */
public final class PriorityRivals {

    /** Utility class; not instantiable. */
    private PriorityRivals() {
    }

    /**
     * Returns a {@link Proxy} implementing {@code spi} whose {@code priority()} returns
     * {@code priority}, whose {@code providerId()} and {@code providerName()} (when the SPI declares
     * them) return {@code id}, whose {@code toString()} returns {@code id}, whose
     * {@code equals}/{@code hashCode} are identity, and every other method throws
     * {@link UnsupportedOperationException} naming the method.
     *
     * @param spi      the provider interface to implement
     * @param priority the value {@code priority()} returns
     * @param id       the identity reported by {@code providerId()}, {@code providerName()} and {@code toString()}
     * @param <P>      the provider type
     * @return a rival provider
     */
    public static <P> P rival(Class<P> spi, int priority, String id) {
        Objects.requireNonNull(spi, "spi must not be null");
        Objects.requireNonNull(id, "id must not be null");
        InvocationHandler handler = new RivalHandler(spi, priority, id);
        // The proxy lives in the SPI's own loader so the interface is visible to it.
        @SuppressWarnings("PMD.UseProperClassLoader")
        ClassLoader loader = spi.getClassLoader();
        return spi.cast(Proxy.newProxyInstance(loader, new Class<?>[] {spi}, handler));
    }

    private record RivalHandler(Class<?> spi, int priority, String id) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            int arity = method.getParameterCount();
            if (arity == 0) {
                switch (name) {
                    case "priority":
                        return priority;
                    case "providerId":
                    case "providerName":
                    case "toString":
                        return id;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        break;
                }
            } else if (arity == 1 && "equals".equals(name)) {
                return proxy == args[0];  // NOPMD CompareObjectsWithEquals - identity equality is the contract
            }
            throw new UnsupportedOperationException(
                    "Rival " + spi.getSimpleName() + " '" + id + "' does not implement " + name);
        }
    }
}
