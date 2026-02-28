/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L0 Contract: Security domain value types — {@link ImmutablePrincipal},
 * {@link ImmutableStorageContext}, and {@link StorageContext.IsolationStrategy}.
 *
 * <h2>ImmutablePrincipal — Valhalla-readiness</h2>
 * <p>Structural equals/hashCode; defensive copy of roles/scopes; factory methods verified;
 * null-guard on principalId; roles/scopes are immutable post-construction.
 *
 * <h2>ImmutableStorageContext — strategy-exclusive validation</h2>
 * <p>SHARED/SEPARATED_SCHEMA/DEDICATED strategy rules enforced; GLOBAL singleton;
 * structural equality; factory methods; attribute map defensively copied.
 *
 * <h2>IsolationStrategy ordinal stability</h2>
 * <p>Ordinals used as routing table indices in the PersistenceInterceptor chain.
 *
 * @since 0.5.0
 */
@DisplayName("L0: Security Value Types — ImmutablePrincipal + ImmutableStorageContext + IsolationStrategy")
class SecurityValueTypesTest {

    private static final UUID PRINCIPAL_ID = UUID.fromString("01945a3b-f2c1-7000-0000-000000000001");
    private static final UUID TENANT_ID    = UUID.fromString("01945a3b-f2c1-7000-0000-000000000002");

    // =========================================================================
    // ImmutablePrincipal — construction + Valhalla-readiness
    // =========================================================================

    @Nested
    @DisplayName("ImmutablePrincipal — construction + structural equality + immutable collections")
    class ImmutablePrincipalContract {

        @Test
        @DisplayName("ofTenant(4-arg): stores all fields correctly")
        void ofTenantFourArg() {
            ImmutablePrincipal p = ImmutablePrincipal.ofTenant(
                    PRINCIPAL_ID, TENANT_ID,
                    Set.of("ROLE_ADMIN"), Set.of("read", "write"));

            assertThat(p.principalId()).isEqualTo(PRINCIPAL_ID);
            assertThat(p.tenantId()).isPresent().contains(TENANT_ID);
            assertThat(p.roles()).containsExactly("ROLE_ADMIN");
            assertThat(p.scopes()).containsExactlyInAnyOrder("read", "write");
        }

        @Test
        @DisplayName("ofTenant(3-arg): scopes is empty by default")
        void ofTenantThreeArg() {
            ImmutablePrincipal p = ImmutablePrincipal.ofTenant(PRINCIPAL_ID, TENANT_ID, Set.of("ROLE_USER"));
            assertThat(p.scopes()).isEmpty();
            assertThat(p.tenantId()).isPresent();
        }

        @Test
        @DisplayName("ofScopes(): tenantId is empty, roles are empty, scopes are set")
        void ofScopes() {
            ImmutablePrincipal p = ImmutablePrincipal.ofScopes(PRINCIPAL_ID, Set.of("api:read"));
            assertThat(p.tenantId()).isEmpty();
            assertThat(p.roles()).isEmpty();
            assertThat(p.scopes()).containsExactly("api:read");
        }

        @Test
        @DisplayName("system(): tenantId is empty, scopes are empty")
        void systemFactory() {
            ImmutablePrincipal p = ImmutablePrincipal.system(PRINCIPAL_ID, Set.of("ROLE_SYSTEM"));
            assertThat(p.tenantId()).isEmpty();
            assertThat(p.scopes()).isEmpty();
            assertThat(p.roles()).containsExactly("ROLE_SYSTEM");
        }

        @Test
        @DisplayName("null principalId throws NullPointerException")
        void nullPrincipalIdThrows() {
            Set<String> roles = Set.of();
            assertThatThrownBy(() -> ImmutablePrincipal.ofTenant(null, TENANT_ID, roles))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null roles throws NullPointerException")
        void nullRolesThrows() {
            Optional<UUID> emptyTenant = Optional.empty();
            Set<String> scopes = Set.of();
            assertThatThrownBy(() -> new ImmutablePrincipal(PRINCIPAL_ID, emptyTenant, null, scopes))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("roles set is immutable post-construction — cannot be mutated externally")
        void rolesSetImmutable() {
            ImmutablePrincipal p = ImmutablePrincipal.ofTenant(
                    PRINCIPAL_ID, TENANT_ID, Set.of("ROLE_USER"), Set.of());
            var roles = p.roles();
            assertThatThrownBy(() -> roles.add("ROLE_ADMIN"))
                    .isInstanceOfAny(UnsupportedOperationException.class, IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Defensive copy: mutating original roles set does not affect ImmutablePrincipal")
        void defensiveCopyOfRoles() {
            Set<String> mutableRoles = new HashSet<>();
            mutableRoles.add("ROLE_USER");
            ImmutablePrincipal p = ImmutablePrincipal.ofTenant(
                    PRINCIPAL_ID, TENANT_ID, mutableRoles, Set.of());
            mutableRoles.add("ROLE_ADMIN");
            assertThat(p.roles()).containsExactly("ROLE_USER");
        }

        @Test
        @DisplayName("Structural equals: two identical principals are equal")
        void structuralEquality() {
            ImmutablePrincipal a = ImmutablePrincipal.ofTenant(
                    PRINCIPAL_ID, TENANT_ID, Set.of("ROLE_ADMIN"), Set.of("read"));
            ImmutablePrincipal b = ImmutablePrincipal.ofTenant(
                    PRINCIPAL_ID, TENANT_ID, Set.of("ROLE_ADMIN"), Set.of("read"));
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("Structural hashCode: equal principals have same hashCode")
        void structuralHashCode() {
            ImmutablePrincipal a = ImmutablePrincipal.system(PRINCIPAL_ID, Set.of("ROLE_SYS"));
            ImmutablePrincipal b = ImmutablePrincipal.system(PRINCIPAL_ID, Set.of("ROLE_SYS"));
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("Different principalId yields inequality")
        void differentPrincipalIdNotEqual() {
            ImmutablePrincipal a = ImmutablePrincipal.system(PRINCIPAL_ID, Set.of());
            ImmutablePrincipal b = ImmutablePrincipal.system(UUID.randomUUID(), Set.of());
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("toString() does NOT expose full UUID — masked for safe logging")
        void toStringMasksUuid() {
            ImmutablePrincipal p = ImmutablePrincipal.system(PRINCIPAL_ID, Set.of());
            String str = p.toString();
            assertThat(str).doesNotContain(PRINCIPAL_ID.toString());
        }
    }

    // =========================================================================
    // IsolationStrategy ordinal stability
    // =========================================================================

    @Nested
    @DisplayName("IsolationStrategy ordinal stability — PersistenceInterceptor routing table")
    class IsolationStrategyOrdinals {

        @Test
        @DisplayName("SHARED ordinal == 0")
        void sharedOrdinal() {
            assertThat(StorageContext.IsolationStrategy.SHARED.ordinal()).isZero();
        }

        @Test
        @DisplayName("SEPARATED_SCHEMA ordinal == 1")
        void separatedSchemaOrdinal() {
            assertThat(StorageContext.IsolationStrategy.SEPARATED_SCHEMA.ordinal()).isEqualTo(1);
        }

        @Test
        @DisplayName("DEDICATED ordinal == 2")
        void dedicatedOrdinal() {
            assertThat(StorageContext.IsolationStrategy.DEDICATED.ordinal()).isEqualTo(2);
        }

        @Test
        @DisplayName("Total count == 3 — new strategy requires interceptor chain review")
        void totalCount() {
            assertThat(StorageContext.IsolationStrategy.values()).hasSize(3);
        }
    }

    // =========================================================================
    // ImmutableStorageContext — strategy-exclusive validation + Valhalla-readiness
    // =========================================================================

    @Nested
    @DisplayName("ImmutableStorageContext — strategy validation + structural equality")
    class ImmutableStorageContextContract {

        @Test
        @DisplayName("GLOBAL singleton: isolationKey empty, strategy=SHARED, schemaName empty, dataSourceKey empty")
        void globalSingleton() {
            ImmutableStorageContext g = ImmutableStorageContext.GLOBAL;
            assertThat(g.isolationKey()).isEmpty();
            assertThat(g.strategy()).isEqualTo(StorageContext.IsolationStrategy.SHARED);
            assertThat(g.schemaName()).isEmpty();
            assertThat(g.dataSourceKey()).isEmpty();
            assertThat(g.attributes()).isEmpty();
        }

        @Test
        @DisplayName("system() returns the GLOBAL singleton reference")
        void systemReturnsGlobal() {
            assertThat(ImmutableStorageContext.system()).isSameAs(ImmutableStorageContext.GLOBAL);
        }

        @Test
        @DisplayName("shared(tenantId): isolationKey=tenantId, strategy=SHARED, no schemaName/dataSourceKey")
        void sharedFactory() {
            ImmutableStorageContext s = ImmutableStorageContext.shared("tenant-abc");
            assertThat(s.isolationKey()).isPresent().contains("tenant-abc");
            assertThat(s.strategy()).isEqualTo(StorageContext.IsolationStrategy.SHARED);
            assertThat(s.schemaName()).isEmpty();
            assertThat(s.dataSourceKey()).isEmpty();
        }

        @Test
        @DisplayName("separatedSchema(tenantId, schema): strategy=SEPARATED_SCHEMA, schemaName present")
        void separatedSchemaFactory() {
            ImmutableStorageContext s = ImmutableStorageContext.separatedSchema("tenant-abc", "tenant_abc_schema");
            assertThat(s.strategy()).isEqualTo(StorageContext.IsolationStrategy.SEPARATED_SCHEMA);
            assertThat(s.schemaName()).isPresent().contains("tenant_abc_schema");
            assertThat(s.dataSourceKey()).isEmpty();
        }

        @Test
        @DisplayName("dedicated(tenantId, dsKey): strategy=DEDICATED, dataSourceKey present")
        void dedicatedFactory() {
            ImmutableStorageContext s = ImmutableStorageContext.dedicated("tenant-abc", "ds-primary-eu");
            assertThat(s.strategy()).isEqualTo(StorageContext.IsolationStrategy.DEDICATED);
            assertThat(s.dataSourceKey()).isPresent().contains("ds-primary-eu");
            assertThat(s.schemaName()).isEmpty();
        }

        @Test
        @DisplayName("SHARED with schemaName present throws IllegalArgumentException")
        void sharedWithSchemaNameThrows() {
            Optional<String> isolationKey = Optional.of("tenant");
            Optional<String> schemaName = Optional.of("my_schema");
            Optional<String> noDataSource = Optional.empty();
            Map<String, String> noAttrs = Map.of();
            assertThatThrownBy(() -> new ImmutableStorageContext(
                    isolationKey, StorageContext.IsolationStrategy.SHARED,
                    schemaName, noDataSource, noAttrs))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("SEPARATED_SCHEMA without schemaName throws IllegalArgumentException")
        void separatedSchemaWithoutSchemaNameThrows() {
            Optional<String> isolationKey = Optional.of("tenant");
            Optional<String> noSchema = Optional.empty();
            Optional<String> noDataSource = Optional.empty();
            Map<String, String> noAttrs = Map.of();
            assertThatThrownBy(() -> new ImmutableStorageContext(
                    isolationKey, StorageContext.IsolationStrategy.SEPARATED_SCHEMA,
                    noSchema, noDataSource, noAttrs))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("DEDICATED without dataSourceKey throws IllegalArgumentException")
        void dedicatedWithoutDataSourceKeyThrows() {
            Optional<String> isolationKey = Optional.of("tenant");
            Optional<String> noSchema = Optional.empty();
            Optional<String> noDataSource = Optional.empty();
            Map<String, String> noAttrs = Map.of();
            assertThatThrownBy(() -> new ImmutableStorageContext(
                    isolationKey, StorageContext.IsolationStrategy.DEDICATED,
                    noSchema, noDataSource, noAttrs))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("attributes map is immutable post-construction")
        void attributesImmutable() {
            ImmutableStorageContext s = ImmutableStorageContext.shared("tenant-abc");
            var attrs = s.attributes();
            assertThatThrownBy(() -> attrs.put("key", "val"))
                    .isInstanceOfAny(UnsupportedOperationException.class, IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Structural equals: two identical shared contexts are equal")
        void structuralEquality() {
            ImmutableStorageContext a = ImmutableStorageContext.shared("tenant-abc");
            ImmutableStorageContext b = ImmutableStorageContext.shared("tenant-abc");
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("Structural hashCode: equal contexts have same hashCode")
        void structuralHashCode() {
            ImmutableStorageContext a = ImmutableStorageContext.shared("x");
            ImmutableStorageContext b = ImmutableStorageContext.shared("x");
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("Different tenantId yields inequality")
        void differentTenantIdNotEqual() {
            assertThat(ImmutableStorageContext.shared("tenant-a"))
                    .isNotEqualTo(ImmutableStorageContext.shared("tenant-b"));
        }

        @Test
        @DisplayName("shared() with attributes: attributes are propagated and copied defensively")
        void sharedWithAttributesDefensiveCopy() {
            Map<String, String> original = new java.util.HashMap<>();
            original.put("trace", "abc123");
            ImmutableStorageContext s = ImmutableStorageContext.shared("tenant", original);
            original.put("extra", "should-not-appear");
            assertThat(s.attributes()).containsOnlyKeys("trace");
        }
    }
}

