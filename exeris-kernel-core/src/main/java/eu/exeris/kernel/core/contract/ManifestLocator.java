/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.contract.ContractBreachException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Finds and reads the license manifest for {@link ContractBootstrapStep}: the configured path when
 * {@value ContractBootstrapStep#MANIFEST_PATH_KEY} is bound, else {@code license-manifest.json} in the working
 * directory, else on the classpath — bounded to 64 KiB, and never falling through past a configured path.
 * The classpath holds at most one manifest: a second one makes the manifest ambiguous, so it is refused
 * rather than resolved by classpath order.
 */
final class ManifestLocator {

    /** File name of the manifest in the working directory and on the classpath. */
    /* default */ static final String DEFAULT_MANIFEST_FILENAME = "license-manifest.json";
    private static final String CLASSPATH_SOURCE = "classpath:" + DEFAULT_MANIFEST_FILENAME;
    /** A v1 manifest is a few kilobytes; anything larger is refused before it is read into the heap. */
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    /** More than one distinct manifest makes the contract depend on classpath order, so it is refused. */
    private static final int MAX_CLASSPATH_MANIFESTS = 1;

    private ManifestLocator() {}

    /**
     * A manifest's content and where it was read from.
     *
     * @param bytes  manifest content
     * @param source file path, or {@code classpath:license-manifest.json}
     */
    /* default */ record ManifestSource(byte[] bytes, String source) {

        @Override
        public boolean equals(Object other) {
            return other instanceof ManifestSource that
                    && Arrays.equals(bytes, that.bytes)
                    && source.equals(that.source);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(bytes) + source.hashCode();
        }

        @Override
        public String toString() {
            return "ManifestSource[source=" + source + ", bytes=" + bytes.length + "]";
        }
    }

    /**
     * Reads the manifest from the configured path, else the default file, else the classpath.
     *
     * @param configuredPath value of {@value ContractBootstrapStep#MANIFEST_PATH_KEY}, if bound
     * @param defaultFile    file consulted when no path is configured
     * @param classLoader    loader whose {@code license-manifest.json} resource is consulted last; {@code null}
     *                       means the system class loader
     * @return the manifest and its source, or {@code null} when no path is configured and no default exists
     * @throws ContractBreachException with EX-LIC-0001 if a configured path is blank, a manifest exists
     *                                 but cannot be read or is larger than 64 KiB, or the classpath holds
     *                                 more than one manifest
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
        // A null loader means the system class loader, as it does to the ServiceLoader that discovers
        // the requirements; a thread's context class loader may be null.
        ClassLoader loader = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
        List<URL> found = classpathManifests(loader);
        if (found.isEmpty()) {
            return null;
        }
        if (found.size() > MAX_CLASSPATH_MANIFESTS) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0001,
                    "More than one " + DEFAULT_MANIFEST_FILENAME + " is on the classpath: "
                            + found.stream().map(URL::toExternalForm).collect(Collectors.joining(", "))
                            + "; bind " + ContractBootstrapStep.MANIFEST_PATH_KEY + " to the one to use",
                    CLASSPATH_SOURCE);
        }
        try (InputStream stream = found.get(0).openStream()) {
            return new ManifestSource(readBounded(stream, CLASSPATH_SOURCE), CLASSPATH_SOURCE);
        } catch (IOException e) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0001,
                    "Cannot read license manifest from the classpath",
                    e,
                    CLASSPATH_SOURCE);
        }
    }

    /**
     * Every distinct manifest URL the loader sees. A loader that delegates to a parent can report the
     * same resource once per loader in the chain; counting it twice would refuse a classpath that holds
     * one manifest.
     */
    private static List<URL> classpathManifests(ClassLoader loader) {
        Map<String, URL> byForm = new LinkedHashMap<>();
        try {
            Enumeration<URL> urls = loader.getResources(DEFAULT_MANIFEST_FILENAME);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                byForm.putIfAbsent(url.toExternalForm(), url);
            }
        } catch (IOException e) {
            throw new ContractBreachException(
                    KernelErrorCodes.EX_LIC_0001,
                    "Cannot enumerate license manifests on the classpath",
                    e,
                    CLASSPATH_SOURCE);
        }
        return List.copyOf(byForm.values());
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
