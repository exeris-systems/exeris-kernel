CREATE TABLE IF NOT EXISTS exeris_outbox (
    outbox_seq BIGSERIAL NOT NULL,
    id UUID PRIMARY KEY,
    aggregate_id TEXT NOT NULL,
    aggregate_type TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload BYTEA NOT NULL,
    occurred_at BIGINT NOT NULL,
    published_at BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_exeris_outbox_seq
    ON exeris_outbox (outbox_seq);

CREATE INDEX IF NOT EXISTS idx_exeris_outbox_unpublished
    ON exeris_outbox (published_at, outbox_seq);

CREATE INDEX IF NOT EXISTS idx_exeris_outbox_aggregate
    ON exeris_outbox (aggregate_id);

CREATE TABLE IF NOT EXISTS exeris_outbox_dlq (
    id UUID PRIMARY KEY,
    stream_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload BYTEA NOT NULL,
    occurred_at BIGINT NOT NULL,
    failure_reason TEXT NOT NULL,
    moved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_exeris_outbox_dlq_moved_at
    ON exeris_outbox_dlq (moved_at);
