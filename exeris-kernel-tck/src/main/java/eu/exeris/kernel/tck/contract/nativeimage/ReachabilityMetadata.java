/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.nativeimage;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Model and canonical JSON renderer for GraalVM reachability metadata ({@code reachability-metadata.json}).
 *
 * <p>Enforces deterministic canonical output conforming to the GraalVM metadata schema:
 * top-level keys ordered {@code reflection}, {@code resources}, {@code foreign}; two-space indentation,
 * LF line endings, and a trailing newline. Methods are ordered by name, then parameter count, then parameter
 * types element-by-element lexicographically. Empty models render as {@code "{}\n"}.
 *
 * @since 0.13
 */
public final class ReachabilityMetadata {

    private final Map<String, ReflectionEntry> reflection;
    private final Set<String> resources;
    private final Set<DowncallRef> downcalls;
    private final Set<DirectUpcallRef> directUpcalls;

    private ReachabilityMetadata(Map<String, ReflectionEntry> reflection,
                                 Set<String> resources,
                                 Set<DowncallRef> downcalls,
                                 Set<DirectUpcallRef> directUpcalls) {
        this.reflection = reflection;
        this.resources = resources;
        this.downcalls = downcalls;
        this.directUpcalls = directUpcalls;
    }

    /**
     * Creates an empty reachability metadata model.
     *
     * @return an empty model
     */
    public static ReachabilityMetadata empty() {
        return new ReachabilityMetadata(Collections.emptyMap(), Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet());
    }

    /**
     * Creates a new builder for constructing reachability metadata.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Maps an FFM memory layout to its GraalVM native-image C type identifier.
     *
     * @param layout memory layout
     * @return canonical type identifier (e.g. {@code "jint"}, {@code "jlong"}, {@code "void*"})
     * @throws IllegalArgumentException if the layout is a {@link GroupLayout} or unsupported
     */
    public static String mapLayout(MemoryLayout layout) {
        Objects.requireNonNull(layout, "layout");
        if (layout instanceof ValueLayout.OfInt) {
            return "jint";
        }
        if (layout instanceof ValueLayout.OfLong) {
            return "jlong";
        }
        if (layout instanceof AddressLayout) {
            return "void*";
        }
        if (layout instanceof ValueLayout.OfByte) {
            return "jbyte";
        }
        if (layout instanceof ValueLayout.OfShort) {
            return "jshort";
        }
        if (layout instanceof ValueLayout.OfChar) {
            return "jchar";
        }
        if (layout instanceof ValueLayout.OfFloat) {
            return "jfloat";
        }
        if (layout instanceof ValueLayout.OfDouble) {
            return "jdouble";
        }
        if (layout instanceof ValueLayout.OfBoolean) {
            return "jboolean";
        }
        if (layout instanceof GroupLayout) {
            throw new IllegalArgumentException("Group layout is not supported in native-image metadata: " + layout);
        }
        throw new IllegalArgumentException("Unsupported layout for native-image metadata: " + layout);
    }

    /**
     * Maps a function descriptor's return layout to its canonical GraalVM native-image return type.
     *
     * @param descriptor function descriptor
     * @return {@code "void"} if no return layout is present, or the mapped layout name
     */
    public static String mapReturnLayout(FunctionDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return descriptor.returnLayout().map(ReachabilityMetadata::mapLayout).orElse("void");
    }

    /**
     * Maps a function descriptor's argument layouts to a list of canonical GraalVM native-image parameter types.
     *
     * @param descriptor function descriptor
     * @return list of canonical parameter type names
     */
    public static List<String> mapArgumentLayouts(FunctionDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        List<String> args = new ArrayList<>();
        for (MemoryLayout layout : descriptor.argumentLayouts()) {
            args.add(mapLayout(layout));
        }
        return args;
    }

    /**
     * Renders this metadata into canonical JSON formatted with two-space indentation, LF endings,
     * and a trailing newline.
     *
     * @return the rendered JSON string
     */
    public String render() {
        return ReachabilityMetadataRenderer.render(reflection, resources, downcalls, directUpcalls);
    }

    static final class ReflectionEntry {
        private final String type;
        private final Set<MethodRef> methods = new TreeSet<>();
        private final Set<String> fields = new TreeSet<>();
        private boolean allDeclaredFields;

        ReflectionEntry(String type) {
            this.type = type;
        }

        String type() {
            return type;
        }

        Set<MethodRef> methods() {
            return methods;
        }

        Set<String> fields() {
            return fields;
        }

        boolean allDeclaredFields() {
            return allDeclaredFields;
        }
    }

    /**
     * Represents a reflected method reference.
     *
     * <p>Ordered by method name, then parameter count, then parameter types element-by-element lexicographically.
     *
     * @param name           method name
     * @param parameterTypes parameter type names
     */
    record MethodRef(String name, List<String> parameterTypes) implements Comparable<MethodRef> {
        @Override
        public int compareTo(MethodRef other) {
            int cmp = name.compareTo(other.name);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(parameterTypes.size(), other.parameterTypes.size());
            if (cmp != 0) {
                return cmp;
            }
            for (int i = 0; i < parameterTypes.size(); i++) {
                cmp = parameterTypes.get(i).compareTo(other.parameterTypes.get(i));
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }
    }

    record DowncallRef(
            String returnType,
            List<String> parameterTypes,
            Map<String, Object> options
    ) implements Comparable<DowncallRef> {

        DowncallRef(String returnType, List<String> parameterTypes) {
            this(returnType, parameterTypes, Collections.emptyMap());
        }

        DowncallRef {
            Objects.requireNonNull(returnType, "returnType");
            Objects.requireNonNull(parameterTypes, "parameterTypes");
            options = options == null || options.isEmpty()
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new TreeMap<>(options));
        }

        @Override
        public int compareTo(DowncallRef other) {
            int cmp = returnType.compareTo(other.returnType);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(parameterTypes.size(), other.parameterTypes.size());
            if (cmp != 0) {
                return cmp;
            }
            for (int i = 0; i < parameterTypes.size(); i++) {
                cmp = parameterTypes.get(i).compareTo(other.parameterTypes.get(i));
                if (cmp != 0) {
                    return cmp;
                }
            }
            cmp = Integer.compare(options.size(), other.options.size());
            if (cmp != 0) {
                return cmp;
            }
            Iterator<Map.Entry<String, Object>> thisIt = options.entrySet().iterator();
            Iterator<Map.Entry<String, Object>> otherIt = other.options.entrySet().iterator();
            while (thisIt.hasNext()) {
                Map.Entry<String, Object> e1 = thisIt.next();
                Map.Entry<String, Object> e2 = otherIt.next();
                cmp = e1.getKey().compareTo(e2.getKey());
                if (cmp != 0) {
                    return cmp;
                }
                cmp = String.valueOf(e1.getValue()).compareTo(String.valueOf(e2.getValue()));
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }
    }

    record DirectUpcallRef(
            String className,
            String method,
            String returnType,
            List<String> parameterTypes
    ) implements Comparable<DirectUpcallRef> {
        @Override
        public int compareTo(DirectUpcallRef other) {
            int cmp = className.compareTo(other.className);
            if (cmp != 0) {
                return cmp;
            }
            cmp = method.compareTo(other.method);
            if (cmp != 0) {
                return cmp;
            }
            cmp = returnType.compareTo(other.returnType);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(parameterTypes.size(), other.parameterTypes.size());
            if (cmp != 0) {
                return cmp;
            }
            for (int i = 0; i < parameterTypes.size(); i++) {
                cmp = parameterTypes.get(i).compareTo(other.parameterTypes.get(i));
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }
    }

    /**
     * Builder for constructing {@link ReachabilityMetadata} with merge-per-type semantics.
     *
     * @since 0.13
     */
    public static final class Builder {

        private final Map<String, ReflectionEntry> reflection = new TreeMap<>();
        private final Set<String> resources = new TreeSet<>();
        private final Set<DowncallRef> downcalls = new TreeSet<>();
        private final Set<DirectUpcallRef> directUpcalls = new TreeSet<>();

        private Builder() {
        }

        private ReflectionEntry getOrCreateEntry(String typeName) {
            Objects.requireNonNull(typeName, "typeName");
            return reflection.computeIfAbsent(typeName, ReflectionEntry::new);
        }

        /**
         * Registers reflective access to a type without specifying individual methods or fields.
         *
         * @param typeName binary class name
         * @return this builder
         */
        public Builder registerType(String typeName) {
            getOrCreateEntry(typeName);
            return this;
        }

        /**
         * Registers access to a declared field of the given type.
         *
         * @param typeName  binary class name
         * @param fieldName field name
         * @return this builder
         */
        public Builder registerField(String typeName, String fieldName) {
            Objects.requireNonNull(fieldName, "fieldName");
            getOrCreateEntry(typeName).fields.add(fieldName);
            return this;
        }

        /**
         * Registers reflective access to all declared fields of the given type.
         *
         * @param typeName binary class name
         * @return this builder
         */
        public Builder registerAllDeclaredFields(String typeName) {
            getOrCreateEntry(typeName).allDeclaredFields = true;
            return this;
        }

        /**
         * Registers reflective invocation of a method on the given type.
         *
         * @param typeName       binary class name
         * @param methodName     method name
         * @param parameterTypes list of parameter type names
         * @return this builder
         */
        public Builder registerMethod(String typeName, String methodName, List<String> parameterTypes) {
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(parameterTypes, "parameterTypes");
            getOrCreateEntry(typeName).methods.add(new MethodRef(methodName, List.copyOf(parameterTypes)));
            return this;
        }

        /**
         * Registers reflective invocation of a method on the given type.
         *
         * @param typeName       binary class name
         * @param methodName     method name
         * @param parameterTypes array of parameter type names
         * @return this builder
         */
        public Builder registerMethod(String typeName, String methodName, String... parameterTypes) {
            return registerMethod(typeName, methodName, Arrays.asList(parameterTypes));
        }

        /**
         * Registers reflective invocation of a constructor on the given type.
         *
         * @param typeName       binary class name
         * @param parameterTypes list of constructor parameter type names
         * @return this builder
         */
        public Builder registerConstructor(String typeName, List<String> parameterTypes) {
            return registerMethod(typeName, "<init>", parameterTypes);
        }

        /**
         * Registers reflective invocation of a constructor on the given type.
         *
         * @param typeName       binary class name
         * @param parameterTypes array of constructor parameter type names
         * @return this builder
         */
        public Builder registerConstructor(String typeName, String... parameterTypes) {
            return registerMethod(typeName, "<init>", Arrays.asList(parameterTypes));
        }

        /**
         * Registers an exact classpath resource path.
         *
         * @param glob exact resource path without wildcards or leading slash
         * @return this builder
         */
        public Builder registerResource(String glob) {
            Objects.requireNonNull(glob, "glob");
            resources.add(glob);
            return this;
        }

        /**
         * Registers an FFM downcall descriptor.
         *
         * @param descriptor function descriptor
         * @return this builder
         */
        public Builder registerDowncall(FunctionDescriptor descriptor) {
            return registerDowncall(mapReturnLayout(descriptor), mapArgumentLayouts(descriptor));
        }

        /**
         * Registers an FFM downcall signature.
         *
         * @param returnType     canonical return type identifier
         * @param parameterTypes list of canonical parameter type identifiers
         * @return this builder
         */
        public Builder registerDowncall(String returnType, List<String> parameterTypes) {
            return registerDowncall(returnType, parameterTypes, Collections.emptyMap());
        }

        /**
         * Registers an FFM downcall signature with options.
         *
         * @param returnType     canonical return type identifier
         * @param parameterTypes list of canonical parameter type identifiers
         * @param options        map of downcall options (e.g. captureCallState, firstVariadicArg, critical)
         * @return this builder
         */
        public Builder registerDowncall(String returnType, List<String> parameterTypes, Map<String, Object> options) {
            Objects.requireNonNull(returnType, "returnType");
            Objects.requireNonNull(parameterTypes, "parameterTypes");
            Objects.requireNonNull(options, "options");
            downcalls.add(new DowncallRef(returnType, List.copyOf(parameterTypes), options));
            return this;
        }

        /**
         * Registers an FFM direct upcall descriptor.
         *
         * @param className  binary class name declaring the target method
         * @param methodName target method name
         * @param descriptor function descriptor
         * @return this builder
         */
        public Builder registerDirectUpcall(String className, String methodName, FunctionDescriptor descriptor) {
            return registerDirectUpcall(className, methodName, mapReturnLayout(descriptor),
                    mapArgumentLayouts(descriptor));
        }

        /**
         * Registers an FFM direct upcall signature.
         *
         * @param className      binary class name declaring the target method
         * @param methodName     target method name
         * @param returnType     canonical return type identifier
         * @param parameterTypes list of canonical parameter type identifiers
         * @return this builder
         */
        public Builder registerDirectUpcall(String className,
                                           String methodName,
                                           String returnType,
                                           List<String> parameterTypes) {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(methodName, "methodName");
            Objects.requireNonNull(returnType, "returnType");
            Objects.requireNonNull(parameterTypes, "parameterTypes");
            directUpcalls.add(new DirectUpcallRef(className, methodName, returnType, List.copyOf(parameterTypes)));
            return this;
        }

        /**
         * Builds the immutable reachability metadata model.
         *
         * @return the constructed {@link ReachabilityMetadata}
         */
        public ReachabilityMetadata build() {
            return new ReachabilityMetadata(
                    Collections.unmodifiableMap(new TreeMap<>(reflection)),
                    Collections.unmodifiableSet(new TreeSet<>(resources)),
                    Collections.unmodifiableSet(new TreeSet<>(downcalls)),
                    Collections.unmodifiableSet(new TreeSet<>(directUpcalls))
            );
        }
    }
}
