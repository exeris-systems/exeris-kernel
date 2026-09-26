/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A kernel booted in {@code HttpMode.DUAL} reaches its own listener through the client engine it
 * binds at {@code HTTP_CLIENT_ENGINE}.
 *
 * <p>{@code DUAL} builds a server engine and a client engine from one {@code HttpConfig}. Each
 * engine's transport takes that engine's role: the client's is a {@code CLIENT} transport with no
 * listener, which is what lets it start without a stream handler and dial out.
 */
@Timeout(60)
@DisplayName("Community: a DUAL kernel dials its own listener through HTTP_CLIENT_ENGINE")
class CommunityHttpDualSelfDialTest {

    private final Map<String, String> saved = new HashMap<>();

    @BeforeEach
    void rememberProperties() {
        for (String key : List.of("exeris.http.mode", "exeris.http.bindHost", "exeris.http.port")) {
            saved.put(key, System.getProperty(key));
        }
    }

    @AfterEach
    void restoreProperties() {
        saved.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Test
    @DisplayName("plaintext DUAL: HTTP_CLIENT_ENGINE.send to the kernel's own listener returns 200")
    void plaintextDualKernelReachesItsOwnListener() throws Exception {
        int port = nextFreePort();
        System.setProperty("exeris.http.mode", "DUAL");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));
        AtomicInteger status = new AtomicInteger();

        KernelBootstrap.builder()
                .selector(BootstrapSelector.forNames("http"))
                .build()
                .boot(() -> status.set(getHealth(port)));

        assertThat(status.get())
                .as("the DUAL kernel's client engine reached its own /health")
                .isEqualTo(200);
    }

    private static int getHealth(int port) {
        HttpRequest request = HttpRequest.noBody(HttpMethod.GET, "/health", HttpVersion.HTTP_1_1, List.of())
                .withAuthority("127.0.0.1:" + port);
        HttpResponse response = HttpKernelProviders.HTTP_CLIENT_ENGINE.get().send(request);
        if (response.body() != null) {
            response.body().close();
        }
        return response.status().code();
    }

    private static int nextFreePort() throws java.io.IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }
}
