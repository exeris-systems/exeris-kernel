/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler.locality;

import eu.exeris.kernel.core.transport.scheduler.locality.ExerisCarrierThread.CarrierWorkerThread;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Resolves the calling thread to the locality carrier it is executing on.
 *
 * <p>Three kinds of caller exist when the scheduler is entered: a carrier's own platform thread,
 * a virtual thread mounted on some carrier, and any other platform thread (reactor, backend,
 * delayed-task, poller). Only the first two run on a carrier of this scheduler; the third is
 * remote and resolves to {@code null}.
 *
 * <p>The mounted carrier of a virtual thread is not public API. It is reached through the JDK's
 * internal {@code JavaLangAccess} by method handle, so that this module compiles without any
 * {@code --add-exports}; when the package is not exported to this module at run time the handle
 * is absent and a virtual thread resolves to {@code null}, never to a wrong carrier.
 */
final class CarrierResolver {

    private static final MethodHandle MOUNTED_CARRIER = lookupMountedCarrier();

    private CarrierResolver() {
    }

    private static MethodHandle lookupMountedCarrier() {
        try {
            Class<?> sharedSecrets = Class.forName("jdk.internal.access.SharedSecrets");
            Class<?> javaLangAccess = Class.forName("jdk.internal.access.JavaLangAccess");
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Object access = lookup.findStatic(sharedSecrets, "getJavaLangAccess",
                    MethodType.methodType(javaLangAccess)).invoke();
            return lookup.findVirtual(javaLangAccess, "currentCarrierThread",
                    MethodType.methodType(Thread.class)).bindTo(access);
        } catch (Throwable unavailable) {
            // not exported to this module (or not this JDK): virtual threads resolve to remote
            return null;
        }
    }

    /** Whether a virtual thread's mounted carrier can be read on this JVM. */
    static boolean mountedCarrierAvailable() {
        return MOUNTED_CARRIER != null;
    }

    /**
     * The carrier the calling thread is executing on, or {@code null} for a remote caller.
     */
    static ExerisCarrierThread mounted() {
        Thread current = Thread.currentThread();
        if (current instanceof CarrierWorkerThread worker) {
            return worker.carrier();
        }
        if (MOUNTED_CARRIER != null && current.isVirtual()) {
            try {
                if ((Thread) MOUNTED_CARRIER.invokeExact() instanceof CarrierWorkerThread worker) {
                    return worker.carrier();
                }
            } catch (Error e) {
                throw e;
            } catch (Throwable unresolvable) {
                return null;
            }
        }
        return null;
    }
}
