# Exeris Kernel

The **Zero-Copy Runtime Platform** for high-density architectures. Designed for **Java 26 EA (Project Valhalla)**, leveraging Panama FFM, Virtual Threads, and Native C integration to eliminate the "Abstraction Tax".

## 🏗️ Performance Architecture (The Wall)

Exeris strictly separates contracts from implementations to ensure hardware-level efficiency:

1. **Base Native (Core)**: The default engine. Uses Panama FFM for direct C integration, providing high-performance **TCP** and **OpenSSL** transport while bypassing standard JVM overhead.
2. **Premium Native (Enterprise)**: Advanced kernel-bypass mechanisms including **io_uring**, **QUIC/HTTP3**, and zero-syscall memory management.
3. **Java Adapters (Community)**: Standard, easy-to-use implementations based on pure Java libraries (e.g., Jackson, JDBC).

## 🧩 Key Components

* **`exeris-kernel-spi`**: The immutable constitution. Defines identity-free data carriers (**Value Records**) and lifecycle contracts.
* **`exeris-kernel-core`**: The orchestration engine and base native muscle. Includes the `CoreOrchestrator` and `ExerisSmartLauncher` for sub-millisecond boot and memory partitioning.
* **`exeris-kernel-tck`**: The Technology Compatibility Kit. Ensures that any driver (Community or Enterprise) adheres to the strict performance and safety rules of the SPI.

## ⚖️ License
Copyright (C) 2025-2026 Exeris. All rights reserved.
Distributed under the **Proprietary Exeris Software License**.