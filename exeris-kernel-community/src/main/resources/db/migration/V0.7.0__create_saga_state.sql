-- ADR-013 / FLOW-103: durable saga state for distributed FlowSnapshotStore.
--
-- Composite PK on (instance_id_most, instance_id_least) is the 128-bit flow instance UUID
-- split into two BIGINT halves. This avoids any UUID-typed column dependency and keeps the
-- schema portable across Postgres and H2 (developer-loop fallback).
--
-- compensation_stack is packed BYTEA (4 bytes per int, big-endian, length stack_pointer * 4)
-- rather than INT[] for cross-database portability — H2 does not support native int arrays.
--
-- schema_version is the monotonic optimistic-locking version (see FlowSnapshot.SCHEMA_VERSION_INITIAL
-- and ADR-013 §5). Durable stores MUST advance it on every accepted write and reject writes
-- whose incoming value does not match the on-disk row, raising EX-FLOW-7002 with
-- phase=OPTIMISTIC_LOCK_CONFLICT.

CREATE TABLE IF NOT EXISTS exeris_saga_state (
    instance_id_most    BIGINT       NOT NULL,
    instance_id_least   BIGINT       NOT NULL,
    definition_name     TEXT         NOT NULL,
    current_step        INT          NOT NULL,
    state               TEXT         NOT NULL,
    last_update         TIMESTAMP WITH TIME ZONE NOT NULL,
    timeout_at          TIMESTAMP WITH TIME ZONE,
    compensation_stack  BYTEA        NOT NULL,
    stack_pointer       INT          NOT NULL,
    opaque_state        BYTEA,
    schema_version      BIGINT       NOT NULL DEFAULT 1,
    PRIMARY KEY (instance_id_most, instance_id_least)
);

-- Partial index for the listParked() recovery enumeration. Predicate matches the
-- FlowState.PARKED.name() string emitted by JdbcFlowSnapshotStore.
CREATE INDEX IF NOT EXISTS idx_exeris_saga_state_parked
    ON exeris_saga_state (last_update)
    WHERE state = 'PARKED';
