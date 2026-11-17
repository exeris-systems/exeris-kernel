/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventTypeSpec;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Broker-level verification of the Kafka engine's 0.13.2 additions against a real broker: the raw
 * record channel, manual commit after handlers, the ordered clean stop, the rebalance hooks and the
 * send-failure latch. Every engine is configured as the consuming benchmark arm is, through the
 * override maps: producer {@code enable.idempotence=true}, {@code acks=all}; consumer
 * {@code enable.auto.commit=false}, {@code auto.offset.reset=earliest}, the cooperative-sticky
 * assignor and {@code group.protocol=classic}. Topics are created explicitly, with several
 * partitions, and every case uses its own topics and consumer group.
 *
 * <p>Tagged {@code @Tag("integration")}; it runs only in the Kafka integration gate.
 *
 * @since 0.13.2
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Community :: Kafka — raw records, manual commit and rebalance hooks (Testcontainers Kafka 3.x)")
class CommunityKafkaRawRecordIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    private static final Duration AWAIT = Duration.ofSeconds(60);
    private static final int VALUE_SIZE = 1024;

    private final List<AutoCloseable> resources = new ArrayList<>();
    private Admin admin;
    private String run;

    @BeforeEach
    void setUp() {
        run = UUID.randomUUID().toString().substring(0, 8);
        admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()));
    }

    @AfterEach
    void tearDown() throws Exception {
        Exception first = null;
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception ex) {
                if (first == null) {
                    first = ex;
                }
            }
        }
        resources.clear();
        admin.close(Duration.ofSeconds(10));
        if (first != null) {
            throw first;
        }
    }

    // =========================================================================
    // a. raw round trip
    // =========================================================================

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("a. headerless records reach the raw handler verbatim and are re-sent verbatim to the asked partition")
    void rawRoundTrip() throws Exception {
        String in = topic("in");
        String out = topic("out");
        createTopic(in, 3);
        createTopic(out, 3);
        int n = 30;

        List<Sent> sent = produce(in, n, 3);
        Map<Integer, Sent> byIndex = new HashMap<>();
        for (Sent s : sent) {
            byIndex.put(s.index(), s);
        }

        KafkaEventEngine engine = engine(group("roundtrip"));
        List<ConsumerRecord<byte[], byte[]>> handled = new CopyOnWriteArrayList<>();
        Set<Thread> handlerThreads = ConcurrentHashMap.newKeySet();
        CountDownLatch acked = new CountDownLatch(n);
        List<Exception> sendErrors = new CopyOnWriteArrayList<>();
        engine.records().subscribe(in, rec -> {
            handled.add(rec);
            handlerThreads.add(Thread.currentThread());
            engine.records().send(new ProducerRecord<>(out, rec.partition(), rec.key(), rec.value()),
                    (md, ex) -> {
                        if (ex != null) {
                            sendErrors.add(ex);
                        }
                        acked.countDown();
                    });
        });
        engine.start();

        assertThat(acked.await(AWAIT.toSeconds(), TimeUnit.SECONDS))
                .as("every handled record's re-send MUST be acknowledged").isTrue();
        assertThat(sendErrors).as("no re-send may fail").isEmpty();

        // The consume direction: each input reached the handler byte-identical with its coordinates.
        assertThat(handled).hasSize(n);
        for (ConsumerRecord<byte[], byte[]> rec : handled) {
            assertThat(rec.topic()).isEqualTo(in);
            assertThat(rec.value()).hasSize(VALUE_SIZE);
            Sent expected = byIndex.get(indexOf(rec.value()));
            assertThat(expected).as("the handler saw a value that was never produced").isNotNull();
            assertThat(rec.value()).containsExactly(expected.value());
            assertThat(rec.key()).containsExactly(expected.key());
            assertThat(rec.partition()).isEqualTo(expected.partition());
            assertThat(rec.offset()).isEqualTo(expected.offset());
            assertThat(rec.timestamp()).isEqualTo(expected.timestamp());
            assertThat(rec.headers().toArray()).as("an input record carries no header").isEmpty();
        }
        assertThat(handled.stream().map(r -> indexOf(r.value())).distinct().count()).isEqualTo(n);
        assertThat(handlerThreads).hasSize(1);
        assertThat(engine.stats().processedTotal())
                .as("raw records are not counted as processed events").isZero();
        assertThat(engine.loopFailure()).isEmpty();

        // The produce direction: exactly n records on out, verbatim, on the partition asked for.
        List<ConsumerRecord<byte[], byte[]>> outRecords = readAll(out);
        assertThat(outRecords).hasSize(n);
        Set<Integer> seen = new HashSet<>();
        for (ConsumerRecord<byte[], byte[]> rec : outRecords) {
            assertThat(rec.value()).as("no Exeris header is prepended to a raw send").hasSize(VALUE_SIZE);
            Sent expected = byIndex.get(indexOf(rec.value()));
            assertThat(expected).isNotNull();
            assertThat(rec.value()).containsExactly(expected.value());
            assertThat(rec.key()).containsExactly(expected.key());
            assertThat(rec.partition()).isEqualTo(expected.partition());
            assertThat(rec.headers().toArray()).as("a raw send adds no isolation header").isEmpty();
            seen.add(expected.index());
        }
        assertThat(seen).hasSize(n);
    }

    // =========================================================================
    // b. committed offsets after a clean stop, and after a handler failure
    // =========================================================================

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("b1. after a clean stop the group's committed offsets sum to the number of inputs")
    void cleanStopCommitsEverything() throws Exception {
        String in = topic("in");
        createTopic(in, 3);
        int n = 40;
        produce(in, n, 3);
        String group = group("clean");

        KafkaEventEngine engine = engine(group);
        CountDownLatch handled = new CountDownLatch(n);
        engine.records().subscribe(in, rec -> handled.countDown());
        engine.start();
        assertThat(handled.await(AWAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        engine.close();

        assertThat(engine.loopFailure()).isEmpty();
        assertThat(engine.loop().isRunning()).isFalse();
        Map<TopicPartition, Long> committed = committed(group, in);
        assertThat(committed.values().stream().mapToLong(Long::longValue).sum())
                .as("a clean stop commits exactly the handled positions").isEqualTo(n);
        assertThat(committed).hasSize(3);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @DisplayName("b2. a handler failure on record k leaves k uncommitted, and a new engine in the group gets k again")
    void handlerFailureIsRedelivered() throws Exception {
        String in = topic("in");
        createTopic(in, 3);
        int n = 20;
        int k = 7;
        List<Sent> sent = produce(in, n, 3);
        Sent failing = sent.get(k);
        String group = group("fail");

        KafkaEventEngine first = engine(group);
        Set<Integer> firstHandled = ConcurrentHashMap.newKeySet();
        RuntimeException boom = new IllegalStateException("handler failure on record " + k);
        first.records().subscribe(in, rec -> {
            int index = indexOf(rec.value());
            if (index == k) {
                throw boom;
            }
            firstHandled.add(index);
        });
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<Throwable> reported = new AtomicReference<>();
        first.onLoopFailure(cause -> {
            reported.set(cause);
            failed.countDown();
        });
        first.start();

        assertThat(failed.await(AWAIT.toSeconds(), TimeUnit.SECONDS))
                .as("the handler failure MUST end the loop and reach the failure listener").isTrue();
        assertThat(reported.get()).isSameAs(boom);
        assertThat(first.loopFailure()).containsSame(boom);
        assertThat(first.loop().isRunning()).isFalse();
        assertThat(first.stats().loopRunning()).isFalse();
        assertThat(first.stats().failedTotal())
                .as("failedTotal counts events; a raw record's handler failure is not one").isZero();
        assertThat(firstHandled).doesNotContain(k);
        first.close();

        Map<TopicPartition, Long> committed = committed(group, in);
        TopicPartition kPartition = new TopicPartition(in, failing.partition());
        assertThat(committed.getOrDefault(kPartition, 0L))
                .as("the committed position of k's partition MUST NOT pass record k (offset %d)",
                        failing.offset())
                .isLessThanOrEqualTo(failing.offset());
        assertThat(committed.values().stream().mapToLong(Long::longValue).sum()).isLessThan(n);

        KafkaEventEngine second = engine(group);
        Set<Integer> secondHandled = ConcurrentHashMap.newKeySet();
        CountDownLatch gotK = new CountDownLatch(1);
        second.records().subscribe(in, rec -> {
            int index = indexOf(rec.value());
            secondHandled.add(index);
            if (index == k) {
                gotK.countDown();
            }
        });
        second.start();
        assertThat(gotK.await(AWAIT.toSeconds(), TimeUnit.SECONDS))
                .as("record k MUST be redelivered to a new engine in the same group").isTrue();
        // Nothing is lost between the two engines: what the first did not commit, the second gets.
        awaitTrue("the two engines together handled every input", () -> {
            Set<Integer> union = new HashSet<>(firstHandled);
            union.addAll(secondHandled);
            return union.size() == n;
        });
    }

    // =========================================================================
    // c. per-partition order on the loop thread
    // =========================================================================

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("c. offsets arrive strictly increasing per partition, all on one loop thread")
    void perPartitionOrderOnTheLoopThread() throws Exception {
        String in = topic("in");
        createTopic(in, 4);
        int n = 200;
        produce(in, n, 4);

        KafkaEventEngine engine = engine(group("order"));
        Map<Integer, List<Long>> offsets = new ConcurrentHashMap<>();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        CountDownLatch handled = new CountDownLatch(n);
        engine.records().subscribe(in, rec -> {
            threads.add(Thread.currentThread());
            offsets.computeIfAbsent(rec.partition(), p -> new CopyOnWriteArrayList<>()).add(rec.offset());
            handled.countDown();
        });
        engine.start();
        assertThat(handled.await(AWAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        assertThat(threads).hasSize(1);
        Thread loopThread = threads.iterator().next();
        assertThat(loopThread).isNotSameAs(Thread.currentThread());
        assertThat(loopThread.getName()).startsWith("exeris-kafka-consumer-");
        assertThat(offsets).hasSize(4);
        for (Map.Entry<Integer, List<Long>> entry : offsets.entrySet()) {
            List<Long> list = entry.getValue();
            assertThat(list).as("partition %d", entry.getKey()).hasSize(n / 4);
            for (int i = 0; i < list.size(); i++) {
                assertThat(list.get(i)).as("partition %d position %d", entry.getKey(), i).isEqualTo((long) i);
            }
        }
    }

    // =========================================================================
    // d. rebalance hooks with two engines in one group
    // =========================================================================

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @DisplayName("d1. a second engine takes a strict subset cooperatively, and gives it back on close")
    void cooperativeRebalanceBetweenTwoEngines() throws Exception {
        String in = topic("in");
        createTopic(in, 4);
        Set<TopicPartition> all = partitions(in, 4);
        String group = group("rebalance");

        KafkaEventEngine first = engine(group);
        Ownership firstOwns = new Ownership();
        first.records().rebalanceListener(firstOwns);
        first.records().subscribe(in, rec -> { });
        first.start();
        awaitTrue("the first engine owns every partition", () -> firstOwns.owned().equals(all));
        assertThat(firstOwns.revoked()).isEmpty();

        KafkaEventEngine second = engine(group);
        Ownership secondOwns = new Ownership();
        second.records().rebalanceListener(secondOwns);
        second.records().subscribe(in, rec -> { });
        second.start();
        awaitTrue("the two engines split the partitions disjointly", () -> {
            Set<TopicPartition> a = firstOwns.owned();
            Set<TopicPartition> b = secondOwns.owned();
            Set<TopicPartition> union = new HashSet<>(a);
            union.addAll(b);
            Set<TopicPartition> both = new HashSet<>(a);
            both.retainAll(b);
            return !a.isEmpty() && !b.isEmpty() && union.equals(all) && both.isEmpty();
        });
        List<Set<TopicPartition>> revocations = firstOwns.revoked();
        assertThat(revocations).as("the first engine was told of a revocation").isNotEmpty();
        for (Set<TopicPartition> revoked : revocations) {
            assertThat(revoked).as("a cooperative revocation is a strict non-empty subset")
                    .isNotEmpty().isSubsetOf(all).hasSizeLessThan(all.size());
        }
        assertThat(secondOwns.assignedCalls()).isPositive();
        assertThat(firstOwns.lost()).isEmpty();

        second.close();
        awaitTrue("closing the second engine gives its partitions back to the first",
                () -> firstOwns.owned().equals(all));
        assertThat(first.loopFailure()).isEmpty();
        assertThat(second.loopFailure()).isEmpty();
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("d2. a listener set after the assignment gets it replayed exactly once")
    void lateListenerGetsTheAssignmentOnce() throws Exception {
        String in = topic("in");
        createTopic(in, 4);
        Set<TopicPartition> all = partitions(in, 4);
        produce(in, 4, 4);

        KafkaEventEngine engine = engine(group("late"));
        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        engine.records().subscribe(in, rec -> seen.add(indexOf(rec.value())));
        engine.start();
        // A record from every partition has been handled, so the engine holds all four.
        awaitTrue("one record from every partition handled", () -> seen.size() == 4);

        Ownership late = new Ownership();
        engine.records().rebalanceListener(late);
        awaitTrue("the late listener is told of the current assignment", () -> late.assignedCalls() == 1);
        assertThat(late.owned()).isEqualTo(all);

        // A barrier, not a sleep: once one more record is handled, the loop has iterated past the
        // replay point several times.
        produceOne(in, 0, 100);
        awaitTrue("the barrier record is handled", () -> seen.contains(100));
        assertThat(late.assignedCalls()).as("the replay happens once").isEqualTo(1);

        // Replacing the listener replays to the new one, and not again to the old one.
        Ownership replacement = new Ownership();
        engine.records().rebalanceListener(replacement);
        awaitTrue("the replacement listener is told of the current assignment",
                () -> replacement.assignedCalls() == 1);
        produceOne(in, 1, 101);
        awaitTrue("the second barrier record is handled", () -> seen.contains(101));
        assertThat(replacement.assignedCalls()).isEqualTo(1);
        assertThat(replacement.owned()).isEqualTo(all);
        assertThat(late.assignedCalls()).isEqualTo(1);
    }

    // =========================================================================
    // e. partitionsFor
    // =========================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("e. partitionsFor returns the real partition count")
    void partitionsForReturnsTheRealCount() throws Exception {
        String five = topic("five");
        String two = topic("two");
        createTopic(five, 5);
        createTopic(two, 2);
        KafkaEventEngine engine = engine(group("partitions"));

        assertThat(engine.records().partitionsFor(five)).isEqualTo(5);
        assertThat(engine.records().partitionsFor(two)).isEqualTo(2);
    }

    // =========================================================================
    // f. framed and raw side by side in manual mode
    // =========================================================================

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("f. a framed event type and a raw topic both deliver, and a clean stop commits both")
    void framedAndRawSideBySide() throws Exception {
        String framedTopic = topic("framed");
        String rawTopic = topic("raw");
        createTopic(framedTopic, 3);
        createTopic(rawTopic, 3);
        int m = 12;
        String type = "RawSideBySide_" + run;
        int ordinal = 31_000;
        String group = group("sidebyside");

        KafkaEventEngine engine = engine(group);
        engine.registry().register(EventTypeSpec.ofPersistent(type, ordinal, framedTopic));
        Set<Integer> framedTags = ConcurrentHashMap.newKeySet();
        AtomicInteger framedCalls = new AtomicInteger();
        CountDownLatch framedDone = new CountDownLatch(m);
        engine.bus().subscribe(type, (descriptor, payload) -> {
            try (payload) {
                framedCalls.incrementAndGet();
                framedTags.add((int) payload.segment().get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L));
                framedDone.countDown();
            }
        });
        Set<String> rawTopicsSeen = ConcurrentHashMap.newKeySet();
        AtomicInteger rawCalls = new AtomicInteger();
        CountDownLatch rawDone = new CountDownLatch(m);
        engine.records().subscribe(rawTopic, rec -> {
            rawTopicsSeen.add(rec.topic());
            rawCalls.incrementAndGet();
            rawDone.countDown();
        });
        engine.start();

        for (int i = 0; i < m; i++) {
            UUID id = UUID.randomUUID();
            EventDescriptor descriptor = EventDescriptor.of(
                    id.getMostSignificantBits(), id.getLeastSignificantBits(),
                    id.getLeastSignificantBits(), id.getMostSignificantBits(),
                    ordinal, EventDescriptor.FLAG_PERSISTENT, System.currentTimeMillis());
            engine.bus().publishAndAwait(descriptor, KafkaHeapEventPayload.wrap(new byte[] {(byte) i}));
        }
        produce(rawTopic, m, 3);

        assertThat(framedDone.await(AWAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(rawDone.await(AWAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        engine.close();

        assertThat(engine.loopFailure()).isEmpty();
        assertThat(framedCalls.get()).isEqualTo(m);
        assertThat(framedTags).hasSize(m);
        assertThat(rawCalls.get()).isEqualTo(m);
        assertThat(rawTopicsSeen).as("the raw handler sees only its own topic").containsExactly(rawTopic);
        assertThat(engine.stats().processedTotal())
                .as("framed events are counted, raw records are not").isEqualTo(m);
        assertThat(sum(committed(group, framedTopic))).as("framed topic committed").isEqualTo(m);
        assertThat(sum(committed(group, rawTopic))).as("raw topic committed").isEqualTo(m);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @DisplayName("f2. manual mode: a failing framed handler ends the loop, is counted, and its event is redelivered")
    void framedHandlerFailureInManualMode() throws Exception {
        String framedTopic = topic("framedfail");
        createTopic(framedTopic, 1);
        String type = "RawFramedFail_" + run;
        int ordinal = 31_001;
        int m = 6;
        int k = 3;
        String group = group("framedfail");

        KafkaEventEngine first = engine(group);
        first.registry().register(EventTypeSpec.ofPersistent(type, ordinal, framedTopic));
        Set<Integer> firstTags = ConcurrentHashMap.newKeySet();
        Set<Thread> handlerThreads = ConcurrentHashMap.newKeySet();
        first.bus().subscribe(type, (descriptor, payload) -> {
            try (payload) {
                handlerThreads.add(Thread.currentThread());
                int tag = payload.segment().get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L);
                if (tag == k) {
                    throw new IllegalStateException("framed handler failure on tag " + k);
                }
                firstTags.add(tag);
            }
        });
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<Throwable> cause = new AtomicReference<>();
        first.onLoopFailure(t -> {
            cause.set(t);
            failed.countDown();
        });
        first.start();
        for (int i = 0; i < m; i++) {
            publishFramed(first, ordinal, i);
        }

        assertThat(failed.await(AWAIT.toSeconds(), TimeUnit.SECONDS))
                .as("in manual mode a failing framed handler MUST end the loop").isTrue();
        assertThat(cause.get()).isInstanceOf(eu.exeris.kernel.spi.exceptions.events.EventBusException.class);
        assertThat(first.stats().failedTotal()).isEqualTo(1L);
        assertThat(first.stats().loopRunning()).isFalse();
        assertThat(first.loop().isRunning()).isFalse();
        assertThat(handlerThreads).hasSize(1);
        assertThat(firstTags).doesNotContain(k);
        first.close();
        assertThat(committed(group, framedTopic).getOrDefault(new TopicPartition(framedTopic, 0), 0L))
                .as("the failing event's batch is not committed").isLessThanOrEqualTo(k);

        KafkaEventEngine second = engine(group);
        second.registry().register(EventTypeSpec.ofPersistent(type, ordinal, framedTopic));
        CountDownLatch gotK = new CountDownLatch(1);
        second.bus().subscribe(type, (descriptor, payload) -> {
            try (payload) {
                if (payload.segment().get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L) == k) {
                    gotK.countDown();
                }
            }
        });
        second.start();
        assertThat(gotK.await(AWAIT.toSeconds(), TimeUnit.SECONDS))
                .as("the event whose handler failed is redelivered to the group").isTrue();
    }

    private static void publishFramed(KafkaEventEngine engine, int ordinal, int tag) throws InterruptedException {
        UUID id = UUID.randomUUID();
        // One stream for every event, so one partition and offset order.
        EventDescriptor descriptor = EventDescriptor.of(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                42L, 42L, ordinal, EventDescriptor.FLAG_PERSISTENT, System.currentTimeMillis());
        engine.bus().publishAndAwait(descriptor, KafkaHeapEventPayload.wrap(new byte[] {(byte) tag}));
    }

    // =========================================================================
    // g. an asynchronous raw send failure in manual mode
    // =========================================================================

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    @DisplayName("g. a broker-rejected raw send counts, ends the loop, and its batch is not committed")
    void asyncSendFailureEndsTheLoop() throws Exception {
        String in = topic("in");
        String out = topic("out");
        String small = topic("small");
        createTopic(in, 1);
        createTopic(out, 1);
        // The broker refuses any batch above 4 KiB on this topic; the producer's own limit is 1 MiB,
        // so the send is accepted by the client and fails asynchronously on the broker's answer.
        admin.createTopics(List.of(new NewTopic(small, 1, (short) 1)
                .configs(Map.of("max.message.bytes", "4096")))).all().get(30, TimeUnit.SECONDS);
        int n = 10;
        int k = 4;
        List<Sent> sent = produce(in, n, 1);
        Sent failing = sent.get(k);
        String group = group("sendfail");

        KafkaEventEngine engine = engine(group);
        AtomicReference<Exception> callbackError = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        Set<Integer> handled = ConcurrentHashMap.newKeySet();
        engine.records().subscribe(in, rec -> {
            int index = indexOf(rec.value());
            handled.add(index);
            handlerThread.set(Thread.currentThread());
            if (index != k) {
                engine.records().send(new ProducerRecord<>(out, rec.key(), rec.value()), null);
                return;
            }
            CountDownLatch completed = new CountDownLatch(1);
            engine.records().send(new ProducerRecord<>(small, rec.key(), new byte[64 * 1024]), (md, ex) -> {
                callbackThread.set(Thread.currentThread());
                callbackError.set(ex);
                completed.countDown();
            });
            // Waits for its own send, so the latch is set before the batch ends: what is under test
            // is that the loop then refuses to commit, not the race with the I/O thread.
            try {
                if (!completed.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the oversized send never completed");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        });
        CountDownLatch failed = new CountDownLatch(1);
        engine.onLoopFailure(cause -> failed.countDown());
        engine.start();

        assertThat(failed.await(AWAIT.toSeconds(), TimeUnit.SECONDS))
                .as("a failed raw send MUST end the loop").isTrue();
        assertThat(callbackError.get()).isInstanceOf(RecordTooLargeException.class);
        assertThat(callbackThread.get())
                .as("the failure was reported asynchronously, not on the sending thread")
                .isNotSameAs(handlerThread.get());
        assertThat(engine.records().sendFailedTotal()).isEqualTo(1L);
        assertThat(engine.loopFailure()).get().isInstanceOf(IllegalStateException.class);
        assertThat(engine.loop().isRunning()).isFalse();
        assertThat(engine.stats().failedTotal()).as("no handler failed").isZero();
        assertThat(engine.stats().publishFailedTotal()).as("raw sends are not engine publishes").isZero();
        engine.close();

        TopicPartition partition = new TopicPartition(in, 0);
        assertThat(committed(group, in).getOrDefault(partition, 0L))
                .as("the batch holding the failed send's record (offset %d) is not committed", failing.offset())
                .isLessThanOrEqualTo(failing.offset());

        KafkaEventEngine second = engine(group);
        CountDownLatch gotK = new CountDownLatch(1);
        second.records().subscribe(in, rec -> {
            if (indexOf(rec.value()) == k) {
                gotK.countDown();
            }
        });
        second.start();
        assertThat(gotK.await(AWAIT.toSeconds(), TimeUnit.SECONDS))
                .as("the record whose send failed is consumed again by the group").isTrue();
    }

    // =========================================================================
    // h. auto-commit mode: what a raw handler failure leaves committed
    // =========================================================================

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("h. auto mode: a raw handler failure ends the loop, and the failing batch is committed all the same")
    void autoModeHandlerFailureEndsTheLoopAtMostOnce() throws Exception {
        String in = topic("in");
        createTopic(in, 1);
        int n = 10;
        int k = 4;
        List<Sent> sent = produce(in, n, 1);
        String group = group("autofail");

        KafkaEventConfig cfg = KafkaEventConfig.defaults(KAFKA.getBootstrapServers(), group)
                .withCloseTimeout(Duration.ofSeconds(10));
        KafkaEventEngine engine = track(KafkaEventProvider.create(EventEngineConfig.communityDefaults(), cfg));
        engine.records().subscribe(in, rec -> {
            if (indexOf(rec.value()) == k) {
                throw new IllegalStateException("handler failure on record " + k);
            }
        });
        CountDownLatch failed = new CountDownLatch(1);
        engine.onLoopFailure(cause -> failed.countDown());
        engine.start();
        assertThat(failed.await(AWAIT.toSeconds(), TimeUnit.SECONDS))
                .as("a raw handler failure MUST end the loop in auto mode too").isTrue();
        assertThat(engine.loopFailure()).isPresent();
        assertThat(engine.loop().isRunning()).isFalse();
        engine.close();

        // KafkaRecordHandler: in auto-commit mode the Kafka client commits consumed positions when
        // the consumer closes, so the failing batch is committed — at-most-once, the opposite of
        // case b2. Record k is not delivered again.
        assertThat(committed(group, in).getOrDefault(new TopicPartition(in, 0), 0L))
                .as("auto mode commits past the failing record (k at offset %d)", sent.get(k).offset())
                .isGreaterThan(sent.get(k).offset());
    }

    // =========================================================================
    // Support
    // =========================================================================

    private record Sent(int index, int partition, long offset, long timestamp, byte[] key, byte[] value) { }

    /** Tracks the partitions a rebalance listener has been told it owns. */
    private static final class Ownership implements ConsumerRebalanceListener {
        private final Set<TopicPartition> owned = ConcurrentHashMap.newKeySet();
        private final List<Set<TopicPartition>> revoked = new CopyOnWriteArrayList<>();
        private final List<Set<TopicPartition>> lost = new CopyOnWriteArrayList<>();
        private final AtomicInteger assignedCalls = new AtomicInteger();

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            assignedCalls.incrementAndGet();
            owned.addAll(partitions);
        }

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            if (!partitions.isEmpty()) {
                revoked.add(Set.copyOf(partitions));
            }
            owned.removeAll(partitions);
        }

        @Override
        public void onPartitionsLost(Collection<TopicPartition> partitions) {
            lost.add(Set.copyOf(partitions));
            owned.removeAll(partitions);
        }

        Set<TopicPartition> owned() {
            return Set.copyOf(owned);
        }

        List<Set<TopicPartition>> revoked() {
            return List.copyOf(revoked);
        }

        List<Set<TopicPartition>> lost() {
            return List.copyOf(lost);
        }

        int assignedCalls() {
            return assignedCalls.get();
        }
    }

    private String topic(String name) {
        return "raw-it-" + name + "-" + run;
    }

    private String group(String name) {
        return "raw-it-" + name + "-" + run;
    }

    private void createTopic(String name, int partitions) throws Exception {
        admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1))).all().get(30, TimeUnit.SECONDS);
    }

    private static Set<TopicPartition> partitions(String topic, int count) {
        Set<TopicPartition> set = new HashSet<>();
        for (int p = 0; p < count; p++) {
            set.add(new TopicPartition(topic, p));
        }
        return Set.copyOf(set);
    }

    private KafkaEventEngine engine(String group) {
        KafkaEventConfig cfg = KafkaEventConfig.defaults(KAFKA.getBootstrapServers(), group)
                .withProducerOverrides(Map.of(
                        "enable.idempotence", "true",
                        "acks", "all"))
                .withConsumerOverrides(Map.of(
                        "enable.auto.commit", "false",
                        "auto.offset.reset", "earliest",
                        "partition.assignment.strategy", CooperativeStickyAssignor.class.getName(),
                        "group.protocol", "classic"))
                .withCloseTimeout(Duration.ofSeconds(10));
        return track(KafkaEventProvider.create(EventEngineConfig.communityDefaults(), cfg));
    }

    private <T extends AutoCloseable> T track(T resource) {
        resources.add(resource);
        return resource;
    }

    private KafkaProducer<byte[], byte[]> testProducer() {
        return track(new KafkaProducer<>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "key.serializer", ByteArraySerializer.class.getName(),
                "value.serializer", ByteArraySerializer.class.getName(),
                "acks", "all",
                "enable.idempotence", "true")));
    }

    /** Produces {@code n} headerless 1024-byte records, round-robin over the partitions, and waits for each ack. */
    private List<Sent> produce(String topic, int n, int partitions) throws Exception {
        KafkaProducer<byte[], byte[]> producer = testProducer();
        long base = System.currentTimeMillis() - 60_000L;
        List<Sent> sent = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte[] key = ("key-" + i).getBytes(StandardCharsets.UTF_8);
            byte[] value = value(i);
            long timestamp = base + i;
            RecordMetadata md = producer.send(
                    new ProducerRecord<>(topic, i % partitions, timestamp, key, value)).get(30, TimeUnit.SECONDS);
            sent.add(new Sent(i, md.partition(), md.offset(), timestamp, key, value));
        }
        return sent;
    }

    private void produceOne(String topic, int partition, int index) throws Exception {
        testProducer().send(new ProducerRecord<>(topic, partition,
                ("key-" + index).getBytes(StandardCharsets.UTF_8), value(index))).get(30, TimeUnit.SECONDS);
    }

    /** A 1024-byte value whose first four bytes are the index and whose rest is index-dependent. */
    private static byte[] value(int index) {
        byte[] value = new byte[VALUE_SIZE];
        for (int j = 4; j < value.length; j++) {
            value[j] = (byte) ((index * 31 + j * 7) & 0xFF);
        }
        ByteBuffer.wrap(value).putInt(0, index);
        return value;
    }

    private static int indexOf(byte[] value) {
        return ByteBuffer.wrap(value).getInt(0);
    }

    /** Reads a topic from the beginning up to its end offsets, which are fixed once every send is acknowledged. */
    private List<ConsumerRecord<byte[], byte[]>> readAll(String topic) {
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "key.deserializer", ByteArrayDeserializer.class.getName(),
                "value.deserializer", ByteArrayDeserializer.class.getName(),
                "enable.auto.commit", "false"))) {
            List<TopicPartition> tps = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition())).toList();
            consumer.assign(tps);
            consumer.seekToBeginning(tps);
            Map<TopicPartition, Long> end = consumer.endOffsets(tps);
            List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + AWAIT.toNanos();
            while (!reached(consumer, end) && System.nanoTime() < deadline) {
                ConsumerRecords<byte[], byte[]> batch = consumer.poll(Duration.ofMillis(250));
                batch.forEach(records::add);
            }
            assertThat(reached(consumer, end)).as("read %s up to its end offsets %s", topic, end).isTrue();
            return records;
        }
    }

    private static boolean reached(KafkaConsumer<byte[], byte[]> consumer, Map<TopicPartition, Long> end) {
        for (Map.Entry<TopicPartition, Long> e : end.entrySet()) {
            if (consumer.position(e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private Map<TopicPartition, Long> committed(String group, String topic) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> all = admin.listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata().get(30, TimeUnit.SECONDS);
        Map<TopicPartition, Long> result = new HashMap<>();
        all.forEach((tp, om) -> {
            if (tp.topic().equals(topic) && om != null) {
                result.put(tp, om.offset());
            }
        });
        return result;
    }

    private static long sum(Map<TopicPartition, Long> offsets) {
        return offsets.values().stream().mapToLong(Long::longValue).sum();
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out after " + AWAIT + " waiting until " + what);
            }
            TimeUnit.MILLISECONDS.sleep(20L);
        }
    }
}
