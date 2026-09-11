/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

/**
 * One frame of a recorded allocation stack, top-most first.
 *
 * @param className fully qualified class name of the frame's method
 * @param method    method name
 * @param line      source line, or a negative value when JFR did not record one
 */
public record Frame(String className, String method, int line) {

    /**
     * Renders the frame the way {@code stacks.json} has always shown it:
     * {@code fqn.method(Simple.java:line)}.
     *
     * @return the rendered frame
     */
    public String describe() {
        int dot = className.lastIndexOf('.');
        String simpleName = dot >= 0 ? className.substring(dot + 1) : className;
        return className + "." + method + "(" + simpleName + ".java:" + line + ")";
    }
}
