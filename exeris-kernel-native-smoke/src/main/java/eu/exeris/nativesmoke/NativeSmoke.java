/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import java.util.Arrays;

/**
 * Main entry point for native smoke testing.
 */
@SuppressWarnings({
        "PMD.SystemPrintln", "PMD.DoNotTerminateVM",
        "PMD.AvoidCatchingGenericException", "PMD.SignatureDeclareThrowsException"
})
public final class NativeSmoke {

    private static final String STEP_BOOT_LOCAL = "boot-local";
    private static final String STEP_TLS = "tls";
    private static final String STEP_ROLES = "roles";
    private static final String STEP_GRAPH = "graph";
    private static final String STEP_STARTUP = "startup";

    private NativeSmoke() {
        // Application main class
    }

    /**
     * Dispatches the requested smoke verification step.
     *
     * @param arguments command-line arguments: step name followed by step-specific arguments
     */
    public static void main(String... arguments) {
        try {
            execute(arguments);
        } catch (Throwable unhandledThrowable) {
            System.out.println("UNHANDLED: " + unhandledThrowable);
            for (Throwable cause = unhandledThrowable.getCause(); cause != null; cause = cause.getCause()) {
                System.out.println("  caused by: " + cause);
            }
            unhandledThrowable.printStackTrace(System.out);
            System.exit(2);
        }
    }

    private static void execute(String... arguments) throws Exception {
        if (arguments.length == 0) {
            System.out.println("ERROR: NativeSmoke requires a step name (boot-local, tls, roles, graph, startup)");
            System.exit(1);
        }

        String step = arguments[0];
        String[] stepArguments = Arrays.copyOfRange(arguments, 1, arguments.length);

        switch (step) {
            case STEP_BOOT_LOCAL -> BootLocalStep.run();
            case STEP_TLS -> TlsStep.run(stepArguments);
            case STEP_ROLES -> RolesStep.run();
            case STEP_GRAPH -> GraphStep.run(stepArguments);
            case STEP_STARTUP -> StartupStep.run();
            default -> {
                System.out.println("ERROR: Unknown step " + step);
                System.exit(1);
            }
        }
    }
}
