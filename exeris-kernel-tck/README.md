# Exeris Kernel TCK

**Module:** `eu.exeris:exeris-kernel-tck`  
**Role:** Technology Compatibility Kit

## Overview
The TCK (Technology Compatibility Kit) ensures that any implementation of Exeris SPIs (Community or Enterprise) adheres to strict performance and safety contracts.

## 🧪 Testing Scope
- **Lifecycle Compliance:** Verifies that subsystems respect topological initialization order.
- **Memory Safety:** Ensures zero-copy buffers are correctly released back to the `MemoryArbiter`.
- **Context Inheritance:** Validates that `ScopedValue` propagation works across Virtual Thread forks.