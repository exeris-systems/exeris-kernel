# Exeris Kernel

Repository for the Exeris runtime kernel (Java 26 GA with preview stack), organized as a multi-module Maven build.

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
- JDK 26 with preview features enabled
- Maven 3.9+

The build is configured for Java 26 preview and native access flags in [pom.xml](pom.xml).

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

- Architecture overview: [docs/architecture.md](docs/architecture.md)
- Performance contract: [docs/performance-contract.md](docs/performance-contract.md)
- Whitepaper: [docs/whitepaper.md](docs/whitepaper.md)
- ADRs: [docs/adr](docs/adr)
- Subsystems: [docs/subsystems](docs/subsystems)
- Module contracts: [docs/modules](docs/modules)
- Help asset: [docs/assets/help-who-exeris-is-for.md](docs/assets/help-who-exeris-is-for.md)
- Hub/Hero asset: [docs/assets/hub-hero-adoption-path.md](docs/assets/hub-hero-adoption-path.md)

## Licensing

- Community-side modules (SPI, Core, Community, TCK): [LICENSE-COMMUNITY](LICENSE-COMMUNITY)
- Enterprise license text: [LICENSE-ENTERPRISE](LICENSE-ENTERPRISE)
- Additional module-level notes: module-local LICENSE/README files

For legal questions: legal@exeris.eu
