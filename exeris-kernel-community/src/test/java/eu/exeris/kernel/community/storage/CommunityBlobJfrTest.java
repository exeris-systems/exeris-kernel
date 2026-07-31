/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.storage.BlobStorageException;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the Community blob driver records its failure paths to JFR, and records them secret-safely.
 *
 * <p>The deny path matters most: an operator needs to see an unscoped access attempt without reading a
 * stack trace, and must never see an object key, which can carry application data.
 */
@DisplayName("Community filesystem blob store — JFR failure events")
class CommunityBlobJfrTest {

    private static final String ISOLATION_DENIED = "eu.exeris.kernel.storage.BlobIsolationDenied";
    private static final String TRANSFER_FAILED = "eu.exeris.kernel.storage.BlobTransferFailed";
    private static final String SECRET_KEY = "invoice-of-customer-4711.pdf";

    @Test
    @DisplayName("commits BlobIsolationDenied on an unscoped call, without the object key")
    void emitsIsolationDenied(@TempDir Path tmp) throws Exception {
        BlobStore store = new CommunityFilesystemBlobStore(
                BlobStorageConfig.atLocation(tmp.resolve("store").toString()));
        BlobRef ref = new BlobRef("docs", SECRET_KEY);

        Path jfr = tmp.resolve("blob.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(ISOLATION_DENIED);
            recording.enable(TRANSFER_FAILED);
            recording.start();

            ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL)
                    .run(() -> assertThatThrownBy(() -> store.stat(ref))
                            .isInstanceOf(BlobStorageException.class));

            recording.stop();
            recording.dump(jfr);
        }

        List<RecordedEvent> events = RecordingFile.readAllEvents(jfr);

        RecordedEvent denied = events.stream()
                .filter(e -> ISOLATION_DENIED.equals(e.getEventType().getName()))
                .findFirst().orElseThrow();
        assertThat(denied.getString("providerName")).isEqualTo(CommunityBlobFailures.PROVIDER_NAME);
        assertThat(denied.getString("operation")).isEqualTo(CommunityBlobFailures.OP_STAT);
        assertThat(denied.getString("reason"))
                .isEqualTo(CommunityBlobFailures.REASON_NO_ISOLATION_KEY);
        // GLOBAL carries strategy SHARED with an empty key — the strategy alone would not have told an
        // operator that nothing was scoped, which is why the reason is a classification of its own.
        assertThat(denied.getString("strategy")).isEqualTo("SHARED");

        // Secret safety is a property of the whole recording, not of one field.
        assertThat(events).noneSatisfy(event ->
                assertThat(event.toString()).contains(SECRET_KEY));
    }

    @Test
    @DisplayName("commits BlobTransferFailed when the underlying I/O fails")
    void emitsTransferFailed(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("store");
        BlobStore store = new CommunityFilesystemBlobStore(
                BlobStorageConfig.atLocation(root.toString()));
        BlobRef ref = new BlobRef("docs", SECRET_KEY);

        Path jfr = tmp.resolve("blob.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(TRANSFER_FAILED);
            recording.start();

            // A container that already exists as a regular file makes createDirectories fail, which is
            // the cheapest genuine IOException on the upload path that needs no permission games.
            ScopedValue.where(KernelProviders.STORAGE_CONTEXT,
                            ImmutableStorageContext.shared("tenant-alpha"))
                    .run(() -> {
                        blockContainerPath(root);
                        assertThatThrownBy(() -> store.beginUpload(ref, 4L, null))
                                .isInstanceOf(BlobStorageException.class);
                    });

            recording.stop();
            recording.dump(jfr);
        }

        List<RecordedEvent> events = RecordingFile.readAllEvents(jfr);

        RecordedEvent failed = events.stream()
                .filter(e -> TRANSFER_FAILED.equals(e.getEventType().getName()))
                .findFirst().orElseThrow();
        assertThat(failed.getString("providerName")).isEqualTo(CommunityBlobFailures.PROVIDER_NAME);
        assertThat(failed.getString("operation")).isEqualTo(CommunityBlobFailures.OP_UPLOAD);
        assertThat(failed.getString("container")).isEqualTo("docs");
        assertThat(failed.getString("exceptionClass")).isNotBlank();

        assertThat(events).noneSatisfy(event ->
                assertThat(event.toString()).contains(SECRET_KEY));
    }

    /** Creates a regular file exactly where the container directory would go. */
    private static void blockContainerPath(Path root) {
        CommunityFilesystemBlobLayout layout = new CommunityFilesystemBlobLayout(root);
        Path tenantDir = layout.requireTenantDirectory(CommunityBlobFailures.OP_UPLOAD);
        try {
            Files.createDirectories(tenantDir);
            Files.writeString(tenantDir.resolve("docs"), "not-a-directory");
        } catch (IOException e) {
            throw new IllegalStateException("test fixture setup failed", e);
        }
    }
}
