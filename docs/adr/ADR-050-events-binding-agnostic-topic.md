# ADR-050: Events Binding-Agnostic `topic` — the SDK `@DomainEvent.topic` gets a kernel sink on `EventTypeSpec`

| Attribute       | Value                                                                                                                                                                                       |
|:----------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **ADR #**       | **050** (reserved 2026-07-01 in `exeris-docs/adr-index.md`).                                                                                                                              |
| **Status**      | **Accepted** — kernel slice on the v0.10 train. This slice ships the SPI seam (`EventTypeSpec.topic`), the Kafka binding honouring it on publish + subscribe, the in-memory advisory semantics, the TCK, this ADR, and the `events.md` record. The `exeris-tooling` generator populate + e2e and the `exeris-sdk` annotation Javadoc stance are the **lockstep follow-up** (separate repos). Reaches `main` with the 0.10 release. |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                                                                       |
| **Date**        | 2026-07-01                                                                                                                                                                                 |
| **Scope**       | kernel/events                                                                                                                                                                             |
| **Owning Repo** | `exeris-kernel`                                                                                                                                                                            |
| **Driven By**   | v0.10 ROADMAP §"Events: Binding-Agnostic `topic` Concept on the Event Descriptor SPI"; downstream dogfooding (Stellar-Tactics multi-service build, 2026-06 — finding K1 + the `@DomainEvent.topic` twin); sequenced with the Event-Payload Codec (ADR-046). |
| **Compliance**  | The Wall (ADR-006); No Waste Compute; Valhalla-ready carriers (JEP 401); consistent with the transient-bus no-ordering stance (ADR-049 H2).                                                |

## Context and Problem Statement

The SDK `@DomainEvent.topic` attribute captures an author's routing intent at design time and is carried through the toolchain, but **it had nowhere to land on the kernel side**, so the routing target was neither portable across `EventEngine` bindings nor consumable by generated code.

Verified against the current source trees (kernel + sdk + tooling, all local):

- **The SDK captures `topic` and the generator is ready.** `@DomainEvent.topic` (`exeris-sdk-annotations/.../DomainEvent.java`) is a **required** `String`; it is carried into `DomainEventMetadata.topic` (`exeris-sdk-source-model/.../DomainEventMetadata.java`). The `exeris-tooling` `KernelEventGenerator` already reads `event.topic()` — but because the kernel offered no sink, it **dropped the value to a Javadoc-only reference** ("topic routing is not part of the Open-Core SPI event descriptor and is preserved here only for reference"). The kernel was the blocker, not the tooling.
- **The Kafka binding invents its own topic mapping.** `KafkaEventEngine` derives the topic purely from the **event-type name** — `KafkaEventConfig.topicFor(name) = topicPrefix + name` — on both the publish path (`buildRecord`) and the subscribe path (`refreshSubscriptions`). So a type `OrderCreatedEvent` publishes to `prefix+"OrderCreated"`, silently ignoring an author's intended `"orders.created"`.
- **The in-memory bus ignores any topic notion entirely.** `InMemoryEventBus` routes by `eventTypeOrdinal` only (ADR-049 H2 — the transient bus is deliberately unordered and, by the same token, topic-blind).
- **There was no shared SPI seam both bindings honour**, so the routing target was not swappable between the in-memory and Kafka bindings, and the deliberate tiering (why the open-core surface omitted `topic`) was undocumented.

The v0.10 ROADMAP framed the resolution as either (a) document the tiering or (b) promote `topic` to a binding-agnostic field. Because the SDK already produces the value and the generator already tries to consume it, (a) would perpetuate "kernel blocks tooling" into a later milestone. This ADR takes **(b)** — but corrects *where* the field lives.

**This ADR answers: where does the binding-agnostic `topic` live, and how do bindings consume it without breaking The Wall or the Valhalla-ready descriptor?**

## 🏁 The Decision

**`topic` is an optional, binding-agnostic `String` carried on `EventTypeSpec` — the per-event-*type* registration record — NOT a field on the per-instance `EventDescriptor`. Bindings resolve it through the `EventRegistry`; the Kafka binding maps it to the concrete broker topic on both publish and subscribe (an override of the default type-name topic); the in-memory `EventBus` treats it as advisory (topic-blind by design).**

A topic is a **static, per-type** attribute (the `@DomainEvent.topic` string has no placeholders — the per-instance routing knob is the separate `routingKey`). It therefore belongs on the type registration, not duplicated into every event instance. `EventTypeSpec` already carries a `String name` that is explicitly "registration/lookup only, never the hot dispatch path"; `topic` rides alongside it with the same off-hot-path property. This leaves `EventDescriptor` — the primitive-only, Valhalla-ready, wire-encoded carrier — **byte-for-byte unchanged**.

**Concrete obligations:**

1. **`EventTypeSpec` gains `String topic` (SPI, additive).** New shape `EventTypeSpec(name, ordinal, persistent, ordered, topic)`; the existing two-arg `of` / `ofPersistent` factories delegate with `topic = null`; new three-arg `of(name, ordinal, topic)` / `ofPersistent(name, ordinal, topic)` overloads carry it; `hasTopic()` reports a non-blank override. All existing call sites go through the factories, so none break. A reviewer can assert: `new EventTypeSpec(` appears only inside `EventTypeSpec`'s own factories.
2. **`null` / blank means "no override".** A binding with no topic value falls back to its default routing (the Kafka binding to the type name). Absent/empty semantics are explicit, not a magic sentinel.
3. **The Kafka binding honours the override on BOTH sides.** A single resolution — `effectiveTopic(spec) = topicFor(spec.hasTopic() ? spec.topic() : spec.name())` — feeds `buildRecord` (publish) and `refreshSubscriptions` (subscribe). Splitting them would strand a topic-overridden type (produced on the override, consumed on the name-derived default). The config `topicPrefix` still applies to the override.
4. **The in-memory `EventBus` stays topic-blind.** Routing remains by `eventTypeOrdinal`; the bus does not consult `topic`. `topic` is advisory there — the value round-trips through the registry but does not affect delivery. (Consistent with ADR-049 H2.)
5. **The Wall holds.** `topic` is a plain SPI `String`; only broker bindings assign it broker meaning (`org.apache.kafka.*` stays inside the Kafka module). Core and the in-memory path stay topic-agnostic.
6. **`EventDescriptor` is untouched.** No 8th field, no `KafkaEventCodec` / `KafkaEventLogCodec` / `EventDescriptorCodec` wire change, no JDBC column, no Valhalla-layout regression.
7. **Verification.** `AbstractEventRegistryTck` (extended by `CommunityEventRegistryTckTest`) asserts, on the Community registry, that a spec's `topic` round-trips unchanged, that no-topic reports `hasTopic() == false`, and the ADR-050 identity cases (same name+ordinal with a *different* topic → `EX-EVENT-6003`; the identical topic-carrying spec → idempotent); `KafkaEventRegistryTest` pins the same round-trip + identity on the Kafka registry directly (broker-free — the registry is a plain heap map); `KafkaTopicResolutionTest` pins `KafkaEventEngine.effectiveTopic`'s override/fallback/prefix decision; `AbstractKafkaEventEngineTck` (Testcontainers) asserts a topic-overridden type still round-trips end-to-end (publish and subscribe agree on the override topic); `AbstractEventBusTck` documents the in-memory topic-blind stance.

## Why `EventTypeSpec`, not `EventDescriptor` (deviation from the ROADMAP's phrasing)

The ROADMAP said both "promote `topic` to a **field on the event descriptor** SPI" and "carried as an ordinal `int` **registered in `EventRegistry`** (same pattern as event-type ordinals)". These pull in different directions; the second is the correct instinct, and this ADR follows it to its conclusion:

- **Per-type, not per-instance.** `topic` is invariant for a given `eventTypeOrdinal`. On the descriptor it would be a type constant duplicated into every instance.
- **The ordinal-interning requirement was contingent on the descriptor placement.** It existed only because `EventDescriptor` is primitive-only (Valhalla / C2 scalarization / JEP 401). Once `topic` lives on `EventTypeSpec` — which already carries a `String name` off the hot path — it can be a plain `String`; no separate topic-ordinal namespace, no interning machinery, no descriptor widening.
- **Lower blast radius, forward-reversible.** Zero wire-format change across two Kafka codecs, the core descriptor codec, and the JDBC event-log. If a per-instance topic override is ever genuinely needed (e.g. dynamic `routingKey`-style routing), adding it later is a separate additive step — this decision does not foreclose it.

## Consequences

### ✅ Positive Outcomes

- **[+] Unblocks the tooling.** The `KernelEventGenerator` can stop dropping `@DomainEvent.topic` to a Javadoc comment and populate `EventTypeSpec.ofPersistent(name, ordinal, topic)` — closing the "kernel blocks tooling" gap.
- **[+] One swappable, binding-agnostic routing target.** In-memory (advisory) and Kafka (broker topic) honour the same seam; the value round-trips through each registry (Community via the shared `AbstractEventRegistryTck`, Kafka via `KafkaEventRegistryTest`).
- **[+] The Valhalla-ready descriptor and both wire codecs are untouched** — No Waste Compute; no per-instance duplication of a per-type constant.
- **[+] The Wall is preserved.** `topic` is an SPI `String`; broker meaning stays binding-private.
- **[+] Additive, pre-1.0.** No external SPI consumers; existing factories keep every call site compiling (TRL-3 — no "breaking change" framing).

### ⚠️ Trade-offs

- **[-] Bindings must resolve `topic` from the registry** (an ordinal → spec lookup on the publish path). It is O(1) and off the in-memory hot path; the Kafka path already did an ordinal → name lookup, now a spec lookup.
- **[-] `EventTypeSpec` equality now includes `topic`.** Re-registering the same `(name, ordinal)` with a *different* topic is a conflict — correct (a type's topic changing under a fixed name/ordinal is a misconfiguration), and consistent with how `persistent` / `ordered` already participate in equality.
- **[-] Full value only after the lockstep lands.** Until the `exeris-tooling` generator populates `topic`, the kernel seam ships correct but unpopulated by codegen (mirrors ADR-046's generator-rewrite lockstep). Hand-written registrations can use it immediately.

### 📋 What is NOT in scope

- **The `exeris-tooling` `KernelEventGenerator` populate + e2e** and the **`exeris-sdk` `@DomainEvent.topic` Javadoc stance update** ("Open-Core does not route on topic" → "carried on `EventTypeSpec`; broker bindings honour it") — lockstep follow-ups in their own repos.
- **A per-instance topic / `routingKey`** — deferred; this decision does not foreclose a later additive step.
- **Any `EventDescriptor` wire-format change** — explicitly avoided.
- **RabbitMQ `exchange` / `routingKey` and the other `@DomainEvent` messaging attributes** — out of scope; only `topic` gets a kernel sink here.
- **The Kafka outbox delivery path (`KafkaEventBrokerPort`)** has no production wiring yet; its `ordinalToTopic` Javadoc now points at the override-aware resolution for whoever wires it.

## Cross-references

- ADR-049 (Events Log-Ordering & OCC Boundary) — H2 (transient bus unordered / topic-blind by design), the sibling this builds beside.
- ADR-046 (Event-Payload Codec SPI) — sibling additive events-SPI seam sequenced with this item; the same generator-lockstep pattern.
- ADR-006 (Spring-Free Kernel Boundary / The Wall) — the boundary broker-private topic realization respects.
- `exeris-kernel/docs/subsystems/events.md` — the boundary record updated by this slice.
- `exeris-kernel/docs/ROADMAP.md` §"Events: Binding-Agnostic `topic` Concept on the Event Descriptor SPI".
- `exeris-sdk/.../DomainEvent.java`, `exeris-sdk/.../DomainEventMetadata.java`, `exeris-tooling/.../KernelEventGenerator.java` — the design-time capture + codegen the kernel sink unblocks.

## Engineering Protocol

1. **This slice (kernel):** `EventTypeSpec.topic` (+ factories + `hasTopic()`); Kafka `effectiveTopic` wired into publish + subscribe + failure-JFR topic resolution; `KafkaEventRegistry.specOfOrdinal`; `AbstractEventRegistryTck` round-trip + `KafkaTopicResolutionTest` + `AbstractKafkaEventEngineTck` override round-trip + `AbstractEventBusTck` topic-blind note; this ADR; the `events.md` record. Global index row registered in `exeris-docs/adr-index.md` as a separate commit.
2. **Lockstep (separate repos):** `exeris-tooling` — `KernelEventGenerator` populates `EventTypeSpec.ofPersistent(name, ordinal, topic)` from `DomainEventMetadata.topic` + an e2e assertion that the captured `@DomainEvent.topic` lands on the spec. `exeris-sdk` — update the `@DomainEvent.topic` "Open-Core status" Javadoc to the new stance.
3. **Downstream:** broker bindings that add real consumers route on the seam; a per-instance override, if ever needed, is a separate additive step.
