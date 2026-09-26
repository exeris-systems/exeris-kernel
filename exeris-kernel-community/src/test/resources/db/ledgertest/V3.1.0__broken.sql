-- Fails outright: the table does not exist. Used to prove the migration before it stays committed.
INSERT INTO exeris_no_such_table (id) VALUES (1);
