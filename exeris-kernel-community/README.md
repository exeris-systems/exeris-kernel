# Exeris Kernel Community

**Module:** `eu.exeris:exeris-kernel-community`  
**Role:** L1 Java-Standard Implementations

> ⚠️ Implementation in progress — see GitHub issue #31–#35.

## Overview

The Community module provides standard, open-source implementations of the Kernel SPIs. It is designed for compatibility and ease of use in environments where native kernel-bypass is not required.

## 📦 Included Drivers

- **`FileConfigProvider`**: Hierarchical configuration loader (JSON/YAML) powered by Jackson 3.
- **`FileWatcherService`**: Hot-reload mechanism for configuration fields using NIO.2 `WatchService`.
- **JDBC Adapters**: Standard database connectivity using HikariCP.

## Local Launcher

The module now exposes a lightweight local runner:

`eu.exeris.kernel.launcher.CommunityStackLauncher`

Behavior:

- Boots `http` by default.
- Boots `persistence` only when `exeris.persistence.jdbcUrl` or `EXERIS_PERSISTENCE_JDBCURL` is explicitly set.
- Supports manual selector override with `-Dexeris.launcher.subsystems=http,persistence`.

Minimal HTTP-only run:

```bash
java --enable-preview --enable-native-access=ALL-UNNAMED \
  -Dexeris.http.mode=SERVER \
  -Dexeris.http.bindHost=127.0.0.1 \
  -Dexeris.http.port=8080 \
  -cp "..." \
  eu.exeris.kernel.launcher.CommunityStackLauncher
```

HTTP + persistence run:

```bash
java --enable-preview --enable-native-access=ALL-UNNAMED \
  -Dexeris.http.mode=SERVER \
  -Dexeris.http.bindHost=127.0.0.1 \
  -Dexeris.http.port=8080 \
  -Dexeris.persistence.jdbcUrl=jdbc:postgresql://localhost:5432/exeris \
  -Dexeris.persistence.username=exeris \
  -Dexeris.persistence.password=secret \
  -cp "..." \
  eu.exeris.kernel.launcher.CommunityStackLauncher
```

Exposed probes:

- `/health`
- `/db/ping` — returns `503` when persistence is not bootstrapped

## E2E Load Harness (P0)

Minimal, repeatable P0 harness was moved out of the module tree and now lives in `exeris-benchmarks/exeris-kernel-community/e2e`.

- `launcher.sh` — builds classpath from current workspace and starts `CommunityStackLauncher`
- `postgres-container.sh` — starts/stops a local Docker PostgreSQL for `--mode postgres`
- `k6-health.js` — load probe for `/health`
- `k6-db-ping.js` — load probe for `/db/ping`
- `h2load-health.sh` — HTTP/1.1 load run for `/health`

### 1) Start stack (HTTP only)

```bash
./exeris-benchmarks/exeris-kernel-community/e2e/launcher.sh --mode http --port 18080
```

### 2) Start stack (HTTP + in-memory H2)

```bash
./exeris-benchmarks/exeris-kernel-community/e2e/launcher.sh --mode h2 --port 18081
```

### 3) Start local PostgreSQL and run stack against it

```bash
./exeris-benchmarks/exeris-kernel-community/e2e/postgres-container.sh up
./exeris-benchmarks/exeris-kernel-community/e2e/launcher.sh --mode postgres --port 18082 \
  --jdbc-url jdbc:postgresql://127.0.0.1:5432/exeris \
  --jdbc-user exeris \
  --jdbc-password exeris
```

Cleanup:

```bash
./exeris-benchmarks/exeris-kernel-community/e2e/postgres-container.sh down
```

### 4) Run k6

```bash
k6 run -e BASE_URL=http://127.0.0.1:18080 ./exeris-benchmarks/exeris-kernel-community/e2e/k6-health.js
k6 run -e BASE_URL=http://127.0.0.1:18081 -e EXPECT_STATUS=200 ./exeris-benchmarks/exeris-kernel-community/e2e/k6-db-ping.js
```

### 5) Run h2load

```bash
PORT=18080 N=20000 C=200 T=4 ./exeris-benchmarks/exeris-kernel-community/e2e/h2load-health.sh
```

Notes:

- `k6` and `h2load` are external tools and must be installed on the host.
- `launcher.sh` intentionally prefers local `target/classes` for `spi/core/community` and filters stale `eu.exeris` jars from `~/.m2`.
- This harness is now treated as perf-lab tooling, not as part of the module-local contract test set.

## ⚖️ Licence

This module is licensed under the **Apache License 2.0 with Commons Clause**.
See the [LICENSE](LICENSE) file in this directory for the full text.

**In brief:** you may use, modify, fork, and redistribute this module in any
product or service — including commercial production deployments — as long as
you are not selling the Exeris Community modules themselves as a standalone
hosted runtime or competing distribution.

For questions: <legal@exeris.eu>
