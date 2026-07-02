-- ADR-049: durable per-stream event log backing the Community EventStreamAppender / EventStreamReader.
--
-- This is the ordering / optimistic-concurrency boundary of the "one log, four views" model
-- (streaming / sourcing / KV / distributed). It is deliberately SEPARATE from exeris_outbox:
-- the outbox is a transactional DELIVERY drain (rows are marked published / moved to DLQ / removed),
-- whereas this log is an append-only, replayable, per-stream-ordered source of truth.
--
-- Stream and event UUIDs are stored as high/low BIGINT pairs (no UUID-typed column), matching
-- StreamId / EventDescriptor and keeping the schema portable across Postgres and H2.
--
-- committed_sequence is the 1-based per-stream monotonic head (ADR-049). The composite PK
-- (stream_id_high, stream_id_low, committed_sequence) is BOTH the ordering key for replay AND the
-- uniqueness constraint the optimistic-concurrency append relies on: a concurrent writer racing on
-- the same next sequence loses with an integrity-constraint violation (SQLSTATE class 23).

CREATE TABLE IF NOT EXISTS exeris_event_log (
    stream_id_high      BIGINT NOT NULL,
    stream_id_low       BIGINT NOT NULL,
    committed_sequence  BIGINT NOT NULL,
    event_id_high       BIGINT NOT NULL,
    event_id_low        BIGINT NOT NULL,
    event_type_ordinal  INT    NOT NULL,
    event_flags         INT    NOT NULL,
    occurred_at         BIGINT NOT NULL,
    payload             BYTEA  NOT NULL,
    PRIMARY KEY (stream_id_high, stream_id_low, committed_sequence)
);

-- replayFrom(streamId, Instant) narrows to a stream and filters by occurred_at, ordered by the PK sequence.
CREATE INDEX IF NOT EXISTS idx_exeris_event_log_stream_time
    ON exeris_event_log (stream_id_high, stream_id_low, occurred_at);

-- replayByType(eventType, Instant): cross-stream scan by ordinal + time.
CREATE INDEX IF NOT EXISTS idx_exeris_event_log_type_time
    ON exeris_event_log (event_type_ordinal, occurred_at);
