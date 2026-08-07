# RFC-2026-08-07: What substrate can a cross-node coordination seam actually stand on?

| Field             | Value                                                                    |
|:------------------|:-------------------------------------------------------------------------|
| **Status**        | **ACCEPTED**                                                             |
| **Author(s)**     | Arkadiusz Przychocki                                                     |
| **Date Opened**   | 2026-08-07                                                               |
| **Date Closed**   | 2026-08-07                                                               |
| **Target ADR(s)** | none at acceptance — the number is reserved when the implementing change reaches its build gate |
| **Affected Repos**| `exeris-kernel`                                                          |
| **Reviewers**     | —                                                                        |

## Question

The kernel has no first-class seam for cross-node coordination — distributed lock, leader election,
singleton execution ("only one instance runs this cron / drain / migration"). The ROADMAP proposes one,
under a load-bearing design constraint: **a thin seam over substrate the kernel already owns** (Postgres
advisory locks, Kafka consumer groups), explicitly not a new cluster subsystem. So: **is that substrate
actually owned and usable, and if it is not, what is the smallest honest thing to build instead?**

## Context

"Five instances = the same app" is ordinary scale-out, and most of what it needs already exists:
stateless per-request handling through `ScopedValue`, shared durable state in Postgres (ADR-013 /
ADR-022), cross-node fan-out through the Kafka Events driver. Coordination is the hole. Without a seam,
every application hand-rolls it and inherits the whole correctness burden — fencing, lease expiry,
split brain — which is exactly the class of problem a kernel exists to absorb once.

The ROADMAP frames this as cheap: the substrate is owned, so the seam is thin. That framing is what
determines whether this is a v0.11-shaped decision or a much larger one, and it is what this RFC
checks first. The check did not go the way the entry assumed, in both directions — the substrate is
weaker than claimed, and the set of things already broken on two nodes is larger.

Cross-node coordination is **explicitly post-1.0** (ROADMAP §"1.0 = narrow, deep, defensible core"),
so nothing here proposes shipping in this milestone. What is at stake is which shape the eventual
implementation takes, and whether the ROADMAP's stated one survives contact with the code.

## Investigation

### Prior art

**Fencing tokens.** The canonical published argument is Martin Kleppmann's *"How to do distributed
locking"* (2016): a lease alone is not sufficient, because a holder that pauses — GC, a stalled
syscall, a descheduled VM — can wake after expiry and act while a new holder is already acting. A lock
service cannot prevent this; only the *resource* can, by rejecting writes carrying a stale monotonically
increasing token. Google's **Chubby** paper (Burrows, OSDI'06) reaches the same conclusion via sequencers,
and **ZooKeeper** exposes the equivalent through `zxid` / the monotonic czxid of an ephemeral node.

The load-bearing consequence for this RFC: **a fencing token is only worth having if the resource being
protected checks it.** A token handed to a caller who then writes to Postgres, S3 or a third-party API
without passing it through fences nothing. Any contract here must say where the check happens, or it is
selling the appearance of safety.

### Constraints

- **The Wall, executably.** `ExerisArchitectureTest` already bans `org.apache.kafka..`, `java.sql..` and
  `com.zaxxer.hikari..` from the SPI. So `tryLock` / `renew` / `release` / `leadership` may name no
  connection, no consumer, no partition, no advisory-lock vocabulary. That guard runs in
  `exeris-kernel-tck`, whose only production dependency is the SPI — a Community coordination driver
  would need its own module-local guard, as `CommunitySchedulingArchitectureTest` does for scheduling.
- **A lock handle in a `ScopedValue` is not thread-confined.** `KernelProviders` slots are inherited by
  every virtual thread spawned inside the binding scope. This is not theoretical: the same assumption
  was wrong in `DrainCoordinator` and cost a correctness fix — a plain `boolean` guarded by a
  confinement claim that `ScopedValue` structurally voids. **`release` and `renew` must therefore be
  idempotent and CAS-based**, never "the owning thread closes it".
- **Concurrency shape is constrained.** `ExecutorService` / `Executors` / `CompletableFuture` are banned
  and ArchUnit-enforced, so a lease-renewal timer cannot be a `ScheduledExecutorService`. The compliant
  in-repo precedent is `CommunitySystemSchedulerClock`: `ReentrantLock` + `Condition.awaitNanos` on a
  virtual thread, behind a Community-only interface. Blocking is fine — `BaseRepository` states the
  Loom-first contract ("All methods are blocking… no reactive wrapper, no `Mono<T>`, no
  `CompletableFuture<T>`") — but a JFR event must not straddle a park (single-phase commit).
- **Fail-closed is doctrine, and it settles one of the ROADMAP's open questions.** `security.md` denies
  outright when context cannot be established, and treats a `null` route policy as a defect rather than
  as "unmatched". So "backend unreachable → degraded single-node", read as *grant the lock anyway*,
  contradicts the doctrine. The compatible reading is the one the kernel already implements for
  dependency loss: deny **and** drain — `CommunityDegradedModeIntegrationTest` shows Postgres loss
  producing readiness `DEGRADED` with liveness `UP`, so the load balancer removes the instance.
- **Stability discipline from day one.** ADR-065's japicmp gate fails the build on an unclassified SPI
  class, so a `spi.coordination` package must land with its `docs/stability-matrix.md` row and its
  `stability-surfaces.conf` entry in the *same* commit. It enters at `preview`, like `spi.scheduling`
  and `spi.storage.blob`. Its **error codes enter harder**: `spi.exceptions` is stable in full,
  including subtrees whose domains are themselves preview.
- **A lease is a time contract, and the kernel has no clock seam.** Time is read ad hoc, and the
  "unified injectable Clock seam" is 1.0-RECOMMENDED and unstarted. Scheduling already added a
  subsystem-local clock rather than wait. A coordination seam either adds a fourth one or declares the
  dependency; it cannot pretend the question is absent.

### Data gathered

**The ROADMAP's central premise — "substrate the kernel already owns" — does not survive. Both halves.**

*Postgres advisory locks are not owned; they are not used at all, and the SPI structurally cannot hold
one.* There is no `pg_advisory_lock` anywhere in tracked source or migrations — every case-insensitive
"advisory" hit in Java is unrelated (advisory JFR labels, `@Tag("advisory")` tests, an advisory TCP
error code). More decisive than absence is impossibility: a session-scoped advisory lock needs a pinned
physical connection, and `PersistenceEngine` exposes only `openConnection()` / `openConnection(StorageContext)`.
`openPhysical()` lives on the Community-internal `PhysicalConnectionSource`, not on the SPI. Outside an
HTTP request scope — i.e. exactly where cron, drain and migration run — every `openConnection()` is a
fresh pool checkout, so `tryLock` and `release` would land on *different Postgres sessions*: the lock
leaks, the release is a no-op.

Two further mechanisms make it worse rather than merely awkward. `JdbcPersistenceConnection.close()`
rolls back and returns the connection to Hikari with no `DISCARD ALL` and no `pg_advisory_unlock_all`,
so a leaked lock rides the recycled connection into the next checkout — the codebase already documents
this exact pooled-session-state hazard for RLS, where `RlsConnectionInterceptor` calls it out as "a
fail-open bug". And Hikari's `maxLifetime` retires connections on a timer, silently dropping any
session-scoped lock with no error path to its owner.

The transaction-scoped variant (`pg_advisory_xact_lock`) survives the connection model, but by
construction cannot outlive a transaction — it can express a critical section, never
`tryLock(key, lease)` + `renew`.

*Kafka consumer groups are half-owned.* A group genuinely exists — `group.id` from a required
`events.kafka.group-id`. What does not exist is any observation surface: zero `ConsumerRebalanceListener`,
zero `consumer.assignment()`, zero `ConsumerGroupMetadata.generationId()` in the entire repo — Kafka's own
free monotonic fencing token, unused. The consumer is a private static class inside `KafkaEventEngine`
with no seam. And the group id is taken raw with no per-node suffix and no separate coordination group,
so a driver reusing it would entangle leadership with event delivery.

There is a sharper observation buried here: because all instances share one group and `subscribe()`,
Kafka's competing-consumer semantics mean a record reaches **one** instance, not five. That is
simultaneously a caveat on the ROADMAP's "cross-node fan-out" phrasing and the strongest exclusivity
primitive the repo currently has, arrived at by accident.

**The JobScheduler paragraph is not just wrong, it is inverted.** The ROADMAP says the scheduler
"already anticipates a subset — its durable backend carries leader-election semantics", and the merge
gate asks for "`JobScheduler` singleton dispatch refactored to consume it". There is no durable backend
and no singleton dispatch. `scheduling.md` lists "Durable job stores, leader election, distributed
coordination" under what is **excluded by contract, not merely unimplemented**, and `JobSchedulerProvider`
says a distributed binding "would select a leader through the coordination seam rather than inventing a
parallel mechanism". The dependency runs the other way, and the gate clause is vacuous: there is nothing
to refactor.

**There is no node identity to be a lock owner.** The only runtime read is
`System.getProperty("exeris.node.id", "local")`, feeding a JFR field. The default is the literal string
`local` — identical across all five instances. Nothing generates a per-process identifier. A lock owner
and a fencing subject would both have to be invented.

**The consumers actually waiting are not the ones the entry names.** Two kernel-internal ones are broken
on two nodes today:

- **The migration runner.** No schema-history table and no lock: it re-executes *every* migration on
  *every* boot inside one transaction, relying on `IF NOT EXISTS`. On a rolling deploy that is N nodes
  running DDL concurrently against one database, and the idempotency that saves it is per-statement
  convention, not a guarantee.
- **`IdempotencyGuard`.** The SPI already has the `tryClaim` shape, and its only main-source
  implementation is a heap `ConcurrentHashMap`. Its TCK asserts "under 16 concurrent virtual threads,
  exactly one wins" — true within a JVM, false across five. Every node grants the same step claim.

### Spike outcomes

None, and deliberately: prototyping a lock over substrate that turned out not to exist would have
measured the wrong thing. What replaced a spike is the connection-model audit above, which is what
actually falsified the premise.

## Options Considered

### Option A: A Postgres lease table with a fencing epoch — new, owned substrate

`exeris_coordination_lease(key, owner, epoch, expires_at)`. Acquisition is a conditional `UPDATE …
WHERE key = ? AND (owner = ? OR expires_at < now())` that bumps `epoch`; the bumped epoch is the fencing
token. Renewal extends `expires_at` for the same `(key, owner, epoch)`. This is the CAS idiom the repo
already runs in `JdbcFlowSnapshotStore` (`schema_version = schema_version + 1 … AND schema_version = ?`),
one table over.

**Pros:**
- Works through the pooled connection model as it exists — every operation is one self-contained
  statement, so nothing depends on session affinity, connection pinning, or pool-reset behaviour.
- The fencing token is genuinely monotonic and survives rollback, and the repo has two working
  precedents for the token-checked-at-the-resource pattern (snapshot OCC, flow lifecycle generation).
- Deployable wherever the kernel already requires Postgres — no new operational dependency.

**Cons:**
- It is **new substrate**, not owned substrate. Honest naming matters: this is a table, a migration, a
  renewal loop and a clock dependency, not a thin wrapper.
- Lease expiry is wall-clock, so it inherits the unresolved clock-seam question.
- Polling-based leadership observation; no push notification of loss.

**Cost:** SPI + one Community driver + migration + TCK, and the clock decision it forces.

### Option B: Postgres advisory locks — the ROADMAP's proposal

**Rejected on the evidence above.** Session-scoped locks cannot be held through a pooled
`PersistenceConnection`, would leak into recycled connections, and would be silently dropped by
`maxLifetime`. Transaction-scoped locks cannot express a lease or a renewal. Neither variant offers a
fencing token. Listed because it was the stated design, and the record should say why it is not.

**Cost:** would require exposing connection pinning on the SPI and a pool-reset contract — a change to
the persistence boundary far larger than the coordination seam itself.

### Option C: Kafka consumer group as the leadership primitive

Derive `leadership(group)` from partition assignment; use the group generation as the fencing token.

**Pros:** the mechanism is real and battle-tested, and the token is free.

**Cons:** it fences **Kafka-resident state only** — the broker validates the generation on transactional
offset commit, but a side effect on Postgres, a filesystem, or a third-party API is entirely outside
that fence. The motivating use cases (cron, drain, migration) all live outside it. It also makes
coordination unavailable to the no-Kafka deployment, which is the default one, and reusing the events
group would break event delivery.

**Cost:** moderate, and it buys leadership for the deployments least likely to need a kernel seam.

### Option D (do nothing): no seam; applications keep hand-rolling

**Pros:** zero kernel surface; the seam is post-1.0 anyway.

**Cons:** leaves two kernel-internal defects unowned — the unlocked migration runner and the node-local
`IdempotencyGuard` whose TCK promises an exclusivity it does not have across nodes. Those are not
application problems and no application-side lock fixes them.

**Cost:** zero now; the two internal defects stay.

## Recommendation

**Select Option A — a Postgres lease table with a fencing epoch — as the seam's shape, and state
plainly that it is new substrate rather than a thin wrapper over owned substrate. Implementation stays
post-1.0.**

The ROADMAP's premise was the whole reason this looked small, and it does not hold: the kernel owns
neither substrate in usable form. That does not make a seam wrong; it makes the *cost estimate* wrong,
and correcting the estimate is the most useful thing this RFC does. Option A is the smallest shape that
survives the connection model the kernel actually has, and it reaches that by reusing an idiom already
running in production paths rather than inventing one.

The recommendation deliberately does **not** follow the ROADMAP's dependency story. The scheduler is not
waiting on this seam to refactor something — it has excluded leader election by contract and named the
seam as a future dependency. The consumers that would benefit on day one are internal: the migration
runner, which today lets N nodes run DDL concurrently, and `IdempotencyGuard`, whose contract promises
cross-node exclusivity that its only implementation cannot deliver. An implementation gate should name
those, because they are checkable.

Residual uncertainty, stated. The out-of-repo enterprise distribution may already ship a durable
scheduler backend with leader election; the open-core evidence describes it only hypothetically, so the
refutation of the ROADMAP's JobScheduler paragraph is scoped to what is readable here. And the split-brain
probe the gate asks for cannot be built with today's test infrastructure — see §Open questions.

### Why not the alternatives?

- **Option B** — the substrate cannot express the contract: no lease, no renewal, no token, and a
  session-scoped lock cannot survive the pool.
- **Option C** — its fence covers Kafka-resident state only, which excludes every use case the entry
  motivates, and it is unavailable in the default no-Kafka deployment.
- **Option D** — leaves two *kernel-internal* correctness gaps unowned, one of which is a TCK promising
  an exclusivity guarantee that does not hold across nodes.

### Risks of the recommendation

- **"Thin seam" was the reason this was scoped small.** Correcting the premise should re-open the
  scheduling question, not be absorbed silently. If the honest cost is unattractive, Option D is a
  legitimate answer and this RFC would rather be superseded than quietly under-delivered.
- **The clock dependency is real and unresolved.** A lease built on a fourth subsystem-local clock adds
  to the debt the unified-seam item exists to pay down.
- **A fencing token nobody checks is worse than none**, because it looks like safety. The implementation
  gate must include at least one resource that actually validates the token, or the contract is
  decorative.
- **Wall-clock leases assume bounded clock skew** across nodes. That assumption is not currently stated
  or verified anywhere in the kernel.

## Decision Record

| Field                | Value |
|:---------------------|:------|
| **Outcome**          | **ACCEPTED** — Option A selected as the seam's shape (Postgres lease table with a fencing epoch), explicitly labelled new substrate; implementation post-1.0. |
| **Date**             | 2026-08-07 |
| **Resulting ADR(s)** | **none at acceptance.** Accepting this RFC commits no kernel surface, matching the merge gate's "no kernel SPI commits in this gate (decision-only track)". The ADR number is reserved when the implementing change reaches its build gate. |
| **Notes**            | Dissent recorded: Option D (no seam) is a defensible answer, and becomes the *right* one if the corrected cost estimate makes the seam unattractive — the premise that made this look cheap was false, and a decision taken on a false premise deserves to be re-taken rather than inherited. Three ROADMAP claims were refuted in the course of accepting this: advisory locks are neither used nor expressible through the pooled persistence SPI, the JobScheduler dependency runs the opposite way to the entry's description (making one merge-gate clause vacuous), and the substrate is not "already owned" in either half. All three corrections land with this RFC. |

## Open questions / follow-ups

- **The split-brain probe the merge gate asks for cannot be built today.** There is no partition tooling
  — no toxiproxy, no container pause, nothing that expresses "node A loses Postgres while node B keeps
  it", and nothing that simulates a holder paused past lease expiry. The closest precedent is
  `AbstractDistributedFlowSnapshotStoreTck`, whose `createStore()` / `reopenStore()` hooks already model
  two instances over one backend and assert "exactly one writer wins the CAS race" — structurally the
  right probe with `save` swapped for `tryLock`. The implementation gate should say which half is
  contract-level (TCK, controllable clock and backend) and which is container-level, rather than asking
  for one test that cannot exist. Owner: whoever opens the implementation gate.
- **Node identity has to be invented.** `exeris.node.id` defaults to the literal `local` on every
  instance. A lock owner needs a per-process identifier that survives no restart. Decide whether it is
  config-supplied, generated per boot, or both. Owner: bootstrap subsystem.
- **The clock seam.** A lease cannot be specified without it. Either the coordination driver takes an
  injectable time source from day one — as the scheduler driver did — or the unified seam lands first.
  Owner: 1.0-RECOMMENDED clock item.
- **The migration runner is a live single-node assumption regardless of this seam.** It has no history
  table and no lock, and re-runs every script on every boot. Worth fixing independently; it should not
  wait for a post-1.0 seam. Owner: persistence subsystem.
- **`IdempotencyGuard`'s contract overstates what its implementation delivers.** The TCK's exclusivity
  assertion holds within a JVM; the SPI does not say so. Either the Javadoc scopes the guarantee to a
  single node, or the seam becomes its cross-node implementation. Owner: flow subsystem.
- **Whether the enterprise distribution already has a durable scheduler backend with leader election.**
  If it does, the ROADMAP's JobScheduler premise is right about that tier and wrong only about
  open-core, and this RFC's refutation should be narrowed accordingly. Owner: enterprise tier.
