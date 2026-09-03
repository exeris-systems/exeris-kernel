/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;

import eu.exeris.kernel.community.crypto.SocketChannelFdAccess;
import eu.exeris.kernel.core.transport.syscall.CoreSyscallLoader;
import eu.exeris.kernel.core.transport.syscall.SyscallHandles;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.Arena;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Community socket-backend selector and bootstrap-validation lifecycle owner.
 *
 * <p>Extracted from {@link NativeTcpCarrier} in v0.8 Sprint 1 (QA-013a). Owns:
 *
 * <ul>
 *   <li>The {@link SocketBackendMode} selection from JVM property / env vars.</li>
 *   <li>The {@link SocketBackendSelection} resolution result (mode + arena +
 *       syscall handles + armed flag + diagnostic detail string).</li>
 *   <li>The shared {@link Arena} that owns the syscall-handle lifetime
 *       (deterministically closed by {@link #close()}).</li>
 *   <li>The {@link AtomicBoolean} latches that turn first-server-start and
 *       first-client-connect into one-shot validation triggers.</li>
 * </ul>
 *
 * <p>Native socket helpers (FFM downcalls, sockaddr serialization, server/
 * client probes) live in {@link NativeTcpSocketProbe}; this class only owns
 * state and routes validation calls through to the probe helpers.
 */
final class NativeTcpSocketBackend {

    /**
     * Config key for the backend selection. Resolved through {@link ConfigProvider} first, so the
     * choice is reachable from a config file and the environment and appears in
     * {@code docs/subsystems/config.md} — the property and env aliases below stay as the second
     * tier, because they were the published surface and a deployment may be using them.
     */
    /* default */ static final String SOCKET_BACKEND_KEY = "transport.socket.backend";

    /* default */ static final String SOCKET_BACKEND_PROPERTY = "exeris.community.transport.socket.backend";
    /* default */ static final String SOCKET_BACKEND_ENV = "EXERIS_COMMUNITY_TRANSPORT_SOCKET_BACKEND";
    /* default */ static final String SOCKET_BACKEND_ENV_ALIAS = "SOCKET_BACKEND_ENV";
    /* default */ static final String SOCKET_BACKEND_AUTO = "auto";
    /* default */ static final String SOCKET_BACKEND_NIO = "nio";
    /* default */ static final String SOCKET_BACKEND_POSIX_HYBRID = "posix-hybrid";

    private static final System.Logger LOG = System.getLogger(NativeTcpSocketBackend.class.getName());
    private static final String ACTIVE_NIO_FALLBACK_DETAIL = "continuing on the active NIO fallback.";

    private final SocketBackendSelection selection;
    private final AtomicBoolean serverSocketValidationAttempted = new AtomicBoolean(false);
    private final AtomicBoolean serverSocketValidationSuccessful = new AtomicBoolean(false);
    private final AtomicBoolean clientSocketValidationAttempted = new AtomicBoolean(false);
    private final AtomicBoolean clientSocketValidationSuccessful = new AtomicBoolean(false);

    /* default */ NativeTcpSocketBackend() {
        this.selection = SocketBackendSelection.resolve();
    }

    /* default */ String requestedSocketBackend() {
        return selection.requestedMode().configValue();
    }

    /* default */ String activeSocketBackend() {
        if (selection.requestedMode() == SocketBackendMode.NIO) {
            return SOCKET_BACKEND_NIO;
        }
        return selection.ffmSocketBackendArmed() ? SOCKET_BACKEND_POSIX_HYBRID : SOCKET_BACKEND_NIO;
    }

    /* default */ boolean isFfmSocketBackendArmed() {
        return selection.ffmSocketBackendArmed();
    }

    /* default */ boolean isServerSocketValidationSuccessful() {
        return serverSocketValidationSuccessful.get();
    }

    /* default */ boolean isClientSocketValidationSuccessful() {
        return clientSocketValidationSuccessful.get();
    }

    /* default */ SyscallHandles socketHandles() {
        return selection.socketHandles();
    }

    /* default */ String detail() {
        return selection.detail();
    }

    /* default */ void validateServerSocketBootstrapOnce(MemoryAllocator allocator) {
        if (!isFfmSocketBackendArmed() || !serverSocketValidationAttempted.compareAndSet(false, true)) {
            return;
        }
        SyscallHandles handles = resolvedSocketHandlesForValidation();
        boolean successful = handles != null
                && NativeTcpSocketProbe.validateServerSocketBootstrap(allocator, handles);
        serverSocketValidationSuccessful.set(successful);
        if (!successful) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Core socket seam could not be validated for the server path; continuing on the active NIO path.");
        }
    }

    /* default */ void validateClientSocketBackendOnce(MemoryAllocator allocator, String host, int port) {
        if (!isFfmSocketBackendArmed() || !clientSocketValidationAttempted.compareAndSet(false, true)) {
            return;
        }
        SyscallHandles handles = resolvedSocketHandlesForValidation();
        boolean successful = handles != null
                && NativeTcpSocketProbe.validateClientSocketConnect(allocator, handles, host, port);
        clientSocketValidationSuccessful.set(successful);
        if (!successful) {
            LOG.log(System.Logger.Level.DEBUG, () ->
                    "Core socket seam could not be validated for the client path " + host + ':' + port
                            + "; continuing on the active NIO path.");
        }
    }

    @SuppressWarnings("PMD.CloseResource") // arena is intentionally closed here as the seam's lifetime owner.
    /* default */ void close() {
        SyscallHandles handles = selection.socketHandles();
        Arena arena = selection.socketBackendArena();
        try {
            if (arena != null) {
                arena.close();
            }
        } finally {
            NativeTcpSocketProbe.bestEffortWsaCleanup(handles);
        }
    }

    private SyscallHandles resolvedSocketHandlesForValidation() {
        if (!isFfmSocketBackendArmed()) {
            return null;
        }
        SyscallHandles handles = selection.socketHandles();
        return handles != null && handles.supportsPlainSocketIo() ? handles : null;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // best-effort close swallows any cleanup exception.
    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception _) {
            // best effort
        }
    }

    /* default */ enum SocketBackendMode {
        AUTO(SOCKET_BACKEND_AUTO),
        NIO(SOCKET_BACKEND_NIO),
        POSIX_HYBRID(SOCKET_BACKEND_POSIX_HYBRID);

        private final String configValue;

        SocketBackendMode(String configValue) {
            this.configValue = configValue;
        }

        /* default */ String configValue() {
            return configValue;
        }

        /**
         * The backend is chosen while the carrier is constructed, which happens inside the boot
         * scope, so {@code CURRENT_CONFIG} is bound. A carrier built outside a boot (tests,
         * tooling) reads {@code null} and falls through to the property ladder unchanged.
         *
         * @return the configured mode string, or {@code null} when no provider is bound
         */
        private static String fromConfigProvider() {
            if (!KernelProviders.CURRENT_CONFIG.isBound()) {
                return null;
            }
            ConfigProvider config = KernelProviders.CURRENT_CONFIG.get();
            return config == null ? null : config.getString(SOCKET_BACKEND_KEY).orElse(null);
        }

        /**
         * First non-blank of the ordered sources. A loop rather than a fallback chain because the
         * chain's NPath grows multiplicatively with each source and PMD refuses it at five —
         * correctly, since the branching says nothing the ordering does not.
         *
         * @param candidates the sources, in precedence order
         * @return the first usable value, or {@code null}
         */
        private static String firstNonBlank(String... candidates) {
            for (String candidate : candidates) {
                if (candidate != null && !candidate.isBlank()) {
                    return candidate;
                }
            }
            return null;
        }

        private static SocketBackendMode resolveConfiguredMode() {
            String configured = firstNonBlank(
                    fromConfigProvider(),
                    System.getProperty(SOCKET_BACKEND_PROPERTY),
                    System.getProperty(SOCKET_BACKEND_ENV_ALIAS),
                    System.getenv(SOCKET_BACKEND_ENV),
                    System.getenv(SOCKET_BACKEND_ENV_ALIAS));
            if (configured == null) {
                return AUTO;
            }
            return switch (configured.trim().toLowerCase(Locale.ROOT)) {
                case SOCKET_BACKEND_AUTO -> AUTO;
                case SOCKET_BACKEND_NIO -> NIO;
                case SOCKET_BACKEND_POSIX_HYBRID -> POSIX_HYBRID;
                default -> {
                    String invalidMode = configured;
                    LOG.log(System.Logger.Level.WARNING, () ->
                            "Unknown Community socket backend mode '" + invalidMode + "'; defaulting to auto.");
                    yield AUTO;
                }
            };
        }
    }

    /* default */ record SocketBackendSelection(SocketBackendMode requestedMode,
                                                Arena socketBackendArena,
                                                SyscallHandles socketHandles,
                                                boolean ffmSocketBackendArmed,
                                                String detail) {

        /* default */ static SocketBackendSelection resolve() {
            SocketBackendMode configuredMode = SocketBackendMode.resolveConfiguredMode();
            if (configuredMode == SocketBackendMode.NIO) {
                return new SocketBackendSelection(
                        configuredMode,
                        null,
                        null,
                        false,
                        "NIO backend pinned explicitly; Core syscall load skipped.");
            }
            if (NativeTcpSocketProbe.IS_WINDOWS_RUNTIME) {
                String detail = configuredMode == SocketBackendMode.POSIX_HYBRID
                        ? resolveRequestedFallbackDetail(true, true)
                        : resolveAutoFallbackDetail(true, true);
                return new SocketBackendSelection(configuredMode, null, null, false, detail);
            }

            //CHECKSTYLE:OFF
            // Direct shared-arena allocation: the Community carrier is the sole owner of
            // the socket backend seam lifetime. Shared scope is required because resolved
            // syscall handles are used across transport threads, and the backend's
            // close() deterministically closes this arena.
            Arena arena = Arena.ofShared();
            //CHECKSTYLE:ON
            SyscallHandles handles = null;
            try {
                handles = CoreSyscallLoader.load(arena);
            } catch (IllegalCallerException | IllegalStateException | UnsupportedOperationException ex) {
                LOG.log(System.Logger.Level.DEBUG,
                        "[NativeTcpSocketBackend] Community-owned Core socket load unavailable; "
                                + "continuing on NIO fallback.",
                        ex);
            }

            if (handles == null) {
                closeQuietly(arena);
                String detail;
                if (configuredMode == SocketBackendMode.POSIX_HYBRID) {
                    detail = "Core socket seam was requested explicitly but is unavailable; "
                            + ACTIVE_NIO_FALLBACK_DETAIL;
                } else {
                    detail = "Core socket seam probe unavailable on this platform; "
                            + ACTIVE_NIO_FALLBACK_DETAIL;
                }
                return new SocketBackendSelection(configuredMode, null, null, false, detail);
            }

            boolean plainSocketIoAvailable = handles.supportsPlainSocketIo();
            boolean fdAccessAvailable = SocketChannelFdAccess.isRuntimeFdAccessAvailable();
            boolean winsockModel = handles.hasIoctlsocket();
            boolean seamArmed = plainSocketIoAvailable && fdAccessAvailable && !winsockModel;
            String detail = resolveDetail(configuredMode, seamArmed, plainSocketIoAvailable, winsockModel);
            return new SocketBackendSelection(configuredMode, arena, handles, seamArmed, detail);
        }

        private static String resolveDetail(SocketBackendMode configuredMode,
                                            boolean seamArmed,
                                            boolean plainSocketIoAvailable,
                                            boolean winsockModel) {
            if (seamArmed) {
                return "Core socket seam armed for plain TCP traffic; "
                        + "selector ownership and NIO fallback remain intact.";
            }
            if (configuredMode == SocketBackendMode.POSIX_HYBRID) {
                return resolveRequestedFallbackDetail(plainSocketIoAvailable, winsockModel);
            }
            return resolveAutoFallbackDetail(plainSocketIoAvailable, winsockModel);
        }

        private static String resolveRequestedFallbackDetail(boolean plainSocketIoAvailable,
                                                             boolean winsockModel) {
            if (!plainSocketIoAvailable) {
                return "Core socket seam was requested explicitly but is unavailable; "
                        + ACTIVE_NIO_FALLBACK_DETAIL;
            }
            if (winsockModel) {
                return "Core socket seam was requested explicitly but this platform uses the Winsock model; "
                        + ACTIVE_NIO_FALLBACK_DETAIL;
            }
            return "Core socket seam was requested explicitly but "
                    + "SocketChannel FD access is blocked by runtime openness; "
                    + "add the required --add-opens flags or "
                    + ACTIVE_NIO_FALLBACK_DETAIL;
        }

        private static String resolveAutoFallbackDetail(boolean plainSocketIoAvailable,
                                                        boolean winsockModel) {
            if (!plainSocketIoAvailable) {
                return "Core socket seam resolved without plain socket syscall support; "
                        + ACTIVE_NIO_FALLBACK_DETAIL;
            }
            if (winsockModel) {
                return "Core socket seam resolved on a Winsock platform; "
                        + ACTIVE_NIO_FALLBACK_DETAIL;
            }
            return "Core socket seam resolved but SocketChannel FD access "
                    + "is blocked by runtime openness; "
                    + "add the required --add-opens flags or "
                    + ACTIVE_NIO_FALLBACK_DETAIL;
        }
    }
}
