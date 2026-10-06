/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds and reads the license manifest for {@link ContractBootstrapStep}: the configured path when
 * {@value ContractBootstrapStep#MANIFEST_PATH_KEY} is bound, else {@code license-manifest.json} in the working
 * directory, else on the classpath — bounded to 64 KiB, and never falling through past a configured path.
 */
final class ManifestLocator {

    /** File name of the manifest in the working directory and on the classpath. */
    /* default */ static final String DEFAULT_MANIFEST_FILENAME = "license-manifest.json";
    private static final String CLASSPATH_SOURCE = "classpath:" + DEFAULT_MANIFEST_FILENAME;
    /** A v1 manifest is a few kilobytes; anything larger is refused before it is read into the heap. */
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;

    private ManifestLocator() {}

    /**
     * A manifest's content and where it was read from.
     *
     * @param bytes  manifest content
     * @param source file path, or {@code classpath:license-manifest.json}
     */
    /* default */ record ManifestSource(byte[] bytes, String source) {
    }

    /**
     * Reads the manifest from the configured path, else the default file, else the classpath.
     *
     * @param configuredPath value of {@value ContractBootstrapStep#MANIFEST_PATH_KEY}, if bound
     * @param defaultFile    file consulted when no path is configured
     * @param classLoader    loader whose {@code license-manifest.json} resource is consulted last
     * @return the manifest and its source, or {@code null} when no path is configured and no default exists
     * @throws ContractBreachException with EX-LIC-0001 if a configured path is blank, or a manifest exists
     *                                 but cannot be read or is larger than 64 KiB
     */
    /* default */ static ManifestSource resolve(
            Optional<String> configuredPath, Path defaultFile, ClassLoader classLoader) {
        if (configuredPath.isPresent()) {
            String configured = configuredPath.get().strip();
            if (configured.isEmpty()) {
                throw new ContractBreachException(
                        KernelErrorCodes.EX_LIC_0001,
                        ContractBootstrapStep.MANIFEST_PATH_KEY + " is bound to a blank value",
                        ContractBootstrapStep.MANIFEST_PATH_KEY);
            }
            Path path = Path.of(configured);
            if (!Files.isRegularFile(path)) {
                throw new ContractBreachException(
                        KernelErrorCodes.EX_LIC_0001,
                        "Configured license manifest is not a readable file: " + path,
                        path.toString());
            }
            return readFile(path);
        }
        if (Files.isRegularFile(defaultFile)) {
            return readFile(defaultFile);
        }
        return readClasspathResource(classLoader);
    }

    private static ManifestSource readFile(Path path) {
        try (InputStream stream = Files.newInputStream(path)) {
            return new ManifestSource(readBounded(stream, path.toString()), path.toString());
        } catch (IOException e) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0001, "Cannot read license manifest: " + path, e, path.toString());
        }
    }

    private static ManifestSource readClasspathResource(ClassLoader classLoader) {
        try (InputStream stream = classLoader.getResourceAsStream(DEFAULT_MANIFEST_FILENAME)) {
            if (stream == null) {
                return null;
            }
            return new ManifestSource(readBounded(stream, CLASSPATH_SOURCE), CLASSPATH_SOURCE);
        } catch (IOException e) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0001,
                    "Cannot read license manifest from the classpath",
                    e,
                    CLASSPATH_SOURCE);
        }
    }

    private static byte[] readBounded(InputStream stream, String source) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_MANIFEST_BYTES + 1);
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0001,
                    "License manifest exceeds " + MAX_MANIFEST_BYTES + " bytes: " + source,
                    source);
        }
        return bytes;
    }
}
