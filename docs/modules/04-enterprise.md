# Physical Tier: Enterprise (The Heavy Muscle)

**Module:** `exeris-kernel-enterprise` (Proprietary / Closed Source Extension)
**Dependencies:** `exeris-kernel-spi` ONLY.

## 🚀 Architectural Rules (L0 Enforcement)

1. **Hyper-Density:** Designed for extreme throughput (>8,500 RPS/vCPU). Uses `GlobalMemoryArbiter` with pre-allocated
   `mmap` regions.
2. **Kernel Bypass & Native I/O:** Leverages `io_uring` (Linux) and advanced native C-interop (OpenSSL for QUIC)
   directly via FFM.
3. **Strict Zero-Copy:** Bytes must travel from NIC to DB Driver without ever crossing into the JVM Heap.
4. **Hardware Awareness:** Code here is allowed to optimize based on CPU Cache Lines (padding), NUMA nodes, and OS Page
   Sizes.