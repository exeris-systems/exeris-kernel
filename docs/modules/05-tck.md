# Physical Tier: TCK (The Judge)

**Module:** `exeris-kernel-tck` (Technology Compatibility Kit)
**Dependencies:** `exeris-kernel-spi`

## ⚖️ Architectural Rules

1. **Verification, Not Implementation:** TCK provides test suites that verify if a given Driver (Community/Enterprise)
   correctly implements the SPI.
2. **SLO Enforcement:** Contains JMH benchmarks and JFR inspectors to verify that a driver does not violate the
   "Zero-Allocation" or "Latency < 200µs" rules.
3. **Leak Detection:** Tests must run with `LeakDetectionMode.PARANOID` to catch unclosed off-heap memory segments.