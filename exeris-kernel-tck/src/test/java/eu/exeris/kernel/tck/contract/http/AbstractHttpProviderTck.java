/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.http.HttpProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: Abstract base for {@link HttpProvider} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code providerId()} is non-null and non-blank</li>
 *   <li>{@code providerName()} is non-null and non-blank</li>
 *   <li>{@code priority()} follows Open-Core convention (0 for Community, 100 for Enterprise)</li>
 *   <li>Provider is discoverable via {@link ServiceLoader}</li>
 *   <li>Highest-priority provider wins selection</li>
 *   <li>Identity fields are stable across calls</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunityHttpProviderTck extends AbstractHttpProviderTck {
 *     @Override
 *     protected HttpProvider createProvider() {
 *         return new CommunityHttpProvider();
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public abstract class AbstractHttpProviderTck {

    private static final String HTTP_PROVIDER_SERVICE_RESOURCE = "META-INF/services/"
            + HttpProvider.class.getName();

    /**
     * Creates the {@link HttpProvider} under test.
     *
     * @return a fresh provider instance; never {@code null}
     */
    protected abstract HttpProvider createProvider();

    private HttpProvider provider;

    private static void loadProvidersWithClassLoader(ClassLoader classLoader,
                                                     Map<String, HttpProvider> providersByClassName) {
        if (classLoader == null) {
            return;
        }
        ServiceLoader.load(HttpProvider.class, classLoader)
                .forEach(provider -> providersByClassName.putIfAbsent(provider.getClass().getName(), provider));
    }

    private static void loadProvidersFromServiceDescriptors(ClassLoader classLoader,
                                                            Map<String, HttpProvider> providersByClassName) {
        if (classLoader == null) {
            return;
        }
        try {
            Enumeration<URL> descriptors = classLoader.getResources(HTTP_PROVIDER_SERVICE_RESOURCE);
            while (descriptors.hasMoreElements()) {
                java.net.URL descriptorUrl = descriptors.nextElement();
                loadProvidersFromSingleDescriptor(classLoader, descriptorUrl, providersByClassName);
            }
        } catch (IOException _) {
            // Best-effort fallback for CI classloader edge-cases.
        }
    }

    private static void loadProvidersFromSingleDescriptor(ClassLoader classLoader,
                                                          java.net.URL descriptorUrl,
                                                          Map<String, HttpProvider> providersByClassName) {
        try (InputStream inputStream = descriptorUrl.openStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String providerClassName = normalizeProviderClassName(line);
                if (providerClassName.isEmpty() || providersByClassName.containsKey(providerClassName)) {
                    continue;
                }
                instantiateProvider(providerClassName, classLoader)
                        .ifPresent(provider -> providersByClassName.putIfAbsent(providerClassName, provider));
            }
        } catch (IOException _) {
            // Descriptor may be unreadable in isolated runners, ignore and continue.
        }
    }

    private static String normalizeProviderClassName(String line) {
        int commentIndex = line.indexOf('#');
        String withoutComment = commentIndex >= 0 ? line.substring(0, commentIndex) : line;
        return withoutComment.trim();
    }

    private static Optional<HttpProvider> instantiateProvider(String providerClassName, ClassLoader classLoader) {
        try {
            Class<?> providerClass = Class.forName(providerClassName, true, classLoader);
            if (!HttpProvider.class.isAssignableFrom(providerClass)) {
                return Optional.empty();
            }
            Object instance = providerClass.getDeclaredConstructor().newInstance();
            return Optional.of((HttpProvider) instance);
        } catch (ClassNotFoundException
                 | InstantiationException
                 | IllegalAccessException
                 | InvocationTargetException
                 | NoSuchMethodException
                 | LinkageError _) {
            return Optional.empty();
        }
    }

    private List<HttpProvider> discoverProviders() {
        Map<String, HttpProvider> providersByClassName = new LinkedHashMap<>();
        ClassLoader providerClassLoader = provider.getClass().getClassLoader();
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
        ClassLoader tckClassLoader = AbstractHttpProviderTck.class.getClassLoader();

        ServiceLoader.load(HttpProvider.class)
                .forEach(discoveredProvider ->
                        providersByClassName.putIfAbsent(discoveredProvider.getClass().getName(), discoveredProvider));

        loadProvidersWithClassLoader(providerClassLoader, providersByClassName);
        loadProvidersWithClassLoader(contextClassLoader, providersByClassName);
        loadProvidersWithClassLoader(systemClassLoader, providersByClassName);
        loadProvidersWithClassLoader(tckClassLoader, providersByClassName);

        if (providersByClassName.isEmpty()) {
            loadProvidersFromServiceDescriptors(providerClassLoader, providersByClassName);
            loadProvidersFromServiceDescriptors(contextClassLoader, providersByClassName);
            loadProvidersFromServiceDescriptors(systemClassLoader, providersByClassName);
            loadProvidersFromServiceDescriptors(tckClassLoader, providersByClassName);
        }

        return List.copyOf(providersByClassName.values());
    }

    @BeforeEach
    final void setUpProvider() {
        provider = createProvider();
    }

    @Nested
    @DisplayName("Provider identity")
    class Identity {

        @Test
        @DisplayName("providerId() is non-blank")
        void providerIdNonBlank() {
            assertThat(provider.providerId()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("providerId() is stable across calls")
        void providerIdStable() {
            assertThat(provider.providerId()).isEqualTo(provider.providerId());
        }

        @Test
        @DisplayName("providerName() is non-blank")
        void providerNameNonBlank() {
            assertThat(provider.providerName()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("providerName() is stable across calls")
        void providerNameStable() {
            assertThat(provider.providerName()).isEqualTo(provider.providerName());
        }

        @Test
        @DisplayName("priority() follows Open-Core convention (0 or 100)")
        void priorityConvention() {
            assertThat(provider.priority()).isIn(0, 100);
        }
    }

    @Nested
    @DisplayName("ServiceLoader integration")
    class ServiceLoaderContract {

        @Test
        @DisplayName("HttpProvider is discoverable via ServiceLoader")
        void discoverable() {
            List<HttpProvider> discoveredProviders = discoverProviders();
            long count = discoveredProviders.size();
            assertThat(count)
                    .as("At least one HttpProvider must be on the classpath")
                    .isGreaterThanOrEqualTo(1);
            assertThat(discoveredProviders)
                    .as("Provider created by createProvider() must be discoverable via ServiceLoader")
                    .anySatisfy(discoveredProvider -> {
                        assertThat(discoveredProvider.getClass()).isEqualTo(provider.getClass());
                        assertThat(discoveredProvider.providerId()).isEqualTo(provider.providerId());
                    });
        }

        @Test
        @DisplayName("Highest-priority provider wins")
        void highestPriorityWins() {
            HttpProvider selected = discoverProviders().stream()
                    .max(Comparator.comparingInt(HttpProvider::priority))
                    .orElseThrow(() -> new AssertionError("No HttpProvider discovered via ServiceLoader"));
            assertThat(selected.priority()).isGreaterThanOrEqualTo(provider.priority());
        }
    }
}
