/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import org.jctools.queues.MpscUnboundedArrayQueue;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reactor loop owning a single {@link Selector} plus its FD-set, draining
 * registration / write-interest / cancel requests off a single MPSC queue,
 * and dispatching READ / WRITE events back to the hosting carrier.
 *
 * <p>Extracted from {@link NativeTcpCarrier} in v0.8 Sprint 1 (QA-013b) as
 * the second step in closing the carrier's God-class suppression block.
 * Previously a non-static inner class on the carrier, now a top-level
 * package-private class with a {@link NativeTcpCarrier} reference for
 * callbacks (ingress read, egress flush, key teardown, async failure,
 * stream resolution).
 *
 * <p>Threading model preserved verbatim:
 * <ul>
 *   <li>Single consumer of {@link #pendingRequests} — the reactor thread
 *       in {@link #runLoop()}.</li>
 *   <li>Multiple producers — any caller of {@link #enqueueRegistration},
 *       {@link #enqueueWriteInterest}, {@link #cancelKey}.</li>
 *   <li>Selector wake-up coalescing via {@link #wakeupPending} — at most
 *       one outstanding {@code wakeup()} per drain cycle.</li>
 * </ul>
 */
@SuppressWarnings({
    "PMD.AvoidCatchingGenericException", // selector loop must catch RuntimeException from key dispatch.
    "PMD.CloseResource",                 // Selector lifetime is owned by NativeTcpCarrier.closeSelectorAndChannels.
    "PMD.CyclomaticComplexity",          // reactor lifecycle (start/drain/loop/cancel) is intrinsically cohesive.
    "PMD.GodClass",                      // WMC tipped +1 by extracting dispatchSelectedKey (lower per-method
                                         // cognitive complexity); the reactor orchestrator stays cohesive.
    "PMD.TooManyMethods"                 // selector + MPSC queue + interest set lifecycle co-located by design.
})
final class NativeTcpReactor {

    private final NativeTcpCarrier host;
    private final int index;
    private final Selector selector;

    // PERF-063: MpscUnboundedArrayQueue replaces ConcurrentLinkedQueue. Multiple producer
    // threads (any caller of enqueueRegistration / enqueueWriteInterest / cancelKey) feed a
    // single consumer (the reactor thread in runLoop). MPSC semantics match exactly; chunked
    // allocation (size 128) avoids per-element node alloc churn under burst arrivals; offer()
    // on the unbounded variant always returns true so no backpressure handling is required at
    // the call site (parity with the existing pattern in NativeTcpStream's outboundQueue).
    private final Queue<ReactorRequest> pendingRequests = new MpscUnboundedArrayQueue<>(128);
    private final Set<SocketChannel> pendingWriteInterest = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean wakeupPending = new AtomicBoolean(false);

    private volatile Thread thread;

    /* default */ NativeTcpReactor(NativeTcpCarrier host, int index, Selector selector) {
        this.host = host;
        this.index = index;
        this.selector = selector;
    }

    /* default */ void start() {
        this.thread = Thread.ofPlatform()
                .name("carrier/native-tcp/reactor/" + index)
                .start(this::runLoop);
    }

    /* default */ void enqueueRegistration(SocketChannel channel) {
        enqueueRequest(new ReactorRequest(ReactorRequestType.REGISTER, channel));
    }

    /* default */ void enqueueWriteInterest(SocketChannel channel) {
        enqueueRequest(new ReactorRequest(ReactorRequestType.ENABLE_WRITE, channel));
    }

    /* default */ void cancelKey(SocketChannel channel) {
        enqueueRequest(new ReactorRequest(ReactorRequestType.CANCEL, channel));
    }

    /* default */ void wakeup() {
        signalSelector();
    }

    /* default */ void join(long timeoutMs) {
        Thread localThread = thread;
        if (localThread == null) {
            return;
        }
        try {
            localThread.join(timeoutMs);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /* default */ void closeSelector() {
        try {
            selector.close();
        } catch (IOException _) {
            // best effort
        }
    }

    private void enqueueRequest(ReactorRequest request) {
        pendingRequests.offer(request);
        signalSelector();
    }

    private void signalSelector() {
        if (wakeupPending.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }

    @SuppressWarnings("PMD.CognitiveComplexity") // selector loop drains MPSC + polls keys; per-key
    // dispatch extracted to dispatchSelectedKey (PERF-073) so cyclomatic complexity no longer trips.
    private void runLoop() {
        while (host.isReactorActive()) {
            try {
                boolean drainedRequests = drainPendingRequests();
                if (!host.isReactorActive()) {
                    return;
                }

                if (drainedRequests) {
                    selector.selectNow();
                } else {
                    selector.select(100L);
                }

                drainPendingRequests();
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    try {
                        dispatchSelectedKey(key);
                    } catch (CancelledKeyException _) {
                        // channel closed concurrently by VT handler path
                    } catch (RuntimeException ex) {
                        // JFR-first: surface the unrecoverable per-stream fault before abortive
                        // teardown. A recurring fault here is the signature of a reactor spin that
                        // a zero-alloc, stack-trace-less sentinel cause hides from exception sampling.
                        NativeTcpStream faulted = (NativeTcpStream) key.attachment();
                        CommunityReactorDispatchFaultEvent.emit(
                                faulted != null ? faulted.streamId() : -1L, index, ex.getClass().getName());
                        host.closeKeyStream(key);
                    }
                }
            } catch (IOException e) {
                if (host.isReactorActive()) {
                    host.handleAsyncFailure("reactor/" + index, e);
                }
                return;
            }
        }
    }

    // PERF-073: dispatch a single selected key by reading the stream off key.attachment()
    // (set at registerChannel) instead of a per-event runtimeByChannel lookup. Extracted from
    // runLoop so the select loop stays low-complexity.
    private void dispatchSelectedKey(SelectionKey key) {
        if (!key.isValid()) {
            return;
        }
        NativeTcpStream attached = (NativeTcpStream) key.attachment();
        if (attached == null) {
            // Degenerate (stream null at register): clear write interest so the key does not
            // re-fire writable every select cycle (matches the pre-attachment flushStream path).
            if (key.isWritable()) {
                key.interestOps(SelectionKey.OP_READ);
            }
            return;
        }
        if (key.isReadable()) {
            host.readIngress(attached);
        }
        if (key.isWritable()) {
            host.flushStream(attached, key);
        }
    }

    private boolean drainPendingRequests() {
        boolean drainedAny = false;
        ReactorRequest request = pendingRequests.poll();
        while (request != null) {
            drainedAny = true;
            processPendingRequest(request);
            request = pendingRequests.poll();
        }

        wakeupPending.set(false);
        if (!pendingRequests.isEmpty()) {
            signalSelector();
            return true;
        }
        return drainedAny;
    }

    private void processPendingRequest(ReactorRequest request) {
        SocketChannel channel = request.channel();
        switch (request.type()) {
            case REGISTER -> registerChannel(channel);
            case ENABLE_WRITE -> enableWriteInterest(channel);
            case CANCEL -> cancelSelection(channel);
        }
    }

    private void registerChannel(SocketChannel channel) {
        try {
            if (channel.isOpen()) {
                NativeTcpStream stream = host.resolveStream(channel);
                boolean enableWriteOnRegister = pendingWriteInterest.remove(channel)
                        || (stream != null && stream.hasPendingData());
                int interestOps = enableWriteOnRegister
                        ? SelectionKey.OP_READ | SelectionKey.OP_WRITE
                        : SelectionKey.OP_READ;
                // PERF-073: attach the stream so runLoop reads key.attachment() instead of a
                // per-event runtimeByChannel lookup; the maps stay authoritative for off-reactor callers.
                channel.register(selector, interestOps, stream);
                if (stream != null) {
                    stream.markRegistrationReady();
                }
            } else {
                pendingWriteInterest.remove(channel);
            }
        } catch (IOException _) {
            pendingWriteInterest.remove(channel);
            NativeTcpStream stream = host.resolveStream(channel);
            if (stream != null) {
                stream.close();
            }
        }
    }

    private void enableWriteInterest(SocketChannel channel) {
        SelectionKey key = channel.keyFor(selector);
        if (key == null || !key.isValid()) {
            pendingWriteInterest.add(channel);
            return;
        }
        pendingWriteInterest.remove(channel);
        try {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE | SelectionKey.OP_READ);
        } catch (RuntimeException _) {
            pendingWriteInterest.add(channel);
        }
    }

    private void cancelSelection(SocketChannel channel) {
        pendingWriteInterest.remove(channel);
        SelectionKey key = channel.keyFor(selector);
        if (key != null) {
            key.cancel();
        }
    }

    /* default */ enum ReactorRequestType {
        REGISTER,
        ENABLE_WRITE,
        CANCEL
    }

    /* default */ value record ReactorRequest(ReactorRequestType type, SocketChannel channel) {
    }
}
