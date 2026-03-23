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

## ⚖️ Licence

This module is licensed under the **Apache License 2.0 with Commons Clause**.
See the [LICENSE](LICENSE) file in this directory for the full text.

**In brief:** you may use, modify, fork, and redistribute this module in any
product or service — including commercial production deployments — as long as
you are not selling the Exeris Community modules themselves as a standalone
hosted runtime or competing distribution.

For questions: <legal@exeris.eu>
