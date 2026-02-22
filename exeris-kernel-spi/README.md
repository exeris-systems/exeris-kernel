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