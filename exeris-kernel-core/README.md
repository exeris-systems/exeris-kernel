# Exeris Kernel Core

**Module:** `eu.exeris:exeris-kernel-core`  
**Role:** L0 Orchestration & Native Transport

## Overview
The Core is the high-performance engine of Exeris. It orchestrates the boot process and provides the base native capabilities of the platform.

## 🧠 Components

- **`SubsystemOrchestrator`**: Binds the global `ScopedValue` context and drives parallel subsystem boot
  via `StructuredTaskScope`.
- **`WatermarkManager`**: Continuously samples off-heap usage and transitions the kernel watermark level
  (NORMAL → WARNING → CRITICAL → SHEDDING).
- **`ResourceArbiter`**: Makes O(1) ALLOW / THROTTLE / REJECT decisions for incoming stream admission
  based on the current watermark level.
- **`OffHeapTlsEngine`**: Zero-copy TLS state machine over off-heap OpenSSL via Project Panama FFM.
- **`PaqsScheduler`**: Priority-Aware Queue Scheduler — stream admission and load-shedding at the
  transport edge.
- **`KernelBootstrap`** *(planned — TRL-4)*: Full `ServiceLoader`-backed multi-provider discovery
  and topological subsystem boot, replacing the current `SubsystemOrchestrator` direct-wiring.
- **`CoreOrchestrator`** *(planned — TRL-4)*: Higher-level lifecycle coordinator wrapping
  `KernelBootstrap`, exposing the canonical `INIT → READY → SHUTTING_DOWN` state machine.

## 🔌 Base Native Capabilities
- **TCP Transport**: Native C-socket integration via Panama FFM (bypasses `java.net`).
- **OpenSSL**: High-throughput TLS implementation.

## ⚖️ Licence

This module is licensed under the **Apache License, Version 2.0** (`SPDX-License-Identifier: Apache-2.0`).
See the [LICENSE](LICENSE) file in this directory for the full text.

**In brief:** you may use, modify, fork, and redistribute this module in any
product or service — including commercial production deployments — as long as
you are not selling the Exeris Community modules themselves as a standalone
hosted runtime or competing distribution.

For questions: legal@exeris.eu
