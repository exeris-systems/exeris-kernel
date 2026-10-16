/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.tck.contract.nativeimage.DeclarationSite;
import eu.exeris.kernel.tck.contract.nativeimage.DeclarationSiteScanner;
import eu.exeris.kernel.tck.contract.nativeimage.DeclarationSiteScanner.AllowlistEntry;
import eu.exeris.kernel.tck.contract.nativeimage.ReachabilityMetadata;
import eu.exeris.kernel.tck.contract.nativeimage.ReachabilityMetadataGuard;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reachability metadata verification guard for {@code exeris-kernel-community-kafka}.
 *
 * <p>Enforces complete declaration-site allowlisting, confines {@link Properties} construction to
 * designated static builder methods, derives reflective type models from Kafka configuration definitions,
 * asserts classpath resolution of by-name types, and verifies exact byte-for-byte matching between
 * the committed {@code reachability-metadata.json} golden file and the synthesized runtime model.
 *
 * @since 0.13
 */
@DisplayName("Kafka reachability metadata and declaration-site guard")
class KafkaReachabilityMetadataTest {

    private static final String KAFKA_EVENT_ENGINE_CLASS =
            "eu.exeris.kernel.community.kafka.KafkaEventEngine";

    /**
     * Types registered for reflection or JMX introspection in kafka-clients.
     *
     * <p>Each entry documents the upstream kafka-clients mechanism:
     * <ul>
     *   <li>{@code AppInfoParser$AppInfo}: Built directly by
     *       {@link org.apache.kafka.common.utils.AppInfoParser#registerAppInfo(String, String, org.apache.kafka.common.metrics.Metrics, long)}
     *       and introspected as an MBean via {@code MBeanServer.registerMBean}.</li>
     *   <li>{@code AppInfoParser$AppInfoMBean}: The standard MBean interface for client app info, reflectively
     *       inspected by the platform MBeanServer during MBean registration, whose getters are invoked
     *       reflectively on attribute reads.</li>
     * </ul>
     */
    private static final List<String> MEASURED_BY_NAME_TYPES = List.of(
            // AppInfoParser.registerAppInfo builds AppInfo directly; MBeanServer.registerMBean introspects it
            "org.apache.kafka.common.utils.AppInfoParser$AppInfo",
            // AppInfoParser$AppInfoMBean introspected by JMX; getters called reflectively on attribute reads
            "org.apache.kafka.common.utils.AppInfoParser$AppInfoMBean"
    );

    /**
     * Shaded Google Protobuf {@code ExtensionRegistry} type in kafka-clients 4.3.1.
     *
     * <p>Loaded reflectively by {@code org.apache.kafka.shaded.com.google.protobuf.ExtensionRegistryFactory#reflectExtensionRegistry}
     * via {@code Class.forName("org.apache.kafka.shaded.com.google.protobuf.ExtensionRegistry")}.
     */
    private static final String SHADED_EXTENSION_REGISTRY_CLASS =
            "org.apache.kafka.shaded.com.google.protobuf.ExtensionRegistry";

    /**
     * Shaded Google Protobuf {@code getEmptyRegistry} method name.
     *
     * <p>Invoked reflectively by {@code org.apache.kafka.shaded.com.google.protobuf.ExtensionRegistryFactory#createEmpty}
     * via {@code getDeclaredMethod("getEmptyRegistry")}.
     */
    private static final String GET_EMPTY_REGISTRY_METHOD = "getEmptyRegistry";

    /**
     * Client version properties resource loaded by {@link org.apache.kafka.common.utils.AppInfoParser}.
     *
     * <p>Loaded in {@code AppInfoParser}'s static initializer via
     * {@code AppInfoParser.class.getResourceAsStream("/kafka/kafka-version.properties")} to populate
     * client version and commit ID metadata.
     */
    private static final String KAFKA_VERSION_RESOURCE = "kafka/kafka-version.properties";

    /**
     * Allowlist entries for declaration sites in {@code exeris-kernel-community-kafka}.
     *
     * <p>The Kafka module delegates network communication to {@code kafka-clients} and contains zero
     * direct foreign function, memory, dynamic resource, or reflection sites.
     */
    private static final List<AllowlistEntry> ALLOWLIST = List.of();

    private static final Set<String> ALLOWED_PROPERTIES_BUILDER_SITES = Set.of(
            "eu.exeris.kernel.community.kafka.KafkaEventEngine#producerProperties",
            "eu.exeris.kernel.community.kafka.KafkaEventEngine#consumerProperties",
            "eu.exeris.kernel.community.kafka.KafkaEventLog#consumerProps"
    );

    @Test
    @DisplayName("GraalVM SDK classes are absent from the test classpath")
    void noGraalVmOnTestClasspath() {
        assertThat(getClass().getClassLoader().getResource("org/graalvm/nativeimage/ImageInfo.class"))
                .as("org.graalvm types must not be present on the classpath")
                .isNull();
    }

    @Test
    @DisplayName("All declaration sites in Kafka match allowlist dispositions with no stale entries")
    void allowlistMatchesAllSites() throws IOException {
        Class<?> anchor = resolveClass(KAFKA_EVENT_ENGINE_CLASS);
        DeclarationSiteScanner.ScanResult result = DeclarationSiteScanner.scanAll(anchor);
        DeclarationSiteScanner.assertAllowlistMatches(result.sites(), ALLOWLIST);
    }

    @Test
    @DisplayName("Properties instances are constructed only in designated builder methods")
    void propertiesInstantiatedOnlyInDesignatedBuilders() throws IOException {
        Class<?> anchor = resolveClass(KAFKA_EVENT_ENGINE_CLASS);
        Path classesDir = DeclarationSiteScanner.classesDirectory(anchor);
        List<String> violations = new ArrayList<>();
        int clientConstructorCount = 0;

        try (Stream<Path> stream = Files.walk(classesDir)) {
            List<Path> classFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().equals("module-info.class"))
                    .toList();

            for (Path classFile : classFiles) {
                ClassModel cm = ClassFile.of().parse(classFile);
                String className = cm.thisClass().asInternalName().replace('/', '.');
                for (MethodModel mm : cm.methods()) {
                    String methodName = mm.methodName().stringValue();
                    mm.code().ifPresent(code -> {
                        for (CodeElement ce : code) {
                            if (ce instanceof NewObjectInstruction noi
                                    && "java/util/Properties".equals(noi.className().asInternalName())) {
                                String siteKey = className + "#" + methodName;
                                if (!ALLOWED_PROPERTIES_BUILDER_SITES.contains(siteKey)) {
                                    violations.add(siteKey);
                                }
                            }
                            if (ce instanceof InvokeInstruction inv
                                    && "<init>".equals(inv.name().stringValue())) {
                                String owner = inv.owner().asInternalName();
                                if ("org/apache/kafka/clients/producer/KafkaProducer".equals(owner)
                                        || "org/apache/kafka/clients/consumer/KafkaConsumer".equals(owner)) {
                                    String descriptor = inv.type().stringValue();
                                    if (!"(Ljava/util/Properties;)V".equals(descriptor)) {
                                        violations.add(className + "#" + methodName
                                                + " invokes " + owner + ".<init> with descriptor " + descriptor);
                                    }
                                }
                            }
                        }
                    });
                }
                for (PoolEntry entry : cm.constantPool()) {
                    if (entry instanceof MethodRefEntry mre
                            && "<init>".equals(mre.name().stringValue())) {
                        String owner = mre.owner().asInternalName();
                        if ("org/apache/kafka/clients/producer/KafkaProducer".equals(owner)
                                || "org/apache/kafka/clients/consumer/KafkaConsumer".equals(owner)) {
                            clientConstructorCount++;
                            String descriptor = mre.type().stringValue();
                            if (!"(Ljava/util/Properties;)V".equals(descriptor)) {
                                violations.add(className + " references " + owner
                                        + ".<init> with descriptor " + descriptor);
                            }
                        }
                    }
                }
            }
        }

        assertThat(clientConstructorCount)
                .as("Expected Kafka client constructor invocations/references in main classes")
                .isGreaterThan(0);
        assertThat(violations)
                .as("Properties and client constructor violations: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("Types, methods, and resources in MEASURED_BY_NAME table resolve on classpath")
    void measuredByNameTableResolves() throws ClassNotFoundException, NoSuchMethodException {
        ClassLoader loader = getClass().getClassLoader();
        for (String typeName : MEASURED_BY_NAME_TYPES) {
            Class.forName(typeName, false, loader);
        }

        Class<?> mbeanClass = Class.forName("org.apache.kafka.common.utils.AppInfoParser$AppInfoMBean", false, loader);
        mbeanClass.getDeclaredMethod("getVersion");
        mbeanClass.getDeclaredMethod("getCommitId");
        mbeanClass.getDeclaredMethod("getStartTimeMs");

        Class<?> extRegistry = Class.forName(SHADED_EXTENSION_REGISTRY_CLASS, false, loader);
        extRegistry.getDeclaredMethod(GET_EMPTY_REGISTRY_METHOD);

        assertThat(loader.getResource(KAFKA_VERSION_RESOURCE))
                .as("Resource %s must resolve on classpath", KAFKA_VERSION_RESOURCE)
                .isNotNull();
    }

    @Test
    @DisplayName("Committed reachability-metadata.json matches expected model generated from declaration sites")
    void committedMetadataMatchesGeneratedModel() throws IOException, ReflectiveOperationException {
        Class<?> anchor = resolveClass(KAFKA_EVENT_ENGINE_CLASS);
        ReachabilityMetadata model = buildExpectedModel(anchor);
        ReachabilityMetadataGuard.assertCommittedMatches(anchor, "exeris-kernel-community-kafka", model);
    }

    static ReachabilityMetadata buildExpectedModel(Class<?> anchor)
            throws IOException, ReflectiveOperationException {
        ReachabilityMetadata.Builder builder = ReachabilityMetadata.builder();
        ClassLoader loader = anchor.getClassLoader();

        // 1. ConfigDef-driven derivation for producerProperties
        KafkaEventConfig config = KafkaEventConfig.defaults("localhost:9092", "metadata-guard");
        Properties producerProps = KafkaEventEngine.producerProperties(config);
        Set<String> listDerivedTypes = new TreeSet<>();
        listDerivedTypes.addAll(deriveConfig(ProducerConfig.configDef(), producerProps, builder, loader));

        // 2. ConfigDef-driven derivation for consumerProperties
        Properties consumerProps = KafkaEventEngine.consumerProperties(config);
        listDerivedTypes.addAll(deriveConfig(ConsumerConfig.configDef(), consumerProps, builder, loader));

        // 3. ConfigDef-driven derivation for event-log consumerProps
        Properties logConsumerProps = KafkaEventLog.consumerProps(config);
        listDerivedTypes.addAll(deriveConfig(ConsumerConfig.configDef(), logConsumerProps, builder, loader));

        // 4. Verify LIST path produced expected assignor and reporter classes
        assertThat(listDerivedTypes)
                .as("LIST path derivation must produce assignor and reporter classes")
                .contains(
                        "org.apache.kafka.clients.consumer.RangeAssignor",
                        "org.apache.kafka.clients.consumer.CooperativeStickyAssignor",
                        "org.apache.kafka.common.metrics.JmxReporter"
                );

        // 5. Types and methods measured by name
        for (String typeName : MEASURED_BY_NAME_TYPES) {
            builder.registerType(typeName);
        }
        builder.registerMethod("org.apache.kafka.common.utils.AppInfoParser$AppInfoMBean", "getVersion", List.of());
        builder.registerMethod("org.apache.kafka.common.utils.AppInfoParser$AppInfoMBean", "getCommitId", List.of());
        builder.registerMethod("org.apache.kafka.common.utils.AppInfoParser$AppInfoMBean", "getStartTimeMs", List.of());
        builder.registerMethod(SHADED_EXTENSION_REGISTRY_CLASS, GET_EMPTY_REGISTRY_METHOD, List.of());
        builder.registerResource(KAFKA_VERSION_RESOURCE);

        // 6. JFR events closure from KafkaJfrEventCatalogue
        List<String> eventNames = new ArrayList<>();
        eventNames.addAll(KafkaJfrEventCatalogue.catalogue().allHotPath());
        eventNames.addAll(KafkaJfrEventCatalogue.catalogue().deliberatelyCold());
        List<String> jfrClosure = ReachabilityMetadataGuard.jfrClosure(eventNames, loader);
        for (String typeName : jfrClosure) {
            builder.registerType(typeName);
        }

        return builder.build();
    }

    private static Set<String> deriveConfig(ConfigDef configDef, Properties props,
                                            ReachabilityMetadata.Builder builder, ClassLoader loader) {
        Map<String, Object> parsed = configDef.parse(props);
        Map<String, ConfigDef.ConfigKey> configKeys = configDef.configKeys();
        Set<String> listDerivedTypes = new TreeSet<>();

        for (Map.Entry<String, ConfigDef.ConfigKey> entry : configKeys.entrySet()) {
            ConfigDef.ConfigKey key = entry.getValue();
            Object value = parsed.get(key.name);
            if (key.type == ConfigDef.Type.CLASS && value instanceof Class<?> clazz) {
                builder.registerConstructor(clazz.getName(), List.of());
            } else if (key.type == ConfigDef.Type.LIST && value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s && s.contains(".")) {
                        try {
                            Class<?> clazz = Class.forName(s, false, loader);
                            builder.registerConstructor(clazz.getName(), List.of());
                            listDerivedTypes.add(clazz.getName());
                        } catch (ClassNotFoundException _) {
                            // Non-class dotted string in list (e.g. protocol or cipher suite names).
                        }
                    } else if (item instanceof Class<?> clazz) {
                        builder.registerConstructor(clazz.getName(), List.of());
                        listDerivedTypes.add(clazz.getName());
                    }
                }
            }
        }
        return listDerivedTypes;
    }

    private static Class<?> resolveClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ex) {
            throw new AssertionError("Failed to load required class: " + className, ex);
        }
    }
}
