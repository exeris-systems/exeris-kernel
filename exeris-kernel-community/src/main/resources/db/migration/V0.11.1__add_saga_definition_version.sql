-- ADR-064: a parked saga resumes on the definition version it parked under, not on whichever
-- version happens to be registered when it wakes.
--
-- Nullable on purpose, and deliberately WITHOUT a default. A row written before this column existed
-- must read back as "no version recorded" so the resume guard can refuse it, exactly as the
-- step-identity column added in V0.11.0 refuses a row that carries no identity. Backfilling a
-- default of 1 would silently assert that every parked saga belongs to the first version — the
-- guess this epic exists to stop making.
ALTER TABLE exeris_saga_state
    ADD COLUMN definition_version INTEGER;

COMMENT ON COLUMN exeris_saga_state.definition_version IS
    'FlowDefinition version this saga parked under; NULL means the row predates ADR-064 and is refused on resume.';
