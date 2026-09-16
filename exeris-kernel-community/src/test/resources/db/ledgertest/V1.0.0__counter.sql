-- Deliberately NOT idempotent. The INSERT is unguarded, so a second application adds a second row.
-- A migration written with IF NOT EXISTS everywhere cannot fail an apply-once test and therefore
-- cannot prove one (ADR-073 Engineering Protocol).
CREATE TABLE IF NOT EXISTS ledger_probe (id INT);
INSERT INTO ledger_probe (id) VALUES (1);
