/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Maps a live {@link SocketChannel} to the {@link NativeTcpStream} reading and writing it and the
 * {@link NativeTcpReactor} currently servicing it, for the carrier's off-reactor callers (write
 * interest requests, close teardown) to look up.
 *
 * <p>Backed by {@link ConcurrentHashMap}, so any thread may register, resolve or remove an entry
 * concurrently with a reactor dispatching events for other channels; there is no per-channel
 * confinement. {@link #registerRuntime} is the single admission point and rejects a second
 * registration for the same channel rather than silently overwriting the first.
 *
 * <p>A carrier that stops seals the registry and takes its snapshot of registered channels in one
 * step ({@link #sealAndSnapshot}), under the same lock that admits a registration. A registration
 * therefore either lands before the seal, and is in the snapshot the stop closes, or finds the
 * registry sealed and is refused; none can land after the snapshot and be missed by it.
 */
// CommentDefaultAccessModifier: package-private registry is intentionally scoped to transport internals.
@SuppressWarnings("PMD.CommentDefaultAccessModifier")
final class ChannelRuntimeRegistry {

    final ConcurrentMap<SocketChannel, ChannelRuntimeState> runtimeByChannel = new ConcurrentHashMap<>();
    final ConcurrentMap<SocketChannel, NativeTcpStream> streamByChannel = new ConcurrentHashMap<>();
    final ConcurrentMap<SocketChannel, NativeTcpReactor> channelOwner = new ConcurrentHashMap<>();

    private final Object admissionLock = new Object();
    /** Guarded by {@link #admissionLock}. */
    private boolean sealed;

    /**
     * Registers a new channel/stream pair. The single admission point for this registry.
     *
     * @param stream  the stream reading and writing {@code channel}
     * @param channel the channel to register
     * @param refusal what to report if the registry is sealed
     * @return the runtime state created for this pair
     * @throws IllegalStateException if {@code channel} is already registered, or ({@code refusal})
     *                               if the registry is sealed
     */
    ChannelRuntimeState registerRuntime(NativeTcpStream stream, SocketChannel channel, String refusal) {
        ChannelRuntimeState runtime = new ChannelRuntimeState(channel, stream, channelOwner);
        synchronized (admissionLock) {
            if (sealed) {
                throw new IllegalStateException(refusal);
            }
            ChannelRuntimeState previous = runtimeByChannel.putIfAbsent(channel, runtime);
            if (previous != null) {
                throw new IllegalStateException(
                        "Transport runtime already registered for channel on backend "
                                + previous.socketBackend() + ": " + channel);
            }
            streamByChannel.put(channel, stream);
        }
        return runtime;
    }

    /**
     * Refuses every later registration and returns the channels registered so far, both in one step
     * with respect to {@link #registerRuntime}.
     *
     * @return the runtime state of every channel registered before the seal
     */
    List<ChannelRuntimeState> sealAndSnapshot() {
        synchronized (admissionLock) {
            sealed = true;
            return List.copyOf(runtimeByChannel.values());
        }
    }

    /** Admits registrations again, for a carrier that starts after it stopped. */
    void unseal() {
        synchronized (admissionLock) {
            sealed = false;
        }
    }

    ChannelRuntimeState resolveRuntime(SocketChannel channel) {
        return runtimeByChannel.get(channel);
    }

    NativeTcpStream resolveStream(SocketChannel channel) {
        ChannelRuntimeState runtime = resolveRuntime(channel);
        return runtime != null ? runtime.stream() : streamByChannel.get(channel);
    }

    /**
     * One channel's registered stream, resolved socket-backend name, and current reactor owner.
     *
     * <p>{@link #owner} and {@link #lifecycleCleanup} are the only fields that change after
     * construction, and both are updated through atomics ({@link #bindOwner}, {@link #detachOwner},
     * {@link #beginLifecycleCleanup}) so a reactor handing this channel to another reactor, or the
     * carrier tearing it down, never races a concurrent reader.
     */
    static final class ChannelRuntimeState {

        private final SocketChannel channel;
        private final NativeTcpStream stream;
        private final String socketBackend;
        private final ConcurrentMap<SocketChannel, NativeTcpReactor> channelOwner;
        private final AtomicReference<NativeTcpReactor> owner = new AtomicReference<>();
        private final AtomicBoolean lifecycleCleanup = new AtomicBoolean(false);

        private ChannelRuntimeState(SocketChannel channel,
                                    NativeTcpStream stream,
                                    ConcurrentMap<SocketChannel, NativeTcpReactor> channelOwner) {
            this.channel = channel;
            this.stream = stream;
            this.socketBackend = stream.plainSocketBackendName();
            this.channelOwner = channelOwner;
        }

        SocketChannel channel() {
            return channel;
        }

        NativeTcpStream stream() {
            return stream;
        }

        String socketBackend() {
            return socketBackend;
        }

        void bindOwner(NativeTcpReactor newOwner) {
            owner.set(newOwner);
            channelOwner.put(channel, newOwner);
        }

        void markRegistrationPending() {
            stream.markRegistrationPending();
        }

        NativeTcpReactor owner() {
            return owner.get();
        }

        NativeTcpReactor detachOwner() {
            return owner.getAndSet(null);
        }

        boolean beginLifecycleCleanup() {
            return lifecycleCleanup.compareAndSet(false, true);
        }
    }
}