/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import eu.exeris.kernel.community.http.CommunityHttpProvider;
import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.http.HttpClientEngine;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import jdk.jfr.consumer.RecordingStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Executes the TLS smoke verification step over HTTP/1.1 and HTTP/2.
 */
@SuppressWarnings({
        "PMD.SystemPrintln", "PMD.AvoidUsingHardCodedIP",
        "PMD.AvoidCatchingGenericException", "PMD.DoNotTerminateVM",
        "PMD.SignatureDeclareThrowsException", "PMD.UseTryWithResources"
})
/* default */ final class TlsStep {

    private static final String LOOPBACK = "127.0.0.1";
    private static final int REQUIRED_ARGS = 2;
    private static final int TIMEOUT_SECONDS = 10;

    private TlsStep() {
        // Utility step class
    }

    /* default */ static void run(String... arguments) throws Exception {
        if (arguments.length < REQUIRED_ARGS) {
            System.out.println("ERROR: tls requires <certPath> <keyPath>");
            System.exit(1);
        }
        Path certPath = Path.of(arguments[0]);
        Path keyPath = Path.of(arguments[1]);

        Checks checks = Checks.declare("tls", "kernel-client", "jdk-http1", "jdk-http2", "recording-stream");
        int tlsPort = allocatePort();

        System.setProperty("exeris.http.mode", "SERVER");
        System.setProperty("exeris.http.bindHost", LOOPBACK);
        System.setProperty("exeris.http.port", Integer.toString(tlsPort));
        System.setProperty("exeris.transport.certPath", certPath.toString());
        System.setProperty("exeris.transport.keyPath", keyPath.toString());
        System.setProperty("exeris.crypto.tls.client.trustFile", certPath.toString());

        CountDownLatch bootReadyLatch = new CountDownLatch(1);
        RecordingStream recordingStream = null;
        try {
            recordingStream = new RecordingStream();
            recordingStream.enable("eu.exeris.kernel.bootstrap.KernelBootReady");
            recordingStream.onEvent("eu.exeris.kernel.bootstrap.KernelBootReady", _ -> bootReadyLatch.countDown());
            recordingStream.startAsync();
        } catch (Throwable t) {
            printException("RecordingStream initialization error", t);
            checks.check("recording-stream", false, "init-failed: " + t.getMessage());
        }

        long startTime = System.nanoTime();
        long[] bootTimeHolder = {0L};

        try {
            HttpHandler handler = ex -> ex.respond(HttpResponse.noBody(
                    HttpStatus.OK, ex.request().version(), List.of(new HttpHeader("X-Smoke", "tls"))));

            ScopedValue.where(HttpKernelProviders.HTTP_SERVER_HANDLER, handler).run(() -> {
                try {
                    KernelBootstrap bootstrap = KernelBootstrap.builder()
                            .selector(BootstrapSelector.forNames("crypto", "http"))
                            .build();

                    bootstrap.boot(() -> {
                        bootTimeHolder[0] = (System.nanoTime() - startTime) / 1_000_000L;
                        executeKernelClient(tlsPort, checks);
                        executeJdkClient(tlsPort, certPath, HttpClient.Version.HTTP_1_1, "jdk-http1", checks);
                        executeJdkClient(tlsPort, certPath, HttpClient.Version.HTTP_2, "jdk-http2", checks);
                    });
                } catch (Throwable e) {
                    printException("TLS bootstrap error", e);
                }
            });

            if (recordingStream != null) {
                boolean bootDelivered = bootReadyLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                checks.check("recording-stream", bootDelivered, bootDelivered ? "KernelBootReady observed" : "timeout");
            }
        } finally {
            if (recordingStream != null) {
                recordingStream.close();
            }
        }

        boolean passed = checks.finish();
        Checks.printMeasure(bootTimeHolder[0]);
        if (!passed) {
            System.exit(1);
        }
    }

    private static int allocatePort() throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(LOOPBACK, 0));
            return socket.getLocalPort();
        }
    }

    private static void executeKernelClient(int port, Checks checks) {
        HttpConfig config = new HttpConfig(HttpMode.CLIENT, LOOPBACK, port,
                HttpConfig.DEFAULT_MAX_CONNECTIONS, HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT, HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES, false, HttpVersion.HTTP_1_1,
                LOOPBACK + ":" + port, HttpConfig.DEFAULT_MAX_HEADER_BLOCK_SIZE,
                HttpConfig.DEFAULT_MAX_HEADER_LIST_SIZE, HttpConfig.DEFAULT_MAX_STRING_LITERAL_SIZE);

        try (HttpClientEngine client = new CommunityHttpProvider().createClientEngine(config)) {
            client.start();
            HttpResponse response = client.send(
                    HttpRequest.noBody(HttpMethod.GET, "/smoke", HttpVersion.HTTP_1_1, List.of()));
            if (response.body() != null) {
                response.body().close();
            }
            boolean passed = response.status().code() == 200;
            checks.check("kernel-client", passed, passed ? "200 OK" : "status=" + response.status().code());
        } catch (Throwable t) {
            printException("Kernel client TLS error", t);
            checks.check("kernel-client", false, t.getMessage());
        }
    }

    private static void executeJdkClient(int port, Path cert, HttpClient.Version version,
                                         String checkName, Checks checks) {
        try (java.io.InputStream certStream = Files.newInputStream(cert)) {
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance(
                    java.security.KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("smoke",
                    java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(certStream));

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);

            try (HttpClient client = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .version(version)
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build()) {

                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                                URI.create("https://" + LOOPBACK + ":" + port + "/smoke"))
                        .timeout(java.time.Duration.ofSeconds(5))
                        .GET()
                        .build();

                java.net.http.HttpResponse<Void> response = client.send(
                        request, java.net.http.HttpResponse.BodyHandlers.discarding());

                boolean versionMatches = response.version() == version;
                boolean headerMatches = "tls".equals(response.headers().firstValue("X-Smoke").orElse(null));
                boolean passed = response.statusCode() == 200 && versionMatches && headerMatches;

                String detail = passed ? "200 OK version=" + response.version()
                        : "status=" + response.statusCode() + " ver=" + response.version();
                checks.check(checkName, passed, detail);
            }
        } catch (Throwable t) {
            printException("JDK client " + checkName + " error", t);
            checks.check(checkName, false, t.getMessage());
        }
    }

    private static void printException(String prefix, Throwable throwable) {
        System.out.println(prefix + ": " + throwable);
        for (Throwable cause = throwable.getCause(); cause != null; cause = cause.getCause()) {
            System.out.println("  caused by: " + cause);
        }
    }
}
