-- ADR-101 Ruling 1: the isolation ordinal a publication was made under is carried by the outbox and
-- by the durable event log, so that the relay keeps the scope the publisher recorded.
--
-- The column is NOT NULL with a default of 0, and 0 is the unscoped value. A row written before this
-- column existed was published with no isolation scope, so the default is the true state of that
-- row and not a fabricated identity. Both statements are idempotent, because the runner replays
-- every resource on every boot.
ALTER TABLE exeris_outbox
    ADD COLUMN IF NOT EXISTS isolation_ordinal INT NOT NULL DEFAULT 0;

ALTER TABLE exeris_event_log
    ADD COLUMN IF NOT EXISTS isolation_ordinal INT NOT NULL DEFAULT 0
