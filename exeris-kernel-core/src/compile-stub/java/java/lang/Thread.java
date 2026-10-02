/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package java.lang;

/**
 * Compile-time stub mirroring {@link Thread} with OpenJDK Loom fibers SPI
 * {@code Thread.VirtualThreadScheduler} and {@code Thread.VirtualThreadTask}
 * to enable building on standard mainline JDK 28 without runtime Loom dependency.
 */
public class Thread implements Runnable {

    public Thread() {
    }

    public Thread(Runnable target) {
    }

    public Thread(String name) {
    }

    public Thread(Runnable target, String name) {
    }

    public static Thread currentThread() {
        return null;
    }

    public static void yield() {
    }

    public static void sleep(long millis) throws InterruptedException {
    }

    public static void sleep(long millis, int nanos) throws InterruptedException {
    }

    public static void sleep(java.time.Duration duration) throws InterruptedException {
    }

    public static void onSpinWait() {
    }

    public void start() {
    }

    @Override
    public void run() {
    }

    public void interrupt() {
    }

    public static boolean interrupted() {
        return false;
    }

    public boolean isInterrupted() {
        return false;
    }

    public boolean isAlive() {
        return false;
    }

    public void setPriority(int newPriority) {
    }

    public int getPriority() {
        return 0;
    }

    public void setName(String name) {
    }

    public String getName() {
        return null;
    }

    public long threadId() {
        return 0;
    }

    public ClassLoader getContextClassLoader() {
        return null;
    }

    public void setContextClassLoader(ClassLoader cl) {
    }

    public StackTraceElement[] getStackTrace() {
        return null;
    }

    public static java.util.Map<Thread, StackTraceElement[]> getAllStackTraces() {
        return java.util.Collections.emptyMap();
    }

    public long getId() {
        return threadId();
    }

    public static boolean holdsLock(Object obj) {
        return false;
    }

    public void join(long millis) throws InterruptedException {
    }

    public void join(long millis, int nanos) throws InterruptedException {
    }

    public void join() throws InterruptedException {
    }

    public boolean join(java.time.Duration duration) throws InterruptedException {
        return false;
    }

    public boolean isVirtual() {
        return false;
    }

    public void setDaemon(boolean on) {
    }

    public boolean isDaemon() {
        return false;
    }

    public UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return null;
    }

    public void setUncaughtExceptionHandler(UncaughtExceptionHandler eh) {
    }

    public interface UncaughtExceptionHandler {
        void uncaughtException(Thread t, Throwable e);
    }

    public enum State {
        NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED;
    }

    public State getState() {
        return State.NEW;
    }

    public static Builder.OfPlatform ofPlatform() {
        return null;
    }

    public static Builder.OfVirtual ofVirtual() {
        return null;
    }

    public interface Builder {
        java.util.concurrent.ThreadFactory factory();

        interface OfPlatform extends Builder {
            OfPlatform name(String name);
            OfPlatform name(String prefix, long start);
            OfPlatform daemon(boolean daemon);
            OfPlatform daemon();
            OfPlatform priority(int priority);
            OfPlatform inheritInheritableThreadLocals(boolean inherit);
            OfPlatform uncaughtExceptionHandler(UncaughtExceptionHandler ueh);
            OfPlatform group(ThreadGroup group);
            Thread unstarted(Runnable task);
            Thread start(Runnable task);
            @Override
            java.util.concurrent.ThreadFactory factory();
        }

        interface OfVirtual extends Builder {
            OfVirtual name(String name);
            OfVirtual name(String prefix, long start);
            OfVirtual inheritInheritableThreadLocals(boolean inherit);
            OfVirtual uncaughtExceptionHandler(UncaughtExceptionHandler ueh);
            Thread unstarted(Runnable task);
            Thread start(Runnable task);
            @Override
            java.util.concurrent.ThreadFactory factory();
        }
    }

    public interface VirtualThreadScheduler {
        void onStart(VirtualThreadTask task);

        void onContinue(VirtualThreadTask task);

        default VirtualThreadTask newThread(Thread.Builder.OfVirtual builder, Thread carrier, Runnable task) {
            return null;
        }

        default java.util.concurrent.Future<?> schedule(Runnable task, long delay, java.util.concurrent.TimeUnit unit) {
            return null;
        }
    }

    public interface VirtualThreadTask extends Runnable {
        Thread thread();

        @Override
        void run();

        Thread preferredCarrier();

        Object attach(Object obj);

        Object attachment();
    }
}
