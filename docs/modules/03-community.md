# Physical Tier: Community (The Muscle)

**Module:** `exeris-kernel-community`
**Dependencies:** `exeris-kernel-spi` ONLY.

## 💪 Architectural Rules (L0 Enforcement)

1. **Pure Java 26:** This tier relies entirely on standard JDK features (JEP 454 FFM, NIO.2) without requiring external
   native C-libraries or kernel-bypass modules.
2. **Zero-Allocation Effort:** Even in the free tier, hot paths must avoid heap allocation using `PanamaArenaAllocator`
   and pooled `LoanedBuffer`.
3. **Graceful Fallbacks:** Uses standard OS networking (TCP/UDP sockets) and JVM-level threads. Designed for broad
   compatibility across all operating systems.
4. **No Direct Core Access:** Community drivers cannot access `exeris-kernel-core` internals. They only implement the
   SPI.