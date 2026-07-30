/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.storage.blob.BlobStorageConfig;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import eu.exeris.kernel.tck.contract.storage.AbstractBlobStorageTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Community filesystem binding of {@link AbstractBlobStorageTck}.
 *
 * <p>The store root is a per-test temporary directory, so tenant-isolation cases start from an empty
 * namespace rather than inheriting state from an earlier test.
 */
@DisplayName("Community: Filesystem BlobStore TCK")
class CommunityFilesystemBlobStorageTckTest extends AbstractBlobStorageTck {

    @TempDir
    private Path storeRoot;

    @Override
    protected BlobStore createStore() {
        return new CommunityFilesystemBlobStorageProvider()
                .createStore(BlobStorageConfig.atLocation(storeRoot.toString()));
    }

    @Override
    protected MemoryProvider createMemoryProvider() {
        return new CommunityMemoryProvider();
    }
}
