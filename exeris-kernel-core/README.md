# Exeris Kernel Core

**Module:** `eu.exeris:exeris-kernel-core`  
**Role:** L0 Orchestration & Native Transport

## Overview
The Core is the high-performance engine of Exeris. It orchestrates the boot process and provides the base native capabilities of the platform.

## 🧠 Components
- **`CoreOrchestrator`**: Binds the global `ScopedValue` context and runs parallel subsystem boot via `StructuredTaskScope`.
- **`KernelBootstrap`**: Resolves the subsystem dependency graph using Kahn’s topological sort.
- **`ExerisSmartLauncher`**: Calculates optimal hardware-bound memory partitions (Heap vs. Off-Heap).

## 🔌 Base Native Capabilities
- **TCP Transport**: Native C-socket integration via Panama FFM (bypasses `java.net`).
- **OpenSSL**: High-throughput TLS implementation.