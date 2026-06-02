/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.security;

import eu.exeris.kernel.core.security.jfr.SecurityJfrEvents;
import eu.exeris.kernel.spi.security.RoleRegistry;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Core: eager, reflective loader that adapts the build-time generated
 * {@code RoleCheckRegistry} into the {@link RoleRegistry} SPI contract
 * (ADR-014 §3).
 *
 * <h2>Why reflective</h2>
 * <p>The canonical registry is the source-generated
 * {@code eu.exeris.kernel.security.generated.RoleCheckRegistry} emitted by
 * {@code RequiresRoleProcessor}. Core must not declare a compile dependency on
 * that artifact — it only exists when at least one {@code @RequiresRole} is
 * compiled somewhere downstream, and a compile edge would reintroduce the
 * reactor cycle the processor deliberately avoids (build-config is consumed by
 * SPI as a plugin, not a module dependency). The class is therefore resolved by
 * <b>string FQN</b> (mirroring {@code RequiresRoleProcessor.GENERATED_CLASS_FQN})
 * and its five static accessors are bound once to {@link MethodHandle}s.
 *
 * <h2>Hot-path discipline</h2>
 * <p>Reflection happens exactly once, at {@link #load()} time (bootstrap, platform
 * thread). The per-request accessors ({@code requiredAny} / {@code requiredAll} /
 * {@code matchIsAll}) invoke the bound handles via {@code invokeExact} — no
 * {@code Method.invoke}, no boxing, allocation-free.
 *
 * <h2>Fail-closed default</h2>
 * <p>When the generated class is absent (the common case — no {@code @RequiresRole}
 * compiled anywhere) {@link #load()} returns the {@link #empty()} singleton:
 * {@code methodCount() == 0}, every required mask is {@code 0L}, {@code matchIsAll}
 * is {@code false}, and {@code roleNameToBit} returns {@code -1}. This keeps
 * {@link RoleCheckEnforcer} fail-closed — {@code (mask & 0L) != 0L} is always
 * {@code false}, so an empty registry denies, it never allows.
 *
 * <h2>The Wall</h2>
 * <p>Imports only {@code exeris-kernel-spi} and the JDK. No driver, no Spring.
 *
 * @since 0.8.0
 * @see RoleRegistry
 * @see RoleCheckEnforcer
 */
public final class GeneratedRoleRegistryLoader {

    /**
     * Fully-qualified name of the generated registry class. MUST stay in sync
     * with {@code RequiresRoleProcessor.GENERATED_CLASS_FQN}; referenced by
     * string literal to avoid a compile dependency on build-config.
     */
    public static final String GENERATED_CLASS_FQN =
            "eu.exeris.kernel.security.generated.RoleCheckRegistry";

    private static final EmptyRoleRegistry EMPTY = new EmptyRoleRegistry();

    private GeneratedRoleRegistryLoader() {
        // static loader — not instantiable
    }

    /**
     * Resolves the generated registry reflectively and adapts it to the
     * {@link RoleRegistry} SPI. Emits a one-shot bootstrap JFR event recording
     * the resolved {@code methodCount} and whether the class was found, so
     * operators can distinguish "no annotations" from "load failed".
     *
     * <p>Tries the thread-context classloader first, then this loader's own
     * classloader. On {@link ClassNotFoundException} (the common case) returns
     * the {@link #empty()} singleton.
     *
     * @return a {@link RoleRegistry} backed by the generated class, or
     *         {@link #empty()} when no generated class is present
     */
    public static RoleRegistry load() {
        Class<?> generated = resolveGeneratedClass();
        if (generated == null) {
            SecurityJfrEvents.emitRoleRegistryLoaded(false, 0);
            return EMPTY;
        }
        try {
            ReflectiveRoleRegistry registry = ReflectiveRoleRegistry.bind(generated);
            SecurityJfrEvents.emitRoleRegistryLoaded(true, registry.methodCount());
            return registry;
        } catch (ReflectiveOperationException ex) {
            // Class present but signature mismatch — fail closed, never allow-all.
            SecurityJfrEvents.emitRoleRegistryLoaded(false, 0);
            return EMPTY;
        }
    }

    /**
     * Returns the fail-closed empty registry singleton.
     *
     * @return the empty {@link RoleRegistry}: {@code methodCount() == 0}, all
     *         masks {@code 0L}, {@code matchIsAll == false}, {@code roleNameToBit == -1}
     */
    public static RoleRegistry empty() {
        return EMPTY;
    }

    private static Class<?> resolveGeneratedClass() {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        Class<?> resolved = tryResolve(contextLoader);
        if (resolved != null) {
            return resolved;
        }
        // Fallback to this loader's own classloader — intentional: the generated registry
        // is typically packaged with the kernel artifacts, not the context CL. We try the
        // context CL first (UseProperClassLoader), this is the documented fallback.
        return tryResolve(GeneratedRoleRegistryLoader.class.getClassLoader()); //NOPMD UseProperClassLoader
    }

    private static Class<?> tryResolve(ClassLoader loader) {
        if (loader == null) {
            return null;
        }
        try {
            return Class.forName(GENERATED_CLASS_FQN, false, loader);
        } catch (ClassNotFoundException _) {
            return null;
        }
    }

    /**
     * Fail-closed empty registry. A singleton so the no-annotations path is
     * allocation-free.
     */
    private static final class EmptyRoleRegistry implements RoleRegistry {
        @Override
        public long requiredAny(int methodId) {
            return 0L;
        }

        @Override
        public long requiredAll(int methodId) {
            return 0L;
        }

        @Override
        public boolean matchIsAll(int methodId) {
            return false;
        }

        @Override
        public int methodCount() {
            return 0;
        }

        @Override
        public int roleNameToBit(String roleName) {
            return -1;
        }
    }

    /**
     * {@link RoleRegistry} backed by {@link MethodHandle}s bound once to the
     * generated class's static accessors. Per-request methods are
     * {@code invokeExact} — allocation-free, no {@code Method.invoke}.
     *
     * <p>{@code invokeExact} declares {@code throws Throwable}; every accessor
     * therefore re-throws {@link RuntimeException}/{@link Error} unchanged and
     * wraps any other (impossible for these signatures) {@code Throwable} —
     * hence the {@code AvoidCatchingGenericException} suppression. This mirrors
     * the fail-fast catch idiom used by {@code SecurityInterceptor}.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static final class ReflectiveRoleRegistry implements RoleRegistry {

        private final MethodHandle requiredAny;
        private final MethodHandle requiredAll;
        private final MethodHandle matchIsAll;
        private final MethodHandle roleNameToBit;
        private final int methodCount;

        private ReflectiveRoleRegistry(MethodHandle requiredAny,
                                       MethodHandle requiredAll,
                                       MethodHandle matchIsAll,
                                       MethodHandle roleNameToBit,
                                       int methodCount) {
            this.requiredAny = requiredAny;
            this.requiredAll = requiredAll;
            this.matchIsAll = matchIsAll;
            this.roleNameToBit = roleNameToBit;
            this.methodCount = methodCount;
        }

        /* default */ static ReflectiveRoleRegistry bind(Class<?> generated)
                throws ReflectiveOperationException {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle anyHandle = lookup.findStatic(generated, "requiredAny",
                    MethodType.methodType(long.class, int.class));
            MethodHandle allHandle = lookup.findStatic(generated, "requiredAll",
                    MethodType.methodType(long.class, int.class));
            MethodHandle matchHandle = lookup.findStatic(generated, "matchIsAll",
                    MethodType.methodType(boolean.class, int.class));
            MethodHandle countHandle = lookup.findStatic(generated, "methodCount",
                    MethodType.methodType(int.class));
            MethodHandle bitHandle = lookup.findStatic(generated, "roleNameToBit",
                    MethodType.methodType(int.class, String.class));
            int count = invokeCount(countHandle);
            return new ReflectiveRoleRegistry(anyHandle, allHandle, matchHandle, bitHandle, count);
        }

        private static int invokeCount(MethodHandle countHandle) throws ReflectiveOperationException {
            try {
                return (int) countHandle.invokeExact();
            } catch (RuntimeException | Error propagate) {
                throw propagate;
            } catch (Throwable ex) {
                throw new ReflectiveOperationException("methodCount() invocation failed", ex);
            }
        }

        @Override
        public long requiredAny(int methodId) {
            try {
                return (long) requiredAny.invokeExact(methodId);
            } catch (RuntimeException | Error propagate) {
                throw propagate;
            } catch (Throwable ex) {
                throw new IllegalStateException("requiredAny invocation failed", ex);
            }
        }

        @Override
        public long requiredAll(int methodId) {
            try {
                return (long) requiredAll.invokeExact(methodId);
            } catch (RuntimeException | Error propagate) {
                throw propagate;
            } catch (Throwable ex) {
                throw new IllegalStateException("requiredAll invocation failed", ex);
            }
        }

        @Override
        public boolean matchIsAll(int methodId) {
            try {
                return (boolean) matchIsAll.invokeExact(methodId);
            } catch (RuntimeException | Error propagate) {
                throw propagate;
            } catch (Throwable ex) {
                throw new IllegalStateException("matchIsAll invocation failed", ex);
            }
        }

        @Override
        public int methodCount() {
            return methodCount;
        }

        @Override
        public int roleNameToBit(String roleName) {
            try {
                return (int) roleNameToBit.invokeExact(roleName);
            } catch (RuntimeException | Error propagate) {
                throw propagate;
            } catch (Throwable ex) {
                throw new IllegalStateException("roleNameToBit invocation failed", ex);
            }
        }
    }
}
