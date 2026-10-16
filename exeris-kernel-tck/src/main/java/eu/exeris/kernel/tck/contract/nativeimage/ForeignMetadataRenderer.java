/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.nativeimage;

import java.util.Map;
import java.util.Set;

/**
 * Package-private helper rendering the foreign section of reachability metadata.
 */
final class ForeignMetadataRenderer {

    private ForeignMetadataRenderer() {
    }

    static void renderForeignEntries(StringBuilder sb,
                                     Set<ReachabilityMetadata.DowncallRef> downcalls,
                                     Set<ReachabilityMetadata.DirectUpcallRef> directUpcalls) {
        boolean firstForeign = true;
        if (!downcalls.isEmpty()) {
            sb.append("    \"downcalls\": [\n");
            renderDowncalls(sb, downcalls);
            sb.append("\n    ]");
            firstForeign = false;
        }

        if (!directUpcalls.isEmpty()) {
            if (!firstForeign) {
                sb.append(",\n");
            }
            sb.append("    \"directUpcalls\": [\n");
            renderDirectUpcalls(sb, directUpcalls);
            sb.append("\n    ]");
        }
    }

    private static void renderDowncalls(StringBuilder sb, Set<ReachabilityMetadata.DowncallRef> downcalls) {
        boolean firstDown = true;
        for (ReachabilityMetadata.DowncallRef downcall : downcalls) {
            if (!firstDown) {
                sb.append(",\n");
            }
            sb.append("      {\n");
            sb.append("        \"returnType\": \"");
            JsonEscaper.escape(sb, downcall.returnType());
            sb.append("\",\n");
            if (downcall.parameterTypes().isEmpty()) {
                sb.append("        \"parameterTypes\": []");
            } else {
                sb.append("        \"parameterTypes\": [\n");
                for (int i = 0; i < downcall.parameterTypes().size(); i++) {
                    sb.append("          \"");
                    JsonEscaper.escape(sb, downcall.parameterTypes().get(i));
                    sb.append('"');
                    if (i < downcall.parameterTypes().size() - 1) {
                        sb.append(',');
                    }
                    sb.append('\n');
                }
                sb.append("        ]");
            }
            if (!downcall.options().isEmpty()) {
                sb.append(",\n        \"options\": {\n");
                boolean firstOpt = true;
                for (Map.Entry<String, Object> opt : downcall.options().entrySet()) {
                    if (!firstOpt) {
                        sb.append(",\n");
                    }
                    sb.append("          \"");
                    JsonEscaper.escape(sb, opt.getKey());
                    sb.append("\": ");
                    renderOptionValue(sb, opt.getValue());
                    firstOpt = false;
                }
                sb.append("\n        }");
            }
            sb.append("\n      }");
            firstDown = false;
        }
    }

    private static void renderOptionValue(StringBuilder sb, Object val) {
        if (val instanceof Boolean b) {
            sb.append(b);
        } else if (val instanceof Number n) {
            sb.append(n);
        } else {
            sb.append('"');
            JsonEscaper.escape(sb, String.valueOf(val));
            sb.append('"');
        }
    }

    private static void renderDirectUpcalls(StringBuilder sb, Set<ReachabilityMetadata.DirectUpcallRef> directUpcalls) {
        boolean firstUp = true;
        for (ReachabilityMetadata.DirectUpcallRef upcall : directUpcalls) {
            if (!firstUp) {
                sb.append(",\n");
            }
            sb.append("      {\n");
            sb.append("        \"class\": \"");
            JsonEscaper.escape(sb, upcall.className());
            sb.append("\",\n        \"method\": \"");
            JsonEscaper.escape(sb, upcall.method());
            sb.append("\",\n        \"returnType\": \"");
            JsonEscaper.escape(sb, upcall.returnType());
            sb.append("\",\n");
            if (upcall.parameterTypes().isEmpty()) {
                sb.append("        \"parameterTypes\": []\n");
            } else {
                sb.append("        \"parameterTypes\": [\n");
                for (int i = 0; i < upcall.parameterTypes().size(); i++) {
                    sb.append("          \"");
                    JsonEscaper.escape(sb, upcall.parameterTypes().get(i));
                    sb.append('"');
                    if (i < upcall.parameterTypes().size() - 1) {
                        sb.append(',');
                    }
                    sb.append('\n');
                }
                sb.append("        ]\n");
            }
            sb.append("      }");
            firstUp = false;
        }
    }
}
