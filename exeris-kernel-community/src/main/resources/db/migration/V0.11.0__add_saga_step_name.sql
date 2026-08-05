-- ADR-062: record the identity of the step a parked saga stopped at.
--
-- Resume used to replay `current_step`, a position, into whatever plan is registered under the
-- definition name. A same-arity reorder left that index in range and bound the saga to a different
-- step. `step_name` is what the index cannot be: stable across redeployments.
--
-- Deliberately NULLABLE. Rows written before 0.11 have no identity, and that is a fact the runtime
-- must be able to see: it rejects such a snapshot fail-closed on wake rather than replaying it by
-- position. A NOT NULL column with a default would fabricate an identity and hide exactly the case
-- the guard exists to catch.
ALTER TABLE exeris_saga_state
    ADD COLUMN IF NOT EXISTS step_name VARCHAR(255);
