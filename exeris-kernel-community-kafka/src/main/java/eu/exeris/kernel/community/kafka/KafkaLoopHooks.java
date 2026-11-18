/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The callbacks an application hands to the raw record channel for the consumer loop to call on its
 * own thread: the rebalance listener and the after-poll hook. Either can be set from any thread at
 * any time; the loop reads them once per iteration, and each is {@code null} until it is set.
 */
final class KafkaLoopHooks {

    private final AtomicReference<ConsumerRebalanceListener> rebalanceListener = new AtomicReference<>();
    private final AtomicReference<Runnable> afterPoll = new AtomicReference<>();

    /* default */ void rebalanceListener(ConsumerRebalanceListener listener) {
        rebalanceListener.set(listener);
    }

    /* default */ ConsumerRebalanceListener rebalanceListener() {
        return rebalanceListener.get();
    }

    /* default */ void afterPoll(Runnable hook) {
        afterPoll.set(hook);
    }

    /* default */ Runnable afterPoll() {
        return afterPoll.get();
    }
}
