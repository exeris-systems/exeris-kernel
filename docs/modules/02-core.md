# Physical Tier: Core (The Brain)

**Module:** `exeris-kernel-core`
**Dependencies:** `exeris-kernel-spi` ONLY.

## 🧠 Architectural Rules (L0 Enforcement)

1. **Driver Agnosticism:** Core must NEVER know if it's running on Community or Enterprise drivers. It interacts
   exclusively via `ServiceLoader` and SPI contracts.
2. **Orchestration Only:** Core makes decisions (Watermarks, Load Shedding, Backpressure), but does not execute the
   physical I/O.
3. **JEP 506 Strictness:** Context (Security, Tenant) is propagated strictly via `ScopedValue`. `ThreadLocal` is
   entirely BANNED.
4. **Fail-Fast Bootstrap:** Must validate all injected SPI providers at T-minus 0 and halt the JVM if contracts are not
   met.