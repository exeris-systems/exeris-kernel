# Exeris Kernel

Off-heap runtime kernel for the JVM (JDK 25 LTS baseline, preview-clean), organized as a multi-module Maven build: SPI contracts, Core orchestration, the open Community driver, and a TCK that pins observable contract behavior.

**What's different, in one paragraph.** Request and response payloads live off-heap in Panama `MemorySegment`s behind deterministic `LoanedBuffer` ownership; the runtime brings up only the subsystems a deployment declares through `BootstrapSelector` (`http`, `persistence`, `crypto`, `events`, `flow`); and the things that usually require a second process — saga / durable execution (Flow), event sourcing (transactional outbox), JFR-first observability — run inside the application process. The intended consequence is a small resident surface, and it is measured rather than asserted (next section).

**Who this is for:** [docs/assets/help-who-exeris-is-for.md](docs/assets/help-who-exeris-is-for.md) · **Adoption paths:** [docs/assets/hub-hero-adoption-path.md](docs/assets/hub-hero-adoption-path.md)

## Measured properties

Numbers live in [exeris-benchmarks](https://github.com/exeris-systems/exeris-benchmarks) under matched-contract fairness gating; every claim carries a scope label and committed raw artifacts. Highlights for this repository's Community driver:

- **+39% / +57% throughput at −26% / −34% CPU per request** vs tuned pure-JDBC Quarkus and Quarkus+Hibernate on a runtime-bound single-row read, dedicated bare metal — `comparison_eligible` ([triad report](https://github.com/exeris-systems/exeris-benchmarks/blob/main/results/reports/2026-07-21-entity-read-by-id-tuned-pg-triad-comparison-eligible.md)).
- **Full speed in a 128 MiB cgroup budget** on the same workload, with a survivable floor of 128 MiB at a 16 MiB heap — `descriptive` ([memory×CPU sweep](https://github.com/exeris-systems/exeris-benchmarks/blob/main/results/reports/2026-07-22-entity-read-by-id-memory-cpu-sweep.md)).
- **A result that cuts against us, on purpose:** going from plaintext to TLS, the Off-Heap TLS engine adds **+0.0069 ms of CPU per request against BoringSSL's +0.0040 ms** — about 73% more added cost, native-vs-native (netty-tcnative, not JSSE), so it cannot be discounted as a JSSE comparison. It is a genuine headroom item. Those are the absolute deltas, which is what the 73% is a ratio of; as a share of each stack's own plaintext cost the taxes read +12.7% and +5.8%, and those two do **not** divide into a meaningful figure because their baselines differ (0.0540 vs 0.0691 ms). The other half of the finding belongs with it: Exeris still leads every absolute axis under TLS (49.4 k vs 43.4 k rps, 0.0609 vs 0.0731 ms CPU/req), so this is a larger tax off a stronger base — same sweep. Reports in the lab publish what loses, with revision histories.

## Current Repository State

- This repository contains source modules for SPI, Core, Community, TCK, BOM, and build config.
- Enterprise runtime code is not part of this repository.
- HTTP codec/runtime code is currently embedded in Core in this repository layout.
- Spring Boot starter/auto-configuration module is not present in this repository.

## Module Map

Root reactor modules from [pom.xml](pom.xml):

- exeris-kernel-build-config
- exeris-kernel-bom
- exeris-kernel-parent
- exeris-kernel-spi
- exeris-kernel-tck
- exeris-kernel-core
- exeris-kernel-community-testkit
- exeris-kernel-community
- exeris-kernel-community-kafka
- exeris-kernel-diagnostics-cli

Present in a clone but outside the root reactor:

- `tools` — build and CI tooling: `spi-api-diff` (the ADR-065 SPI compatibility gate),
  `preview-bytecode-scan`, `sbom-gate`, `release-readiness`, `jfr-reporter`

`exeris-kernel-enterprise` is referenced by the module docs and the enterprise licence text but is **not** part of this repository; it is a separate, closed distribution.

## Architecture Baseline

The project keeps strict separation between contracts and implementations ("The Wall"):

- SPI defines contracts and carriers.
- Core orchestrates via SPI and remains driver-agnostic.
- Community provides open implementations for current repository runtime paths.
- TCK verifies observable SPI contract behavior.

Authoritative references:

- [docs/modules/01-spi.md](docs/modules/01-spi.md)
- [docs/modules/02-core.md](docs/modules/02-core.md)
- [docs/modules/03-community.md](docs/modules/03-community.md)
- [docs/modules/04-enterprise.md](docs/modules/04-enterprise.md)
- [docs/modules/05-tck.md](docs/modules/05-tck.md)
- [docs/modules/06-testkit.md](docs/modules/06-testkit.md)

## Requirements

- Linux, macOS, or Windows
- JDK 25 LTS — the distributed artifact is preview-clean and needs no `--enable-preview`
- Maven 3.9+

The build baselines on JDK 25 LTS and native-access flags in [pom.xml](pom.xml). Main sources compile
without `--enable-preview`, and so does the TCK's test-jar — the one published artifact built from
test sources (ADR-066). Other test sources still compile and run with the flag; they are not
distributed, so nothing a consumer downloads carries preview bytecode. This is checkable rather than
asserted: `tools/preview-bytecode-scan/preview-bytecode-scan.sh` reads the shipped class files, and
preview bytecode is stamped (`minor_version = 0xFFFF`), so a scan of the published jars answers it
without trusting this paragraph. A second artifact ships from the `preview` branch on the newest JDK,
with preview features on.

## Build and Test

Build all reactor modules:

```bash
mvn clean install
```

Build selected module with dependencies:

```bash
mvn -pl exeris-kernel-core -am clean install
```

Tagged suites (`integration`, `continuity`, `stress`) are **excluded** from the command above and run
in dedicated CI gates, so a green `mvn clean install` is not evidence about them. Run one explicitly:

```bash
mvn -pl exeris-kernel-community -DincludedGroups=integration -DexcludedGroups= test
```

## Where to Start Reading Code

- SPI contracts: [exeris-kernel-spi/src/main](exeris-kernel-spi/src/main)
- Core bootstrap and orchestration: [exeris-kernel-core/src/main](exeris-kernel-core/src/main)
- Community providers and integration paths: [exeris-kernel-community/src/main](exeris-kernel-community/src/main)
- Contract tests and TCK suites: [exeris-kernel-tck/src/test](exeris-kernel-tck/src/test)

## Documentation Index

- Getting started (developer guides): [docs/guides](docs/guides)
- Architecture overview: [docs/architecture.md](docs/architecture.md)
- Performance contract: [docs/performance-contract.md](docs/performance-contract.md)
- Whitepaper: [docs/whitepaper.md](docs/whitepaper.md)
- ADRs: [docs/adr](docs/adr)
- Subsystems: [docs/subsystems](docs/subsystems)
- Module contracts: [docs/modules](docs/modules)
- Help asset: [docs/assets/help-who-exeris-is-for.md](docs/assets/help-who-exeris-is-for.md)
- Hub/Hero asset: [docs/assets/hub-hero-adoption-path.md](docs/assets/hub-hero-adoption-path.md)

## Licensing

- Everything in this repository: **Apache License, Version 2.0** — [LICENSE](LICENSE), unmodified, with no addendum or field-of-use condition
- What is open and what is commercial, with SPDX identifiers: [LICENSING.md](LICENSING.md)
- The name is not covered by the licence (Apache-2.0 §6): [TRADEMARK.md](TRADEMARK.md)
- [LICENSE-ENTERPRISE](LICENSE-ENTERPRISE) governs `exeris-kernel-enterprise`, which is **not in this repository** — the text is kept here so the boundary is documented where a reviewer looks for it, and it does not qualify the line above
- Additional module-level notes: module-local LICENSE/README files

For legal questions: legal@exeris.eu
