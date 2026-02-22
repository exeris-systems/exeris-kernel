# Physical Tier: SPI (The Constitution)

**Module:** `exeris-kernel-spi`
**Dependencies:** None (Only standard Java 26+ library)

## 🛡️ Architectural Rules (L0 Enforcement)

1. **No Implementation Details:** Interfaces must never leak implementation specifics (e.g., no `io_uring` flags, no
   Netty references).
2. **Immutable Carriers:** All data transfer objects must be Java `value record` or `value class` (Valhalla readiness)
   to eliminate object headers.
3. **No Logic:** SPI contains only Contracts (Interfaces), Exceptions, Enums, and Constants.
4. **Loaned Memory:** All buffer passing must use `LoanedBuffer` to enforce reference counting and zero-copy semantics.