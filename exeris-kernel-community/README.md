# Exeris Kernel Community

**Module:** `eu.exeris:eu.exeris-kernel-community`  
**Role:** L1 Java-Standard Implementations

## Overview
The Community module provides standard, open-source implementations of the Kernel SPIs. It is designed for compatibility and ease of use in environments where native kernel-bypass is not required.

## 📦 Included Drivers
- **`FileConfigProvider`**: Hierarchical configuration loader (JSON/YAML) powered by Jackson 3.
- **`FileWatcherService`**: Hot-reload mechanism for configuration fields using NIO.2 `WatchService`.
- **JDBC Adapters**: Standard database connectivity using HikariCP.