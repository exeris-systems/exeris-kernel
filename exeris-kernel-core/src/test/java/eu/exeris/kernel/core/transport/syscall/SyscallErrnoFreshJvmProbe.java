/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.syscall;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Child-JVM entry point for {@link SyscallErrnoFreshJvmTest}: loads the handles, makes a send on an
 * invalid descriptor fail, and reports the errno read right after, as the first errno read of the JVM.
 * Exits {@code 0} when the errno is the call's own ({@code EBADF} on Linux; a real error that is not the
 * value symbol resolution leaves behind elsewhere), {@code 1} otherwise.
 */
public final class SyscallErrnoFreshJvmProbe {

    private static final int ENOENT = 2;
    private static final int EBADF = 9;
    private static final boolean LINUX =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("linux");

    private SyscallErrnoFreshJvmProbe() {
        // entry point only
    }

    /**
     * Runs the probe.
     *
     * @param args unused
     * @throws Throwable if the downcall fails
     */
    public static void main(String[] args) throws Throwable {
        try (Arena arena = Arena.ofShared()) {
            SyscallHandles handles = CoreSyscallLoader.load(arena);
            long result = (long) handles.send().invokeExact(-1, MemorySegment.NULL, 0L, 0);
            int errno = SyscallErrno.currentSocketErrno(handles);
            System.out.println("send=" + result + " errno=" + errno);
            boolean own = LINUX ? errno == EBADF : errno > 0 && errno != ENOENT;
            System.exit(result < 0 && own ? 0 : 1);
        }
    }
}
