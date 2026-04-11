package eu.exeris.tools.jfr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].startsWith("--")) {
                opts.put(args[i].substring(2), args[i + 1]);
                i++;
            }
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
}
