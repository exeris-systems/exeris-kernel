# ADR-073: The migration runner gets a schema-history ledger, and stops relying on every migration being idempotent

| Attribute       | Value                                                                       |
|:----------------|:-----------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                |
| **Deciders**    | Arkadiusz Przychocki                                                        |
| **Date**        | 2026-08-26                                                                  |
| **Scope**       | `kernel/persistence`                                                        |
| **Owning Repo** | `exeris-kernel`                                                             |
| **Driven By**   | v0.12 Stream C / T1-3 (1.0-blocking)                                        |
| **Compliance**  | [docs/subsystems/persistence.md](../subsystems/persistence.md) §Database Schema Management |

## Context and Problem Statement

`CommunityPersistenceMigrationRunner.runIfEnabled` executes **every** `V*.sql` on the classpath on
**every** boot, in order, inside **one** transaction, and records nothing about what it did. There is
no ledger, so there is no such thing as "already applied".

Apply-once exists, but only as a convention inside the SQL. Counted rather than assumed: the six
shipped migrations contain **14 DDL statements and all 14 are guarded** by `IF NOT EXISTS` or
`IF EXISTS`. Re-running them is a no-op because every author so far wrote them that way.

That holds until the first migration that cannot be written idempotently, and those are ordinary:

- a **data backfill** — `UPDATE … SET tenant_id = …` re-runs and re-writes rows an operator may have
  since corrected;
- a **`DROP COLUMN`** — `IF EXISTS` makes the second run a no-op, but there is no way to express
  "drop it once and never again" for the case where a later migration re-adds it;
- a **constraint tightening** — `ALTER TABLE … ADD CONSTRAINT` has no portable `IF NOT EXISTS`, so the
  second boot fails outright and the kernel does not start.

The failure mode is not symmetric with a normal bug. The backfill case is **silent**: the boot
succeeds, the application serves, and the data is wrong. The kernel has no way to notice, because it
has no record of the migration having run.

There is a second, quieter gap. Because a database's schema history is unrecorded, a database whose
schema has **drifted from the code** — a hand-applied hotfix, a restored snapshot from a different
release, an edited migration file — is indistinguishable from a healthy one. The runner re-runs the
scripts, the `IF NOT EXISTS` guards make them no-ops, and the boot is green.

The single transaction is the third problem, and it is a consequence of the first two rather than an
independent choice: with no ledger there is nothing to commit alongside a migration, so all-or-nothing
is the only coherent option. It also means one failing migration discards the work of every migration
before it, on every boot, forever.

## 🏁 The Decision

**1. An `exeris_schema_history` ledger, keyed by version, records what was applied.** One row per
migration: the version parsed from the resource name, the script name, a checksum of the resource, and
the time it was applied. The runner consults it before executing anything: a version already present
is **skipped**, not re-run. Apply-once stops being a property of how the SQL was written and becomes a
property of the runner.

The ledger table is created by the runner itself with `CREATE TABLE IF NOT EXISTS` before the ledger
is consulted, and it is deliberately **not** in the ledger. It is the one piece of schema whose
creation must stay idempotent, because there is nothing to record it in.

**2. A checksum mismatch refuses the boot.** If a version is present in the ledger but the resource on
the classpath now hashes differently, the migration file changed after it was applied — so the
database does not match the code that is about to run against it. The runner throws and the kernel
does not start.

This is the fail-closed choice and it is deliberate: **a database that no longer matches its code must
not look healthy.** The alternative — warn and continue — produces exactly the silent drift described
above, and an operator who wanted the change applied has an ordinary remedy (a new migration), while
an operator who did *not* has no way to learn about it otherwise.

**3. The checksum is over the resource with line endings normalised, and nothing else.** `\r\n` is
folded to `\n` before hashing. Without that, checking the repository out on Windows changes every
checksum and refuses every boot — a refusal with nothing wrong, which would teach operators to
distrust the mechanism. Nothing else is normalised: whitespace and comments are *inside* the
checksum, because an edit to a migration is an edit whether or not it changed the parse.

**4. One transaction per migration, with the ledger row committed alongside it.** The insert into
`exeris_schema_history` happens in the same transaction as the migration's statements, so the ledger
cannot claim something the database does not have, and the database cannot hold something the ledger
does not record. If migration 4 of 6 fails, 1–3 stay applied *and recorded*, 4 rolls back whole, the
boot fails, and the next boot resumes at 4 rather than replaying 1–3.

**5. An existing database is baselined by its first boot on this version, and the reason that is safe
is the property being retired.** A database that already has all six migrations has no ledger, so the
runner sees six unapplied versions and executes them. They are no-ops — because all 14 statements are
guarded — and the ledger is then correct. So the transition works *precisely because* the current set
is idempotent, which is the assumption this ADR exists to stop depending on. It is a one-time debt
being spent, not a mechanism, and it is written down here so nobody later mistakes it for one.

## Consequences

### ✅ Positive Outcomes

- A non-idempotent migration becomes expressible. That is the whole point, and it is the precondition
  for every schema change a 1.0 deployment will need that is not `CREATE TABLE IF NOT EXISTS`.
- Schema drift is detected instead of masked. An edited or hand-patched migration stops the boot with
  a message naming the version, rather than passing silently.
- A partially-applied migration set is resumable. Today every boot replays everything and every boot
  can fail at the same place with nothing kept.
- The ledger is a readable operator artefact: what ran, when, against which script.

### ⚠️ Trade-offs

- **A refused boot is a hard stop, and some of them will be self-inflicted.** An operator who edits a
  migration to fix a typo now has a broken deployment until they revert it or clear the row. That is
  the intended cost of fail-closed; it is stated here so it is not discovered.
- **Per-migration transactions weaken the all-or-nothing property.** A failure now leaves earlier
  migrations applied. That is the correct trade — the alternative is a set that can never make
  progress — but a half-migrated schema is a state the previous design could not reach.
- **The ledger is one more table the kernel creates without being asked**, in a schema the operator
  owns.
- Checksums make migration files immutable in practice. That is a discipline change for anyone used
  to editing one before a release ships.

### 📋 What is NOT in scope

- **Concurrent boot of multiple nodes.** Two kernels starting against one database can both see an
  empty ledger and both run migration 1. This ADR does not add a lock. It is not a regression — the
  current runner has the same exposure and worse, since it re-runs everything on every boot — and the
  remedy needs the cross-node coordination seam that is already tracked as post-1.0. The
  single-node assumption stays, now written down rather than implied.
- **Repair, baseline and skip commands.** No operator tooling ships here. The remedy for a mismatch is
  to restore the file or delete the row by hand, and that is enough for a single-node 1.0.
- **Out-of-order migrations.** A version lower than one already applied is not specially handled.
- **Rollback / down-migrations.** Not offered, not planned.

## Cross-references

- [docs/subsystems/persistence.md](../subsystems/persistence.md) §"Database Schema Management — Migration Strategy" — the contract this changes.
- ADR-013 — the saga snapshot store whose tables these migrations create.
- The cross-node coordination seam RFC — where the concurrent-boot lock belongs, and why it is not here.

## Engineering Protocol

- `CommunityPersistenceEngineMigrationTest` pins ordering and statement splitting and must stay green:
  this decision changes *when* a script runs, not how it is parsed.
- Apply-once is asserted by a migration that is **not** idempotent — a counter table incremented by
  the script — so a second boot that re-ran it would be visible. A test whose migration is guarded
  by `IF NOT EXISTS` cannot fail and therefore proves nothing.
- Checksum refusal is asserted by mutating a migration between two boots against the same database.
- Per-migration commit is asserted by failing the last migration of a set and confirming the earlier
  ones survived, in the ledger and in the schema.
- Every one of these is mutation-checked against the pre-decision runner: each must fail before it is
  claimed to hold.
