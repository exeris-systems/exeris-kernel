# RFC-2026-09-03: Should `EX-CFG-1004` report a state or count attempts?

|                    |                                                                          |
|:-------------------|:-------------------------------------------------------------------------|
| **Status**         | **OPEN** — options laid out and measured, decision not taken             |
| **Author(s)**      | Arkadiusz Przychocki                                                     |
| **Date Opened**    | 2026-09-03                                                               |
| **Date Closed**    | —                                                                        |
| **Target ADR(s)**  | None reserved. Only Option C changes observable behaviour and would need one; reserving a number before that choice is made would put an unused number in a global namespace |
| **Affected Repos** | `exeris-kernel` (authoritative and sole — the event is emitted in Core and consumed by nothing outside this repo today) |
| **Reviewers**      | —                                                                        |

## Question

`ImmutableReloadRefused` (`EX-CFG-1004`) is emitted every time the config watcher detects that a
sealed `@Immutable` key differs on disk from its boot value. One logical edit can produce more than
one event, and a *later, unrelated* edit to the same file produces another. Should the kernel
coalesce these, and if so on what key — or is the current level-triggered behaviour the right signal
and only its documentation was missing?

No decision is taken here. This exists because a test asserted a count, the count is not a contract,
and correcting the test made it obvious that nothing had ever decided what the count means.

## Context

`@Immutable` seals a trust anchor — `security.jwks.uri` is the motivating case — against hot reload.
When `DynamicConfigFileWatcher` sees a change to a file carrying a sealed key, it compares the
on-disk value against the baseline captured at seal time. On a difference it keeps the sealed value,
logs, and emits `EX-CFG-1004` with file and key only (never the value). **The baseline is
deliberately never updated**, which is what makes the key stay sealed at its boot value, and also
what makes the event repeat.

Two properties of the telemetry contract frame the question. `docs/subsystems/telemetry.md` states
granularity for every event it documents — "one event per refusal", "one event per reclaimed
connection" — and `EX-CFG-1004` had no such statement until now. And the event carries no value, so
a consumer cannot tell two different rejected values apart from the event alone.

## Investigation

### Data gathered

**Duplicate delivery for one logical edit — a race, and a rare one.** `Files.writeString` with
`TRUNCATE_EXISTING` is two filesystem modifications, truncate then write. `WatchService` usually
merges them into a single `ENTRY_MODIFY` carrying `count() == 2`; the watch loop dispatches per
event, not per count, so a merged pair produces one refusal.

| over 20 runs | outcome |
|---|---|
| 19 | one `ENTRY_MODIFY`, `count() == 2` → **one** refusal |
| 1 | two distinct `ENTRY_MODIFY` → **two** refusals |

That ~5% is the rate at which `mutatingSealedKeyEmitsRefusalEvent` was observed to fail on a
`hasSize(1)` assertion.

> An earlier reading of this measurement summed `WatchEvent.count()` and concluded "two events,
> deterministic". `count()` is the repeat count of a *merged* event, not a number of deliveries, and
> the conclusion drawn from it was wrong in both directions — it is neither two deliveries nor
> deterministic.

**Repeat on an unrelated edit — not a race, always.** Measured directly: seal the key, mutate it
(1 refusal), then edit only a *different* key in the same file while leaving the sealed key wrong on
disk → **2 refusals**. Nothing about this is timing-dependent. As long as the file holds the rejected
value, every subsequent change to that file re-audits the sealed key.

### Constraints

- The event carries `file` and `key` only. **The value is deliberately absent** (secret-safety,
  `config.md`), so any dedupe key computed inside the emitter can use the value, but a *consumer*
  cannot dedupe on it.
- The baseline must not be updated on refusal — that is the seal.
- No consumer exists outside the kernel's own test. Blast radius of a change is small today and
  grows the moment an operator builds an alert on it.

## Options Considered

### Option A: leave it level-triggered, and say so

The event means *the sealed key is currently wrong on disk*. Repetition is honest under that
reading, and the fix was never a code change — it was the missing granularity statement, now
written in `config.md` and the method's javadoc.

- **For:** no code change, no window in which a real attempt is invisible; matches what the emitter
  already does; the repetition is arguably useful — it keeps saying the file is still wrong.
- **Against:** an operator alerting on "an `EX-CFG-1004` fired" gets a burst proportional to how
  often that config file is edited for unrelated reasons, which is noise correlated with nothing.
  The signal is loudest in the deployments that edit config most, not the ones under attack.

### Option B: coalesce a detection burst

Suppress repeats of the same `(file, key)` within a short window, or within one `WatchKey` batch.

- **For:** removes exactly the duplicate this RFC started from.
- **Against:** the window is arbitrary and buys little — it addresses the **1-in-20 race** and does
  nothing about the deterministic repeat on unrelated edits, which is the larger source. Worse, any
  window is a period in which a genuine second attempt is silent, and this is a security audit
  event.

### Option C: edge-triggered on `(file, key, on-disk value)`

Emit when the on-disk value *becomes* different from the baseline, and not again while it stays at
that same rejected value. Clear the suppression when the value returns to the baseline, or changes
to a *different* rejected value.

- **For:** one event per distinct wrong state, which is what an operator would read as "an attempt".
  Unrelated edits stop re-auditing. A second, different rejected value still emits. A repeat of the
  same value after a correction still emits, because the correction clears the state.
- **Against:** new state to hold per sealed key, and a semantic that has to be documented as
  carefully as the current one or it becomes a different kind of surprise. It also *does* hide one
  case: the same rejected value written twice with no correction between — indistinguishable from
  one write, by construction.

### Option D (do nothing): leave it undocumented

Rejected already. The state that produced the flake was not the behaviour but the absence of any
statement about it; that half is fixed regardless of which option wins.

## Recommendation

**Option C, weakly held, and explicitly not urgent.** It is the only option that addresses the
deterministic half — the repeat on unrelated edits — and it produces a signal an operator can act on
without a rule about which config files are noisy. Option B is the tempting one and the wrong one:
it fixes the rare race and leaves the common repeat.

The reason it is weakly held is that Option A is defensible on its own terms and costs nothing. If
this event never grows a consumer, A is correct by default.

### Risks of the recommendation

The obvious dedupe key — `(file, key)` alone — would swallow a genuinely repeated attempt with a
*different* value, which is the case the audit exists for. The value must be part of the key, and
it must be held only in memory and never emitted. Getting that wrong turns a security signal into a
security gap, which is why this is an RFC and not a patch.

## Decision Record

**Undecided.** Raised 2026-09-03 out of the `mutatingSealedKeyEmitsRefusalEvent` flake
(PR #437). No implementation is scheduled; the documentation half has landed independently.

## Open questions / follow-ups

- Does any deployment alert on `EX-CFG-1004` today? If not, this can stay open indefinitely at no
  cost; if it does, the noise argument for Option C gets sharper and measurable.
- If Option C is taken, does the same reasoning apply to any other level-triggered audit event in
  the kernel? No survey has been done.
