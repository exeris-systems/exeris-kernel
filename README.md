# Exeris Kernel

Repository for the Exeris runtime kernel (JDK 25 LTS, preview-clean), organized as a multi-module Maven build.

This README is a developer entrypoint for the current repository state.

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

Related directories present in workspace but not part of the root reactor include:

- exeris-kernel-enterprise (distribution-specific / out-of-repo runtime path)
- tools (tooling helpers)

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
distributed, so nothing a consumer downloads carries preview bytecode. A second artifact ships from
the `preview` branch on the newest JDK, with preview features on.

## Build and Test

Build all reactor modules:

```bash
mvn clean install
```

Build selected module with dependencies:

```bash
mvn -pl exeris-kernel-core -am clean install
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

- Everything in this repository: **Apache License, Version 2.0** — [LICENSE](LICENSE)
- What is open and what is commercial, with SPDX identifiers: [LICENSING.md](LICENSING.md)
- The name is not covered by the licence (Apache-2.0 §6): [TRADEMARK.md](TRADEMARK.md)
- Enterprise license text: [LICENSE-ENTERPRISE](LICENSE-ENTERPRISE)
- Additional module-level notes: module-local LICENSE/README files

For legal questions: legal@exeris.eu
