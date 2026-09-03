# Exeris Kernel SPI

**Module:** `eu.exeris:exeris-kernel-spi`  
**Role:** L0 Architecture Contracts

## Overview
The SPI (Service Provider Interface) defines the immutable laws of the Exeris Kernel. It contains only interfaces and **Value Records** with zero concrete dependencies.

## ⚖️ Core Contracts
- **`Subsystem`**: Lifecycle management (Initialize -> Start -> Stop).
- **`ConfigProvider`**: JIT-optimized, lazy configuration access via `LazyConstant`.
- **`KernelContext`**: Identity-free state propagation via `ScopedValue`.

## 🧬 Valhalla Readiness
All data structures are implemented as `record` types annotated with `@ValueCandidate`. They are architecturally ready to be promoted to `value record` (JEP 401) for zero-object-header memory efficiency.

## ⚖️ Licence

This module is licensed under the **Apache License, Version 2.0** (`SPDX-License-Identifier: Apache-2.0`).
See [LICENSE](../LICENSE) for the full text and [LICENSING.md](../LICENSING.md) for the
open-core map. Note that Apache-2.0 section 6 grants no trademark rights — see
[TRADEMARK.md](../TRADEMARK.md).

**In brief:** you may use, modify, fork, and redistribute this module in any
product or service — including commercial production deployments — as long as
you are not selling the Exeris Community modules themselves as a standalone
hosted runtime or competing distribution. Implementing your own SPI provider
(including a QUIC transport) is explicitly permitted and encouraged.

For questions: legal@exeris.eu
