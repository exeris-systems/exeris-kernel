/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

/**
 * The reference policy's deployment-installed pieces, as the integration tests that bind the
 * conformance suites install them. The text matches {@code docs/subsystems/persistence.md}; the kernel
 * itself ships no DDL.
 */
final class RlsReferencePolicy {

    /**
     * The function the tenant arm of the reference policy calls on a {@code TEXT} tenant column: it
     * raises, with a message naming the key, when the session key is absent or empty.
     */
    static final String REQUIRED_SETTING_FUNCTION = """
            CREATE OR REPLACE FUNCTION public.exeris_required_setting(key text) RETURNS text
              LANGUAGE plpgsql STABLE AS $$
            DECLARE v text := current_setting(key, true);
            BEGIN
              IF v IS NULL OR v = '' THEN
                RAISE EXCEPTION 'session key % is not published', key USING ERRCODE = 'insufficient_privilege';
              END IF;
              RETURN v;
            END $$""";

    /** The tenant arm on a {@code TEXT} tenant column, for {@code USING} and {@code WITH CHECK}. */
    static final String TENANT_ARM = "tenant_id = (SELECT public.exeris_required_setting('exeris.tenant_id'))";

    private RlsReferencePolicy() {
        // constants only
    }
}
