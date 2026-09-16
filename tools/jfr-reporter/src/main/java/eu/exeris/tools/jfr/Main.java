/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Main {

    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
    private static final String LOG_PREFIX_ERROR = "[jfr-reporter] ERROR: ";
    private static final String LOG_PREFIX_WARN  = "[jfr-reporter] WARN: ";

    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length % 2 != 0) {
            LOGGER.log(Level.SEVERE, () -> LOG_PREFIX_ERROR + "each --<option> must be followed by a value.");
            printUsage();
            System.exit(1);
        }
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            final int idx = i;
            if (!args[i].startsWith("--")) {
                LOGGER.log(Level.SEVERE, () -> LOG_PREFIX_ERROR + "expected --<option> but got: " + args[idx]);
                printUsage();
                System.exit(1);
            }
            opts.put(args[i].substring(2), args[i + 1]);
        }

        Map<String, Path> moduleDirs = new LinkedHashMap<>();
        addModuleDir(moduleDirs, opts, "core");
        addModuleDir(moduleDirs, opts, "community");

        if (moduleDirs.isEmpty()) {
            LOGGER.log(Level.SEVERE, () -> LOG_PREFIX_ERROR + "no valid --core or --community directories found.");
            System.exit(1);
        }

        Path outDir = Path.of(opts.getOrDefault("out", "build/jfr-report"));
        String commit = opts.getOrDefault("commit", "unknown");
        String branch = opts.getOrDefault("branch", "unknown");

        Files.createDirectories(outDir);
        new ReportGenerator(moduleDirs, commit, branch, outDir).generate();
    }

    private static void addModuleDir(Map<String, Path> moduleDirs, Map<String, String> opts, String key) {
        if (!opts.containsKey(key)) return;
        Path p = Path.of(opts.get(key));
        if (Files.isDirectory(p)) {
            moduleDirs.put(key, p);
        } else {
            LOGGER.log(Level.WARNING, () -> LOG_PREFIX_WARN + "--" + key + " path not found or not a directory: " + p);
        }
    }

    private static void printUsage() {
        LOGGER.info(() -> "Usage: jfr-reporter [--core <dir>] [--community <dir>] --commit <sha> --branch <name> [--out <dir>]");
    }
}
