package eu.exeris.tools.jfr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length % 2 != 0) {
            System.err.println("[jfr-reporter] ERROR: each --<option> must be followed by a value.");
            printUsage();
            System.exit(1);
        }
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                System.err.println("[jfr-reporter] ERROR: expected --<option> but got: " + args[i]);
                printUsage();
                System.exit(1);
            }
            opts.put(args[i].substring(2), args[i + 1]);
        }

        Map<String, Path> moduleDirs = new LinkedHashMap<>();
        addModuleDir(moduleDirs, opts, "core");
        addModuleDir(moduleDirs, opts, "community");

        if (moduleDirs.isEmpty()) {
            System.err.println("[jfr-reporter] ERROR: no valid --core or --community directories found.");
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
            System.err.println("[jfr-reporter] WARN: --" + key + " path not found or not a directory: " + p);
        }
    }

    private static void printUsage() {
        System.err.println("Usage: jfr-reporter [--core <dir>] [--community <dir>] --commit <sha> --branch <name> [--out <dir>]");
    }
}
