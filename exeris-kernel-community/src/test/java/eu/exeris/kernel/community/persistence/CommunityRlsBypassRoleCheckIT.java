/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.core.persistence.PersistenceBootstrap;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.PersistenceConfig;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceProvider;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.kernel.tck.contract.persistence.AbstractRlsBypassRoleCheckTck;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Community binding of {@link AbstractRlsBypassRoleCheckTck} against a live PostgreSQL instance.
 *
 * <p>The abstract suite owns the refusal matrix; this class provisions one role per attribute set,
 * boots the Community engine under each, and adds the cases only this binding can observe: the JFR
 * event of a consented downgrade, the engine closed by the bootstrap path after a refusal, and the
 * request-time refusal of a dedicated pool.
 *
 * <h2>Execution</h2>
 * <pre>mvn -pl exeris-kernel-community -am test -DincludedGroups=integration -DexcludedGroups=</pre>
 *
 * @since 0.13
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community RLS bypass-role check — live PostgreSQL binding")
class CommunityRlsBypassRoleCheckIT extends AbstractRlsBypassRoleCheckTck {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final String PASSWORD = "role_pw";
    private static final String BYPASS_EVENT = "eu.exeris.kernel.persistence.RlsBypassRole";

    @BeforeAll
    static void provisionRoles() throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("CREATE ROLE exeris_plain LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + PASSWORD + "'");
            st.execute("CREATE ROLE exeris_bypass LOGIN NOSUPERUSER BYPASSRLS PASSWORD '" + PASSWORD + "'");
            st.execute("CREATE ROLE exeris_super LOGIN SUPERUSER NOBYPASSRLS PASSWORD '" + PASSWORD + "'");
            try (ResultSet rs = st.executeQuery(
                    "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = 'exeris_super'")) {
                assertThat(rs.next()).isTrue();
                // The SUPERUSER cell isolates superuser status only if the role really lacks BYPASSRLS.
                assertThat(rs.getBoolean(1)).isTrue();
                assertThat(rs.getBoolean(2)).isFalse();
            }
        }
    }

    @Override
    protected String roleName(RoleKind kind) {
        return switch (kind) {
            case ORDINARY -> "exeris_plain";
            case BYPASSRLS -> "exeris_bypass";
            case SUPERUSER -> "exeris_super";
        };
    }

    @Override
    protected PersistenceEngine bootWithRlsAs(RoleKind kind, boolean allowBypassRole) {
        PersistenceEngine engine = new CommunityPersistenceProvider().createEngine(configAs(kind, allowBypassRole));
        try {
            engine.registerInterceptor(RlsConnectionInterceptor.INSTANCE);
        } catch (RuntimeException refusal) {
            engine.close();
            throw refusal;
        }
        return engine;
    }

    @Override
    protected StorageContext tenantContext() {
        return ImmutableStorageContext.shared("tenant-a");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a consented bypass role emits RlsBypassRole carrying provider, role and attribute")
    void consentedBootEmitsEvent() throws InterruptedException {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<RecordedEvent> captured = new AtomicReference<>();

        try (RecordingStream rs = new RecordingStream()) {
            rs.enable(BYPASS_EVENT);
            rs.onEvent(BYPASS_EVENT, event -> {
                if (captured.compareAndSet(null, event)) {
                    received.countDown();
                }
            });
            rs.startAsync();

            try (PersistenceEngine ignored = bootWithRlsAs(RoleKind.BYPASSRLS, true)) {
                assertThat(received.await(10, TimeUnit.SECONDS))
                        .as("a downgrade MUST be visible without reading the log")
                        .isTrue();
            }
        }

        RecordedEvent event = captured.get();
        assertThat(event.getString("providerName")).isEqualTo(CommunityPersistenceConstants.PROVIDER_ID);
        assertThat(event.getString("roleName")).isEqualTo("exeris_bypass");
        assertThat(event.getString("attribute")).isEqualTo("BYPASSRLS");
    }

    @Test
    @DisplayName("the bootstrap path closes the engine it created when the role is refused")
    void bootstrapClosesEngineOnRefusal() {
        AtomicReference<PersistenceEngine> created = new AtomicReference<>();
        PersistenceProvider recording = new RecordingProvider(created);

        assertThatThrownBy(() -> PersistenceBootstrap.load(
                recording, configAs(RoleKind.BYPASSRLS, false), List.<ConnectionInterceptor>of(RlsConnectionInterceptor.INSTANCE)))
                .isInstanceOfSatisfying(PersistenceProviderException.class,
                        refusal -> assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5009));

        assertThat(created.get()).isNotNull();
        assertThatThrownBy(() -> created.get().openConnection(tenantContext()))
                .as("a refused boot MUST NOT leave a live pool behind")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a bypass role behind a dedicated pool is refused at its first request, and the refusal is remembered")
    void dedicatedPoolIsRefusedAndRefusalCached() throws SQLException {
        PersistenceConfig dedicated = plainConfig("exeris_bypass", Map.of());
        PersistenceConfig shared = new PersistenceConfig(
                POSTGRES.getJdbcUrl(), "exeris_plain", PASSWORD,
                4, 1, 5_000L, 60_000L, 600_000L, true, false, false, 0,
                Map.of(), Map.of("ds-bypass", dedicated));
        StorageContext dedicatedCtx = ImmutableStorageContext.dedicated("tenant-d", "ds-bypass");

        try (PersistenceEngine engine = new CommunityPersistenceProvider().createEngine(shared)) {
            engine.registerInterceptor(RlsConnectionInterceptor.INSTANCE);

            assertThatThrownBy(() -> engine.openConnection(dedicatedCtx))
                    .isInstanceOfSatisfying(PersistenceProviderException.class, refusal -> {
                        assertThat(refusal.errorCode()).isEqualTo(KernelErrorCodes.EX_PERS_5009);
                        assertThat(refusal.rawArgs()[1]).isEqualTo("exeris_bypass");
                    });

            // With the role repaired the pool would now pass a fresh probe; a remembered refusal still stands.
            alterBypass("exeris_bypass", "NOBYPASSRLS");
            try {
                assertThatThrownBy(() -> engine.openConnection(dedicatedCtx))
                        .isInstanceOfSatisfying(PersistenceProviderException.class,
                                refusal -> assertThat(refusal.errorCode())
                                        .isEqualTo(KernelErrorCodes.EX_PERS_5009));
            } finally {
                alterBypass("exeris_bypass", "BYPASSRLS");
            }

            try (PersistenceConnection sharedConnection = engine.openConnection(tenantContext())) {
                assertThat(sharedConnection).as("the shared pool keeps serving").isNotNull();
            }
        }
    }

    private static void alterBypass(String role, String attribute) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("ALTER ROLE " + role + " " + attribute);
        }
    }

    private PersistenceConfig configAs(RoleKind kind, boolean allowBypassRole) {
        return new PersistenceConfig(
                POSTGRES.getJdbcUrl(), roleName(kind), PASSWORD,
                4, 1, 5_000L, 60_000L, 600_000L, true, false, false, 0,
                Map.of("rls.allowBypassRole", Boolean.toString(allowBypassRole)));
    }

    private static PersistenceConfig plainConfig(String role, Map<String, String> properties) {
        return new PersistenceConfig(
                POSTGRES.getJdbcUrl(), role, PASSWORD,
                4, 1, 5_000L, 60_000L, 600_000L, false, false, false, 0, properties);
    }

    /** Delegates to the Community provider and keeps the engine it created. */
    private static final class RecordingProvider implements PersistenceProvider {

        private final CommunityPersistenceProvider delegate = new CommunityPersistenceProvider();
        private final AtomicReference<PersistenceEngine> created;

        private RecordingProvider(AtomicReference<PersistenceEngine> created) {
            this.created = created;
        }

        @Override
        public String providerId() {
            return delegate.providerId();
        }

        @Override
        public String providerName() {
            return delegate.providerName();
        }

        @Override
        public PersistenceEngine createEngine(PersistenceConfig config) {
            PersistenceEngine engine = delegate.createEngine(config);
            created.set(engine);
            return engine;
        }
    }
}
