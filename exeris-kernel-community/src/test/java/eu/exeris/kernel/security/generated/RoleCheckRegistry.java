/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.security.generated;

/**
 * Test fixture mirroring the exact static-accessor shape that
 * {@code RequiresRoleProcessor} emits for the generated registry (see
 * {@code RequiresRoleProcessor.writeAccessors}). Lives in the canonical
 * generated package so {@code GeneratedRoleRegistryLoader} resolves it by FQN
 * via {@code Class.forName} on the Community test classpath — exercising the
 * loader's "present" path without running the annotation processor in this
 * module.
 *
 * <p>One ANY method ({@code M_0}) requires {@code ROLE_ADMIN} (canonical system
 * bit 1), matching the TCK's deterministic expectations.
 */
public final class RoleCheckRegistry {

    /** AdminApi.purge() */
    public static final int M_0 = 0;

    private static final long[] REQUIRED_ANY = {
            0x2L
    };

    private static final long[] REQUIRED_ALL = {
            0x0L
    };

    private static final boolean[] MATCH_IS_ALL = {
            false
    };

    private static final String[] METHOD_NAMES = {
            "AdminApi.purge()"
    };

    private static final java.util.Map<String, Integer> ROLE_BITS;
    static {
        java.util.HashMap<String, Integer> bits = new java.util.HashMap<>();
        bits.put("ROLE_ADMIN", 1);
        bits.put("ROLE_OPERATOR", 2);
        bits.put("ROLE_SYSTEM", 0);
        bits.put("ROLE_USER", 3);
        ROLE_BITS = java.util.Map.copyOf(bits);
    }

    public static int methodCount() {
        return 1;
    }

    public static long requiredAny(int methodId) {
        return REQUIRED_ANY[methodId];
    }

    public static long requiredAll(int methodId) {
        return REQUIRED_ALL[methodId];
    }

    public static boolean matchIsAll(int methodId) {
        return MATCH_IS_ALL[methodId];
    }

    public static String methodName(int methodId) {
        return METHOD_NAMES[methodId];
    }

    public static java.util.Set<String> roleNames() {
        return ROLE_BITS.keySet();
    }

    public static int roleNameToBit(String role) {
        Integer bit = ROLE_BITS.get(role);
        return bit == null ? -1 : bit;
    }

    private RoleCheckRegistry() {
        // generated registry — not instantiable
    }
}
