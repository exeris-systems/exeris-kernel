-- ADR-064: a parked saga resumes on the definition version it parked under, not on whichever
-- version happens to be registered when it wakes.
--
-- NOT NULL DEFAULT 0, and the 0 is load-bearing: it is FlowSnapshot.VERSION_ABSENT. Definition
-- versions start at 1, so 0 can never name a real version. A row written before this column existed
-- backfills to "no version recorded" and is refused fail-closed on resume, exactly as the
-- step-identity column added in V0.11.0 refuses a row carrying no identity. Defaulting to 1 would
-- instead assert that every parked saga belongs to the first version, which is the guess this epic
-- exists to stop making.
--
-- Two constraints this file learned the hard way, both enforced by the runner rather than by a
-- linter. It replays every resource on every boot with no applied-migration ledger, so the DDL must
-- be idempotent (V0.11.0 does the same). And it executes each resource as ONE statement after a
-- quote-aware comment strip, so a file may carry no second statement and no apostrophe in its
-- comments.
ALTER TABLE exeris_saga_state
    ADD COLUMN IF NOT EXISTS definition_version INTEGER NOT NULL DEFAULT 0;
