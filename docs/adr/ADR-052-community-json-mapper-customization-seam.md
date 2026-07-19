# ADR-052: Community JSON Mapper Customization Seam — `JsonMapperCustomizer` / `JsonMapperScope`

| Attribute       | Value                                                                                                       |
|:----------------|:------------------------------------------------------------------------------------------------------------|
| **ADR #**       | **052** (reserved 2026-07-19 in `exeris-docs/adr-index.md`).                                                 |
| **Status**      | **Accepted** — target **v0.10.1**. Per-repo (`exeris-kernel`); no `exeris-tooling` / enterprise lockstep.   |
| **Deciders**    | Arkadiusz Przychocki                                                                                         |
| **Date**        | 2026-07-19                                                                                                   |
| **Scope**       | kernel/community (per-repo)                                                                                  |
| **Owning Repo** | `exeris-kernel`                                                                                              |
| **Compliance**  | The Wall (ADR-006); No Waste Compute; Java-26 idioms (ServiceLoader model, immutable carriers); extends the body/event codec lineage (ADR-009 / ADR-034 / ADR-036 / ADR-046) |

## Context

The `{request,response} × {encode,decode}` HTTP body-codec matrix (ADR-009 / ADR-034 / ADR-036) and
the event-payload codec (ADR-046) are complete tier-neutral SPI seams, and every concrete Community
driver already accepts an **injected** `tools.jackson.databind.ObjectMapper` in its constructor:

- `JsonBodyEncoder(ObjectMapper)` — response encode (0.5.0)
- `CommunityJsonRequestBodyEncoder(ObjectMapper)` — request encode (ADR-034)
- `CommunityJsonResponseBodyDecoder(ObjectMapper)` — response decode (ADR-034)
- `CommunityJsonRequestBodyDecoder(ObjectMapper)` — request decode (ADR-036)
- `CommunityJsonEventPayloadCodec(ObjectMapper)` — events (ADR-046)

The gap is **one layer below the SPI**: the providers that assemble these drivers
(`CommunityHttpProvider`, `CommunityEventProvider`) fed them a hardcoded `new ObjectMapper()` — a bare
mapper with no customization seam. That default uses Jackson's shared `MethodHandle`-based property
accessors: because one `MethodHandle` field is reused across all properties and types, the accessor
call-site is **megamorphic** and the JIT does not inline it, which benchmarks show costing a material
slice of response-serialization CPU on the `exchange.respond(status, payload)` hot path. The classic
remedy is the Blackbird module (`jackson-module-blackbird`), which emits a dedicated `invokedynamic`
call-site per property via `LambdaMetafactory` — monomorphic, C2-inlineable — but there was **no way to
register it** (or any module / feature) on the kernel-owned mapper. The application-owned `ObjectMapper`
in a handler is a different object on a different path; it cannot influence the provider's codecs.

This decision does **not** add a codec quadrant and changes **no** SPI type, driver class, or generated
code. It records how the Community tier now *sources* the mapper it injects.

## Decision

**Introduce a Community-internal customization seam so the providers source each codec's `ObjectMapper`
through a `ServiceLoader`-discovered `JsonMapperCustomizer` chain instead of `new ObjectMapper()`, scoped
per codec quadrant. The change is strictly additive and default byte-identical; Jackson stays a Community
driver detail (The Wall holds — no SPI type sees `ObjectMapper` or the seam).**

### 1. Seam (new, in `eu.exeris.kernel.community.json`)

A new leaf package shared by `community.http` and `community.events` (placing it under `community.http`
would force `community.events` to depend on `community.http` — a subsystem inversion):

```java
public enum JsonMapperScope {
    HTTP_RESPONSE_ENCODE, HTTP_REQUEST_ENCODE, HTTP_RESPONSE_DECODE, HTTP_REQUEST_DECODE, EVENTS
}

public interface JsonMapperCustomizer {                       // discovered via ServiceLoader
    default boolean appliesTo(JsonMapperScope scope) { return true; }
    void customize(JsonMapperScope scope, JsonMapper.Builder builder);   // Jackson-3 immutable-mapper idiom
    default int order() { return 0; }                          // ascending; later overrides earlier
}

public final class CommunityJsonMappers {
    public static ObjectMapper forScope(JsonMapperScope scope);           // applies discovered customizers
}
```

The signature takes a Jackson-3 `JsonMapper.Builder` because a Jackson-3 `ObjectMapper` is **immutable
once built** — all module/feature configuration must happen on the builder. Discovery order from
`ServiceLoader` is unspecified, so `order()` makes multi-customizer resolution deterministic.

### 2. Default preservation (byte-identical)

`CommunityJsonMappers.forScope(scope)` returns a plain `new ObjectMapper()` whenever **no** discovered
customizer `appliesTo(scope)` — the exact pre-0.10.1 object, with no `JsonMapper.builder().build()`
round-trip that could drift from the bare-constructor defaults. The customizing builder path is entered
only when at least one customizer opts in. Pinned by
`CommunityJsonMappersTest.noCustomizerYieldsByteIdenticalDefaultMapper` across every scope.

### 3. Provider wiring

`CommunityHttpProvider` sources four mappers (one per HTTP quadrant) and `CommunityEventProvider` one
(`EVENTS`), each via `forScope(...)`, replacing the single shared `DEFAULT_MAPPER`. Building per-scope
(five default mappers at bootstrap instead of one shared) is off the hot path and immaterial; it is what
lets a customizer target, e.g., only `HTTP_RESPONSE_ENCODE` (the benchmarked hot path) without touching
decode.

### 4. No client-facade scope; Blackbird is application-supplied

There is deliberately **no** `WEB_CLIENT` scope. `KernelWebClient` lives in `exeris-kernel-core` and is
constructed **explicitly** by applications with their chosen registries (ADR-034 §322), so a per-instance
client mapper is already an application capability — an app that needs 2–3 clients with different mappers
builds each client's registries with its own `ObjectMapper` today, no new surface required. The
provider-exposed default client codecs are covered by `HTTP_REQUEST_ENCODE` + `HTTP_RESPONSE_DECODE`.

Community ships **neither** `jackson-module-blackbird` **nor** a Blackbird customizer: with nothing
registered the default is unchanged, and the artifact is not pulled unless an application opts in by
supplying its own `JsonMapperCustomizer` + the module dependency + a `META-INF/services` entry.

## Alternatives considered

- **A `JsonMapper.Builder`-per-property config field on `HttpConfig` / an SPI carrier.** Rejected — a hard
  The Wall breach. `HttpConfig`'s own Javadoc forbids implementation tuning in the record; `ObjectMapper`
  is a `tools.jackson` type that must never enter `exeris-kernel-spi`.
- **A named strategy knob (`-Dexeris.http.json.accessor=blackbird|default`) read at class-init.** Viable
  (there is precedent: `http.stream.creditWindowBytes` is read directly from a system property), but it
  is a narrow switch — it only toggles a fixed Community-shipped strategy, forcing Blackbird to become a
  (optional) Community dependency and requiring reflective/optional loading. The ServiceLoader seam gives
  full, open-ended configuration with no Community Jackson-module dependency.
- **Jackson-3 module auto-discovery (`JsonMapper.builder().findAndAddModules()`).** Least code — Blackbird
  would register by mere classpath presence. Rejected as the primary mechanism: it makes serialization
  behavior an implicit function of classpath contents, the kind of non-determinism the kernel avoids
  (config.md "deterministic T-0"), and gives the application no control over features or ordering. A
  customizer may still call `findAndAddModules()` itself if an app wants that behavior explicitly.

## Consequences

### Positive
- **[+] Closes the customization gap on the response-encode hot path.** An app can register Blackbird (or
  any module/feature) so property access becomes monomorphic `invokedynamic`, per-scope.
- **[+] The Wall holds unchanged.** No SPI type, driver class, or generated code changes; `ObjectMapper`
  and the seam are Community-only. Per-repo — no tooling/enterprise lockstep or link stubs.
- **[+] Default byte-identical, zero-regression.** No customizer ⇒ the same bare mapper as before,
  test-pinned per scope.
- **[+] Uses the sanctioned ServiceLoader model** (Hard Constraints) and needs no Community Jackson-module
  dependency.

### Trade-offs
- **[-] Five default mappers at bootstrap** instead of one shared (per-quadrant serializer caches). Boot
  time only, immaterial; the per-scope split is what enables targeted customization.
- **[-] A new Community extension surface** (`JsonMapperCustomizer` / `JsonMapperScope`) to keep stable.

### What is NOT in scope
- **No perf claim is ratified here.** Whether Blackbird actually wins on a given workload is an
  application choice, validated separately under the JMH/JFR discipline (`exeris-jfr-perf-research`); the
  seam only makes the choice possible.
- **No JFR event** for which customizers applied to which scope. A Glass-Box "mapper assembled with N
  customizers" event is a reasonable follow-up but is not required for this bootstrap-time config step.
- **No `WEB_CLIENT` scope** (see Decision §4).

## Cross-references
- ADR-006 — The Wall — `ObjectMapper` / the customizer seam stay a Community driver detail.
- ADR-009 / ADR-034 / ADR-036 — the HTTP body-codec matrix whose drivers already take an injected mapper.
- ADR-046 — the event-payload codec whose driver this seam also feeds.
- `exeris-kernel-community/src/main/java/eu/exeris/kernel/community/json/` — the seam.
- `exeris-kernel-community/.../http/CommunityHttpProvider.java`, `.../events/CommunityEventProvider.java` —
  the providers that now source mappers per scope.

## Engineering Protocol
1. **ADR number reserved** in `exeris-docs/adr-index.md` (051) before content — register discipline.
2. **Per-repo, additive.** SPI, drivers, and generated code untouched; no lockstep.
3. **Tests (Community).** `CommunityJsonMappersTest` (default byte-identical per scope, ordering,
   `appliesTo` filtering, config application) + `CommunityJsonMapperServiceLoaderTest` (discovery via a
   real `META-INF/services` no-op customizer). No `Abstract*Tck` — the seam is Community-internal, not an
   SPI contract.
4. **Docs.** `docs/subsystems/http.md` and `docs/subsystems/events.md` note the seam.
5. **Release.** Lands on `feature/v0101-configurable-json-mapper` off `main`; forward-ported to
   `development/0.11.0`.
