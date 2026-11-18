/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Shared doubles for the manual-commit, close-order and loop-failure tests: a producer and a consumer
 * that record every lifecycle call into one ordered {@link CallLog}, and helpers that build
 * Exeris-framed records and configurations. No broker is reached.
 */
final class KafkaLoopTestSupport {

    /** Registered event type name. */
    static final String TYPE = "KafkaLoopTestEvent";
    /** Registered event type ordinal. */
    static final int ORDINAL = 9_410;
    /** The topic the engine subscribes to for {@link #TYPE} under an empty prefix. */
    static final String TOPIC = TYPE;

    private KafkaLoopTestSupport() {
    }

    static String group() {
        return "loop-test-" + UUID.randomUUID();
    }

    /** A config with a short poll timeout; manual commit when {@code manual}. */
    static KafkaEventConfig config(String group, boolean manual, Duration closeTimeout) {
        Map<String, String> consumerOverrides = manual ? Map.of("enable.auto.commit", "false") : Map.of();
        return new KafkaEventConfig("localhost:1", group, "", true, 0L, Duration.ofMillis(20L),
                Map.of(), consumerOverrides, "", KafkaEventConfig.LoopThread.VIRTUAL, closeTimeout);
    }

    static EventDescriptor descriptor() {
        UUID id = UUID.randomUUID();
        return new EventDescriptor(id.getMostSignificantBits(), id.getLeastSignificantBits(),
                0L, 0L, ORDINAL, EventDescriptor.FLAG_ASYNC, System.currentTimeMillis());
    }

    /** An Exeris-framed record value, as the module's own codec writes it. */
    static byte[] frame() {
        return KafkaEventCodec.encode(descriptor(), EventPayload.empty());
    }

    /** Polls {@code condition} until it holds or {@code timeout} elapses; returns the last value. */
    static boolean await(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        return true;
    }

    /** An ordered, thread-safe list of call names that a thread can wait on. */
    static final class CallLog {
        private final Lock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private final List<String> entries = new ArrayList<>();

        void add(String call) {
            lock.lock();
            try {
                entries.add(call);
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        List<String> snapshot() {
            lock.lock();
            try {
                return List.copyOf(entries);
            } finally {
                lock.unlock();
            }
        }

        boolean contains(String call) {
            return snapshot().contains(call);
        }

        /** Waits until the log holds {@code call}; false on timeout. */
        boolean awaitCall(String call, Duration timeout) throws InterruptedException {
            long remaining = timeout.toNanos();
            lock.lock();
            try {
                while (!entries.contains(call)) {
                    if (remaining <= 0L) {
                        return false;
                    }
                    remaining = changed.awaitNanos(remaining);
                }
                return true;
            } finally {
                lock.unlock();
            }
        }
    }

    /** A {@link MockProducer} that records flush and close into the shared log. */
    static final class RecordingProducer extends MockProducer<byte[], byte[]> {
        private final CallLog log;

        RecordingProducer(CallLog log, boolean autoComplete) {
            super(autoComplete, null, new ByteArraySerializer(), new ByteArraySerializer());
            this.log = log;
        }

        @Override
        public synchronized void flush() {
            log.add("producer.flush");
            super.flush();
        }

        @Override
        public void close(Duration timeout) {
            log.add("producer.close");
            super.close(timeout);
        }
    }

    /**
     * A {@link MockConsumer} that records commits and close into the shared log, waits out each poll
     * timeout (so an idle loop does not spin), and delivers the batches it is given. Its {@code close}
     * can be held shut by a gate to model a consumer whose close blocks.
     */
    static final class RecordingConsumer extends MockConsumer<byte[], byte[]> {
        private final CallLog log;
        private final TopicPartition partition = new TopicPartition(TOPIC, 0);
        private final CountDownLatch polled = new CountDownLatch(1);
        private final AtomicReference<Thread> pollThread = new AtomicReference<>();
        private volatile CountDownLatch closeGate;
        private volatile Exception commitAsyncFailure;
        private final Set<TopicPartition> assignedPartitions = new LinkedHashSet<>();
        private volatile ConsumerRebalanceListener driverListener;
        private long nextOffset;

        RecordingConsumer(CallLog log) {
            super("earliest");
            this.log = log;
        }

        /** The listener the engine's loop passed to {@code subscribe}; a test can call it to model callbacks MockConsumer cannot (lost). */
        ConsumerRebalanceListener driverListener() {
            return driverListener;
        }

        @Override
        public synchronized void subscribe(Collection<String> topics, ConsumerRebalanceListener listener) {
            this.driverListener = listener;
            super.subscribe(topics, listener);
        }

        /** Runs {@code task} on the loop thread inside one poll (the way {@code MockConsumer} schedules it). */
        synchronized void onNextPoll(Runnable task) {
            schedulePollTask(task);
        }

        /**
         * Schedules one poll that assigns {@code partitions} (as a rebalance would) and returns
         * {@code records}, which carry their own topic, partition and offset.
         */
        @SafeVarargs
        final synchronized void deliverRecords(List<TopicPartition> partitions,
                                               ConsumerRecord<byte[], byte[]>... records) {
            schedulePollTask(() -> {
                assignAll(partitions);
                for (ConsumerRecord<byte[], byte[]> consumerRecord : records) {
                    addRecord(consumerRecord);
                }
            });
        }

        // Called on the loop thread, inside a poll task; the topics must already be subscribed.
        void assignAll(List<TopicPartition> partitions) {
            if (assignedPartitions.addAll(partitions)) {
                rebalance(List.copyOf(assignedPartitions));
                Map<TopicPartition, Long> beginnings = new HashMap<>();
                for (TopicPartition tp : assignedPartitions) {
                    beginnings.put(tp, 0L);
                }
                updateBeginningOffsets(beginnings);
            }
        }

        /** Makes {@code close} block, ignoring interrupts, until the gate opens. */
        void holdCloseUntil(CountDownLatch gate) {
            this.closeGate = gate;
        }

        /**
         * Schedules one poll that returns {@code frames} as a single batch. The first call also
         * assigns the partition, as a rebalance would.
         */
        synchronized void deliver(byte[]... frames) {
            schedulePollTask(() -> {
                assignAll(List.of(partition));
                for (byte[] frame : frames) {
                    addRecord(new ConsumerRecord<>(TOPIC, 0, nextOffset++, null, frame));
                }
            });
        }

        boolean awaitFirstPoll(Duration timeout) throws InterruptedException {
            return polled.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        Thread pollThread() {
            return pollThread.get();
        }

        @Override
        public ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
            pollThread.compareAndSet(null, Thread.currentThread());
            LockSupport.parkNanos(timeout.toNanos());
            ConsumerRecords<byte[], byte[]> records = pollNow();
            polled.countDown();
            return records;
        }

        private synchronized ConsumerRecords<byte[], byte[]> pollNow() {
            return super.poll(Duration.ZERO);
        }

        /** Makes the next {@code commitAsync} report {@code failure} to its callback instead of committing. */
        void failNextCommitAsync(Exception failure) {
            this.commitAsyncFailure = failure;
        }

        @Override
        public synchronized void commitAsync(OffsetCommitCallback callback) {
            log.add("consumer.commitAsync");
            Exception failure = commitAsyncFailure;
            if (failure != null) {
                commitAsyncFailure = null;
                callback.onComplete(Map.of(), failure);
                return;
            }
            super.commitAsync(callback);
        }

        @Override
        public synchronized void commitSync(Duration timeout) {
            log.add("consumer.commitSync");
            super.commitSync(timeout);
        }

        @Override
        public void close(CloseOptions options) {
            log.add("consumer.close");
            CountDownLatch gate = closeGate;
            while (gate != null && gate.getCount() > 0) {
                try {
                    gate.await();
                } catch (InterruptedException _) {
                    // a consumer whose close ignores interrupts: keep waiting for the gate
                    Thread.interrupted();
                }
            }
            super.close(options);
        }
    }
}
