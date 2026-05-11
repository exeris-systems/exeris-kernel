# ADR-022: Persistence SPI Extension — Instant Binders and Readers

| Attribute      | Value                                                                                  |
|:---------------|:---------------------------------------------------------------------------------------|
| **Status**     | **ACCEPTED**                                                                           |
| **Deciders**   | Arkadiusz Przychocki                                                                   |
| **Date**       | 2026-05-11                                                                             |
| **Driven By**  | ADR-013 §5 (saga OCC contract), `docs/subsystems/persistence.md`, ROADMAP v0.8 Sprint 0b |
| **Compliance** | [Strategic Pillar: Distributed Saga Recovery](../whitepaper.md)                        |

## 1) Context and Problem Statement
- `JdbcFlowSnapshotStore` (Community 0.7) ships as a fully-implemented durable saga-state backend but is **unreachable from any wiring path** because `CommunityFlowSubsystem.initialize()` always constructs the in-memory `CommunityFlowSnapshotStore` when `flow.persistenceEnabled=true`, regardless of JDBC availability. Downstream consumers (`exeris-spring-runtime` Phase 4B Step 4) that enable durable saga state get silent in-memory behaviour — parked flows do not survive restart.
- The fix requires `JdbcFlowSnapshotStore` to acquire its connections through the `PersistenceEngine` SPI rather than a raw `javax.sql.DataSource`. Exposing `DataSource` directly would break the swappable-engine contract that keeps Community a black-box implementation of `PersistenceEngine` (Enterprise/future tiers must remain swap-compatible at the SPI boundary).
- Routing snapshot persistence through `PersistenceEngine.openConnection()` → `PersistenceStatement` / `QueryResult` requires Instant binding and reading on the SPI, which the current surface lacks. The store binds `Timestamp.from(Instant)` on `last_update` and `timeout_at` columns and reads them back as `Instant` — the SPI has typed binders for `int / long / short / float / double / boolean / String / UUID / bytes / null` and typed readers for the same set, but no `Instant` or `Timestamp` type.

## 2) Decision Scope
- In scope: additive SPI surface for `Instant` binding (`PersistenceStatement.bindInstant`) and reading (`RowCursor.getInstant`); Community JDBC implementation; semantics for SQL NULL on `Instant` columns; the `Instant.MAX ↔ NULL` convention used by `FlowSnapshot.timeout()`.
- In scope: positional-index reads only — no named-column accessors (e.g. `getInstant(String columnName)`) are added in this ADR. Named-column reads remain deferred (callers map column order at the call site, matching the existing `getInt(int)` / `getLong(int)` pattern).
- Out of scope: timezone semantics beyond `TIMESTAMP WITH TIME ZONE` (Instant is UTC-only); microsecond/nanosecond precision negotiation (driver-dependent, documented per-tier); Enterprise off-heap binary encoding details (Enterprise binding remains out-of-repo).

## 3) Decision — Additive Binders and Readers
- **New SPI method:** `PersistenceStatement bindInstant(int index, Instant value)` — binds a `java.time.Instant`. A `null` value MUST be treated as SQL NULL (same convention as `bindString(null)`, `bindBytes(null)`).
- **New SPI method:** `Instant getInstant(int column)` on `RowCursor` — returns the column value as `Instant`, or `null` for SQL NULL. Unlike primitive getters (which throw `NullPointerException` on NULL to preserve type discipline), this reference-typed getter returns `null` directly; the caller is responsible for mapping NULL to a sentinel value where required (e.g. `Instant.MAX` for "no timeout" per `FlowSnapshot` convention).
- **Backward compatibility:** both methods are added as new abstract members on existing public interfaces. Existing implementations (Community `JdbcPersistenceStatement` / `JdbcRowCursor`) are updated in the same change set. There is no in-repo external implementation of these SPI interfaces, so the addition is safe at TRL-3 pre-1.0; out-of-repo implementations (future Enterprise binding) MUST implement both methods.
- **Naming consistency:** `bindInstant` matches the existing `bindXxx(int, value)` pattern; `getInstant` matches the existing `getXxx(int)` pattern. No abbreviations (`bindTS`, `bindTimestamp`) — `Instant` is the canonical type in the Java time model and matches `java.time` usage across the kernel.

### Rejected alternatives
- **Encode `Instant` as `long` epoch-micros via `bindLong` / `getLong`.** Forces a schema migration on existing `JdbcFlowSnapshotStore` callers (`TIMESTAMP WITH TIME ZONE` → `BIGINT`), breaks human-readable inspection of the saga table, and discards driver-native timestamp semantics (timezone, NULL distinction). Rejected as a load-bearing simplification that costs more than it saves.
- **Encode `Instant` as ISO-8601 `String` via `bindString` / `getString`.** Adds per-row parsing overhead on the read path, loses native timestamp comparison/indexing on the database side, and breaks Postgres-specific timestamp arithmetic (`now() - last_update`). Rejected for the same reason as the `long` form, with the additional cost of allocation pressure.
- **Add named-column reads (`getXxx(String columnName)`) in the same ADR.** Out-of-scope for Sprint 0b — `JdbcFlowSnapshotStore` can refactor to positional indexes from its `SELECT` column order without requiring named-column SPI. Deferring keeps the SPI surface minimal and avoids committing to a column-name resolution model (case sensitivity, schema-qualified names) before there is a second use case to validate the design.
- **Refactor `JdbcFlowSnapshotStore` to keep `javax.sql.DataSource` constructor parameter, fed via a Community-internal `ConnectionFactory` functional interface.** Lower SPI surface change, but keeps the store coupled to raw JDBC API and prevents `PersistenceEngine` from owning request-scoped session reuse, RLS interceptors, or future Enterprise transport. Rejected in favour of routing through the SPI for contract cleanliness.

## 4) Architecture Boundaries (The Wall)
- SPI remains implementation-blind: the new methods reference `java.time.Instant` (JDK standard library) only — no JDBC types (`Timestamp`, `Connection`), no driver-specific types, no Postgres-specific extensions.
- Community owns the JDBC adapter: `JdbcPersistenceStatement.bindInstant` delegates to `setTimestamp(idx + 1, Timestamp.from(instant))`; `JdbcRowCursor.getInstant` delegates to `resultSet.getTimestamp(col + 1)` and converts via `.toInstant()`. SQL NULL is propagated via `setNull(idx + 1, Types.TIMESTAMP_WITH_TIMEZONE)` for binding and `resultSet.wasNull()` check for reading.
- Enterprise binding (out-of-repo) MUST implement both methods. The expected wire encoding is 8-byte big-endian microseconds since 2000-01-01 00:00:00 UTC (Postgres binary `timestamptz`); the off-heap `MemorySegment` write pattern matches existing primitive binders.
- Core remains driver-agnostic: no Core code uses the new methods directly. They exist to support Community subsystem implementations (`JdbcFlowSnapshotStore`, future `JdbcOutboxStore` timestamp columns) and future Enterprise persistence drivers.

## 5) NULL-Handling Contract
- `bindInstant(idx, null)` MUST emit SQL NULL with type code `TIMESTAMP_WITH_TIMEZONE` (Community) or the equivalent typed-NULL encoding (Enterprise). It MUST NOT throw on `null` input.
- `getInstant(col)` MUST return `null` for SQL NULL. It MUST NOT throw `NullPointerException` for NULL values — the reference-type getter is the explicit opt-out from the primitive-getter NPE convention. Callers requiring a non-null mapping MUST use `isNull(col)` before the read or null-check the returned `Instant`.
- The `Instant.MAX ↔ SQL NULL` convention for "no timeout" remains a `FlowSnapshot` / `JdbcFlowSnapshotStore` concern, not a SPI concern. The SPI surface knows only `Instant` and SQL NULL; mapping `Instant.MAX` to NULL during binding and back during reading is the caller's responsibility.

## 6) Performance Contract
- **Community:** `bindInstant` allocates one `java.sql.Timestamp` per call (`Timestamp.from(Instant)`); `getInstant` reads via `ResultSet.getTimestamp` (one heap allocation) and converts via `.toInstant()` (one more allocation). Total: 2 allocations per timestamp column per row on the read path, 1 on the write path. Acceptable for the Community tier per ADR-013 §7 ("Community implementations MAY allocate per-row").
- **Enterprise** (future): zero heap allocation. `bindInstant` writes 8 bytes directly to the off-heap Bind message buffer (`MemorySegment.set(ValueLayout.JAVA_LONG, offset, micros)`); `getInstant` reads 8 bytes from the off-heap result buffer and constructs `Instant` on demand (one allocation per actual call to `getInstant`, but **only** if the caller materializes the value — flyweight cursor passes raw bytes through without allocation).

## 7) Migration and Rollout
- v0.8.0 (Sprint 0b): land additive SPI methods + Community implementation + `JdbcFlowSnapshotStore` refactor to `PersistenceEngine` + bootstrap wiring fix + kernel-level test asserting `FLOW_SNAPSHOT_STORE` binding under all three configurations (in-memory, JDBC, persistence-absent).
- v0.8.0 CHANGELOG: list both new methods under "Added — Persistence SPI extensions"; cross-reference this ADR and ADR-013 §5 (OCC contract preserved).
- No deprecation cycle required — the SPI surface is additive only. Existing call sites in `JdbcFlowSnapshotStore` migrate to the new methods in the same PR; no other in-repo callers exist.

## 8) Consequences
- **Enables:** Pure-D resolution of the `JdbcFlowSnapshotStore` wiring gap (Sprint 0b primary goal). Downstream `exeris-spring-runtime` can flip `persistenceEnabled=true` default in Phase 4B Step 4 closure.
- **Locks in:** `Instant` (not `Timestamp`, not `OffsetDateTime`, not `long`) as the canonical SPI time type. Future timestamp-heavy subsystems (outbox last-attempt-at, audit log entries, event tombstones) inherit the same surface.
- **Defers:** named-column reads (`getXxx(String)`), `OffsetDateTime` / `LocalDateTime` binders (timezone-aware variants), and microsecond/nanosecond precision negotiation. These remain available for future ADRs if a use case emerges.
- **Cost:** two new abstract members on two public SPI interfaces. Out-of-repo implementations (future Enterprise) MUST add corresponding methods or fail to compile; the additive nature means existing Community impls only need a 4-line addition each.
