/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// CommentDefaultAccessModifier: package-private registry is intentionally scoped to transport internals.
@SuppressWarnings("PMD.CommentDefaultAccessModifier")
final class ChannelRuntimeRegistry {

    final ConcurrentMap<SocketChannel, ChannelRuntimeState> runtimeByChannel = new ConcurrentHashMap<>();
    final ConcurrentMap<SocketChannel, NativeTcpStream> streamByChannel = new ConcurrentHashMap<>();
    final ConcurrentMap<SocketChannel, NativeTcpReactor> channelOwner = new ConcurrentHashMap<>();

    ChannelRuntimeState registerRuntime(NativeTcpStream stream, SocketChannel channel) {
        ChannelRuntimeState runtime = new ChannelRuntimeState(channel, stream, channelOwner);
        ChannelRuntimeState previous = runtimeByChannel.putIfAbsent(channel, runtime);
        if (previous != null) {
            throw new IllegalStateException(
                    "Transport runtime already registered for channel on backend "
                            + previous.socketBackend() + ": " + channel);
        }
        streamByChannel.put(channel, stream);
        return runtime;
    }

    ChannelRuntimeState resolveRuntime(SocketChannel channel) {
        return runtimeByChannel.get(channel);
    }

    NativeTcpStream resolveStream(SocketChannel channel) {
        ChannelRuntimeState runtime = resolveRuntime(channel);
        return runtime != null ? runtime.stream() : streamByChannel.get(channel);
    }

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