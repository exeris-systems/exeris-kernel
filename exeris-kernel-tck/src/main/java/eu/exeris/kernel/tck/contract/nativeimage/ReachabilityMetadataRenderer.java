/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.nativeimage;

import java.util.Map;
import java.util.Set;

/**
 * Package-private canonical JSON renderer for reachability metadata models.
 */
final class ReachabilityMetadataRenderer {

    private static final String EMPTY_JSON = "{}\n";

    private ReachabilityMetadataRenderer() {
    }

    static String render(Map<String, ReachabilityMetadata.ReflectionEntry> reflection,
                         Set<String> resources,
                         Set<ReachabilityMetadata.DowncallRef> downcalls,
                         Set<ReachabilityMetadata.DirectUpcallRef> directUpcalls) {
        if (reflection.isEmpty() && resources.isEmpty() && downcalls.isEmpty() && directUpcalls.isEmpty()) {
            return EMPTY_JSON;
        }

        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n");
        boolean firstTopLevel = true;

        if (!reflection.isEmpty()) {
            sb.append("  \"reflection\": [\n");
            renderReflectionEntries(sb, reflection);
            sb.append("\n  ]");
            firstTopLevel = false;
        }

        if (!resources.isEmpty()) {
            if (!firstTopLevel) {
                sb.append(",\n");
            }
            sb.append("  \"resources\": [\n");
            renderResourceEntries(sb, resources);
            sb.append("\n  ]");
            firstTopLevel = false;
        }

        if (!downcalls.isEmpty() || !directUpcalls.isEmpty()) {
            if (!firstTopLevel) {
                sb.append(",\n");
            }
            sb.append("  \"foreign\": {\n");
            ForeignMetadataRenderer.renderForeignEntries(sb, downcalls, directUpcalls);
            sb.append("\n  }");
        }

        sb.append("\n}\n");
        return sb.toString();
    }

    private static void renderReflectionEntries(StringBuilder sb,
                                                Map<String, ReachabilityMetadata.ReflectionEntry> reflection) {
        boolean firstEntry = true;
        for (ReachabilityMetadata.ReflectionEntry entry : reflection.values()) {
            if (!firstEntry) {
                sb.append(",\n");
            }
            sb.append("    {\n");
            sb.append("      \"type\": \"");
            JsonEscaper.escape(sb, entry.type());
            sb.append('"');

            if (!entry.methods().isEmpty()) {
                sb.append(",\n      \"methods\": [\n");
                renderMethods(sb, entry.methods());
                sb.append("\n      ]");
            }

            if (!entry.fields().isEmpty()) {
                sb.append(",\n      \"fields\": [\n");
                renderFields(sb, entry.fields());
                sb.append("\n      ]");
            }

            if (entry.allDeclaredFields()) {
                sb.append(",\n      \"allDeclaredFields\": true");
            }

            sb.append("\n    }");
            firstEntry = false;
        }
    }

    private static void renderMethods(StringBuilder sb, Set<ReachabilityMetadata.MethodRef> methods) {
        boolean firstMethod = true;
        for (ReachabilityMetadata.MethodRef method : methods) {
            if (!firstMethod) {
                sb.append(",\n");
            }
            sb.append("        {\n");
            sb.append("          \"name\": \"");
            JsonEscaper.escape(sb, method.name());
            sb.append("\",\n");
            if (method.parameterTypes().isEmpty()) {
                sb.append("          \"parameterTypes\": []\n");
            } else {
                sb.append("          \"parameterTypes\": [\n");
                for (int i = 0; i < method.parameterTypes().size(); i++) {
                    sb.append("            \"");
                    JsonEscaper.escape(sb, method.parameterTypes().get(i));
                    sb.append('"');
                    if (i < method.parameterTypes().size() - 1) {
                        sb.append(',');
                    }
                    sb.append('\n');
                }
                sb.append("          ]\n");
            }
            sb.append("        }");
            firstMethod = false;
        }
    }

    private static void renderFields(StringBuilder sb, Set<String> fields) {
        boolean firstField = true;
        for (String field : fields) {
            if (!firstField) {
                sb.append(",\n");
            }
            sb.append("        {\n");
            sb.append("          \"name\": \"");
            JsonEscaper.escape(sb, field);
            sb.append("\"\n        }");
            firstField = false;
        }
    }

    private static void renderResourceEntries(StringBuilder sb, Set<String> resources) {
        boolean firstRes = true;
        for (String glob : resources) {
            if (!firstRes) {
                sb.append(",\n");
            }
            sb.append("    {\n");
            sb.append("      \"glob\": \"");
            JsonEscaper.escape(sb, glob);
            sb.append("\"\n    }");
            firstRes = false;
        }
    }
}
