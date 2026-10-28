/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.syscall;

import eu.exeris.kernel.tck.contract.abi.AbstractAbiSymbolResolutionTck;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Core binding of {@link AbstractAbiSymbolResolutionTck} for the socket syscalls: the manifest is every
 * {@link SyscallDowncall} row. POSIX rows resolve from the native linker's default lookup, as
 * {@link CoreSyscallLoader} resolves them; Windows rows apply only on a Windows host, where they resolve
 * from {@code Ws2_32.dll} (Windows is not a verified target, so no CI job exercises that path).
 */
@DisplayName("Core syscall ABI symbol resolution")
class CoreSyscallAbiSymbolResolutionTckTest extends AbstractAbiSymbolResolutionTck {

    private final SymbolLookup posixLookup = Linker.nativeLinker().defaultLookup();

    @Override
    protected List<AbiSymbol> manifest() {
        return Arrays.stream(SyscallDowncall.values())
                .map(d -> new AbiSymbol("libc", d.symbol(),
                        d.anyOf() == null ? Resolution.REQUIRED : Resolution.OPTIONAL,
                        d.platform() == SyscallDowncall.Platform.POSIX ? Platform.POSIX : Platform.WINDOWS,
                        d.anyOf()))
                .toList();
    }

    @Override
    protected Optional<MemorySegment> resolve(AbiSymbol symbol) {
        return lookupFor(symbol).find(symbol.name());
    }

    /** Windows rows resolve from {@code Ws2_32.dll}, as the loader resolves them; POSIX rows from libc. */
    private SymbolLookup lookupFor(AbiSymbol symbol) {
        if (currentPlatform() == Platform.WINDOWS) {
            return SymbolLookup.libraryLookup("Ws2_32.dll", Arena.global());
        }
        return posixLookup;
    }

    @Override
    protected boolean pinnedMatrixRun() {
        return false;
    }
}
