# Exeris Kernel TCK

**Module:** `eu.exeris:exeris-kernel-tck`  
**Role:** Technology Compatibility Kit

## Overview
The TCK (Technology Compatibility Kit) ensures that any implementation of Exeris SPIs (Community or Enterprise) adheres to strict performance and safety contracts.

## 🧪 Testing Scope
- **Lifecycle Compliance:** Verifies that subsystems respect topological initialization order.
- **Memory Safety:** Ensures zero-copy buffers are correctly released back to the `MemoryArbiter`.
- **Context Inheritance:** Validates that `ScopedValue` propagation works across Virtual Thread forks.

## ⚖️ Licence

This module is licensed under the **Apache License 2.0 with Commons Clause**.
See the [LICENSE](LICENSE) file in this directory for the full text.

**In brief:** you may use, modify, fork, and redistribute this module in any
product or service — including commercial production deployments — as long as
you are not selling the Exeris Community modules themselves as a standalone
hosted runtime or competing distribution. Using the TCK to certify a
third-party SPI implementation is explicitly permitted.

For questions: legal@exeris.eu
