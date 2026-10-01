-- Same version key as drift_a, different bytes: this is what an edited migration looks like to the
-- ledger, and the checksum is what turns it from invisible into a refused boot.
CREATE TABLE IF NOT EXISTS drift_probe (id INT);
