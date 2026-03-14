# Kernel Subsystem: Exceptions (L0 Foundation)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.exceptions.*` (Domain exceptions, Error codes, GlassBox support)
- Core: `eu.exeris.kernel.core.exceptions.*` (Error mappers, Registry)

**Layer:** L0 (Foundation)
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Exceptions subsystem** provides a unified hierarchy and error-mapping framework designed for high-density
environments. It introduces **Zero-Allocation Telemetry** capabilities by capturing raw context without string
formatting. It implements:

- **Standardized Error Codes:** All exceptions enforce the `EX-[DOMAIN]-[ID]` format (e.g., `EX-MEM-1001`).
- **Glass Box Telemetry Support:** Base exceptions capture a `rawArgs: Object[]` payload of raw primitives (`long`,
  `int`, `Enum`) instead of concatenated Strings, enabling the Enterprise tier to dump binary crash logs in nanoseconds.
- **Centralized Error Mapper Registry:** Translates internal Kernel states to **Abstract Transport Codes**, which
  drivers later translate into protocol-specific responses (e.g., HTTP 503 or HTTP/3 `H3_EXCESSIVE_LOAD`).
- **Environment-Aware Disclosure:** PROD shows minimal info; DEV exposes full stack trace.
- **Distributed Tracing:** Every exception automatically captures a UUID `traceId`.

---

## Core Philosophy: Every Exception is a Data Frame

- **Glass Box Pattern:** We store raw primitives (`long`, `int`, `Enum`) in `rawArgs[]`. Binary crash logs are dumped
  in nanoseconds by the Enterprise tier — `StringBuilder` and `String.formatted()` are strictly banned in constructors.
- **Autoboxing on the Exception Path:** `rawArgs: Object[]` requires autoboxing primitives (e.g., `long` → `Long`).
  This is the **only** place in the Kernel where autoboxing is permitted, because exceptions are exceptional states —
  not data flow. A healthy system never pays this cost.
- **Protocol Blindness:** Core is blind to HTTP, QUIC, or Database protocols. It speaks only in abstract states:
  `EXCESSIVE_LOAD`, `UNAUTHORIZED`, `INTERNAL_ERROR`. Each Driver (Community or Enterprise) independently translates
  these to its native protocol codes.
- **Privacy-First Telemetry (CWE-532 Contract):** Any configuration value, connection URL, or persistence argument
  captured in `rawArgs` **MUST** be redacted or truncated by the caller before emission. Emitting raw secrets,
  credentials, or tokens into the binary telemetry dump constitutes a CWE-532 violation against the Exeris Security
  Contract. See `EX_CFG_1002` in `KernelErrorCodes.java` for the canonical example.

---

## Responsibilities

**What Exceptions SPI DOES:**

1. Define `ExerisKernelException` base class and all domain exception subclasses.
2. Enforce the presence of an `EX-` error code constant (from `KernelErrorCodes`) and `traceId` on every instance.
3. Define the `rawArgs: Object[]` structure — the binary contract between throw-site and Glass-Box decoder.

**What Exceptions Core DOES:**

1. Maintain the `ErrorMapperRegistry` mapping Kernel exceptions to generic `TransportErrorCode` enums.
2. Map external library exceptions (e.g., `SQLException`, `TimeoutException`) to Exeris domain exceptions.

---

## Standardized Error Codes (The `EX-` Prefix)

> **Source of truth:** `KernelErrorCodes.java` in `exeris-kernel-spi`. This table is a human-readable index.
> The `rawArgs` binary layout per code is defined in the Javadoc of each constant and must not diverge.

### Memory (`EX-MEM-`)

| Code          | Description             | Glass-Box Payload                               |
|:--------------|:------------------------|:------------------------------------------------|
| `EX-MEM-1001` | Off-heap Exhausted      | `[0] long reqBytes, [1] long availBytes`        |
| `EX-MEM-1002` | Arena Leak Detected     | `[0] long segAddr, [1] long segSize`            |
| `EX-MEM-1003` | AllocationHint Conflict | *(no rawArgs)*                                  |

### Bootstrap (`EX-BOOT-`)

| Code           | Description                  | Glass-Box Payload                                |
|:---------------|:-----------------------------|:-------------------------------------------------|
| `EX-BOOT-0001` | DAG Cycle Detected           | `[0] String[] cycleMembers`                      |
| `EX-BOOT-0002` | Subsystem Init Failure       | *(opaque — variable arity per pathway)*          |
| `EX-BOOT-0003` | Init Timeout                 | `[0] String subsystemName, [1] long deadlineMs`  |
| `EX-BOOT-0004` | Memory Provider Init Failure | `[0] String providerName, [1] long reqBytes`     |
| `EX-BOOT-3001` | Telemetry Provider Failure   | `[0] String providerName, [1] String reason`     |

### Runtime (`EX-RUN-`)

| Code          | Description             | Glass-Box Payload                                    |
|:--------------|:------------------------|:-----------------------------------------------------|
| `EX-RUN-3002` | Carrier Thread Pinning  | `[0] long blockMs, [1] String carrierThreadName`     |

### Network / Transport (`EX-NET-`)

| Code          | Description                 | Glass-Box Payload                                     |
|:--------------|:----------------------------|:------------------------------------------------------|
| `EX-NET-2001` | TLS wrap (encrypt) failure  | `[0] int nativeCode, [1] String detail` — `SSL_write` path |
| `EX-NET-2002` | Crypto Provider Init Failure| `[0] String providerName, [1] String reason`          |
| `EX-NET-2003` | TLS unwrap (decrypt) failure| `[0] int nativeCode, [1] String detail` — `SSL_read` path |
| `EX-NET-4001` | Transport Handshake/Bind    | `[0] String transportName, [1] int port`              |
| `EX-NET-4002` | Transport Send Failure      | `[0] String transportName, [1] long bytesSent`        |
| `EX-NET-4003` | Transport Receive Timeout   | `[0] String transportName, [1] long timeoutMs`        |
| `EX-NET-4004` | Transport Engine Bootstrap  | `[0] String transportName, [1] String reason`         |
| `EX-NET-4005` | Transport Engine Start      | `[0] String transportName, [1] int port`              |
| `EX-NET-4006` | PAQS Load Shedding          | `[0] String transportName, [1] int streamPriority, [2] int thresholdPriority` |
| `EX-NET-4007` | Buffer Exhaustion           | `[0] String transportName, [1] int poolCapacity, [2] int activeSlabs`         |

### HTTP Codec (`EX-HTTP-`, codec-level violations 4001..4006)

| Code           | Description                                  | Glass-Box Payload                      |
|:---------------|:---------------------------------------------|:---------------------------------------|
| `EX-HTTP-4001` | Huffman Decode/Encode Violation              | `[0] String detail`                    |
| `EX-HTTP-4002` | HPACK Decode Violation                       | `[0] String detail`                    |
| `EX-HTTP-4003` | HTTP/2 SETTINGS Validation                   | `[0] String settingName, [1] long actualValue, ...` |
| `EX-HTTP-4004` | HTTP/1.1 Parse Violation (malformed/DoS)     | `[0] String detail`                    |
| `EX-HTTP-4005` | HTTP/2 CONTINUATION Sequence Violation       | `[0] String detail`                    |
| `EX-HTTP-4006` | HTTP/2 Frame Encoding Violation              | `[0] String detail`                    |

### Security (`EX-SEC-`)

| Code          | Description                  | Glass-Box Payload                                           |
|:--------------|:-----------------------------|:------------------------------------------------------------|
| `EX-SEC-2001` | PrincipalContext Missing      | *(no rawArgs)*                                              |
| `EX-SEC-2002` | Token Validation Failed       | `[0] String tokenType, [1] String failureReason`            |
| `EX-SEC-2003` | Insufficient Privileges       | `[0] String requiredRole`                                   |
| `EX-SEC-2004` | StorageContext Missing        | *(no rawArgs)*                                              |

### Persistence (`EX-PERS-`)

| Code           | Description                    | Glass-Box Payload                                                    |
|:---------------|:-------------------------------|:---------------------------------------------------------------------|
| `EX-PERS-5001` | Provider Bootstrap Failure     | `[0] String providerName, [1] String sanitizedConnectionUrl`         |
| `EX-PERS-5002` | Connection Pool Exhausted      | `[0] String providerName, [1] long timeoutMs, [2] int activeConns`   |
| `EX-PERS-5003` | Query Execution Failure        | `[0] String sqlState, [1] String detail`                             |
| `EX-PERS-5004` | Authentication Failure         | `[0] String authMechanism, [1] String serverMessage`                 |
| `EX-PERS-5005` | Persistence Transport Failure  | `[0] String transportName, [1] long fd, [2] int errno`               |
| `EX-PERS-5006` | Interceptor Init Failure       | `[0] String interceptorClass, [1] String isolationKey`               |
| `EX-PERS-5007` | No Provider on Classpath       | `[0] String message`                                                 |

### Graph (`EX-GRPH-`)

| Code           | Description                | Glass-Box Payload                                                |
|:---------------|:---------------------------|:-----------------------------------------------------------------|
| `EX-GRPH-5001` | Engine Bootstrap Failure   | `[0] String providerName, [1] String reason`                     |
| `EX-GRPH-5002` | Query Execution Failure    | `[0] String queryType, [1] String detail`                        |
| `EX-GRPH-5003` | Dual-Write Sync Failure    | `[0] String edgeType, [1] String detail`                         |
| `EX-GRPH-5004` | Path Not Found             | `[0] long sourceMost, [1] long sourceLeast, [2] long targetMost, [3] long targetLeast` |
| `EX-GRPH-5005` | Excessive Allocation       | `[0] String driverName, [1] long bytesAllocated, [2] long xfer`  |

### Events (`EX-EVENT-`)

| Code            | Description              | Glass-Box Payload                                               |
|:----------------|:-------------------------|:----------------------------------------------------------------|
| `EX-EVENT-6001` | Generic Engine Failure   | `[0] String message`                                            |
| `EX-EVENT-6002` | Queue Overflow           | `[0] String eventType, [1] long depth, [2] long capacity`       |
| `EX-EVENT-6003` | Registry Conflict        | `[0] String eventType, [1] int ordinal`                         |
| `EX-EVENT-6004` | Provider Creation Failure| `[0] String providerName, [1] String reason`                    |

### Flow / Saga (`EX-FLOW-`)

| Code           | Description               | Glass-Box Payload                                                               |
|:---------------|:--------------------------|:--------------------------------------------------------------------------------|
| `EX-FLOW-7001` | Provider Engine Failure   | `[0] String providerName, [1] String reason`                                    |
| `EX-FLOW-7002` | Engine Lifecycle Failure  | `[0] String engineName, [1] String phase, [2] String reasonCode, [3] int ctx`   |
| `EX-FLOW-7003` | Step Execution Failure    | `[0] String def, [1] long idMost, [2] long idLeast, [3] int stepIdx, ...`       |
| `EX-FLOW-7004` | Registry Conflict         | `[0] int stepId, [1] String reason`                                             |

### Config (`EX-CFG-`)

| Code          | Description              | Glass-Box Payload                                                             |
|:--------------|:-------------------------|:------------------------------------------------------------------------------|
| `EX-CFG-1001` | Missing Property         | `[0] String missingKey, [1] String providerName`                              |
| `EX-CFG-1002` | Type Mismatch            | `[0] String key, [1] String expectedType, [2] String actualValue` ⚠️ redact  |
| `EX-CFG-1003` | Hot-Reload Read Error    | `[0] String filename, [1] String reason`                                      |

---

## Code Examples

### 1. The Glass-Box Ready Exception (SPI)

```java
package eu.exeris.kernel.spi.exceptions;

public class VirtualThreadPinningException extends ExerisKernelException {

    public VirtualThreadPinningException(long blockTimeMs, String carrierName) {
        super(KernelErrorCodes.EX_RUN_3002, "Virtual Thread pinned the Carrier",
                blockTimeMs, carrierName);
    }
}
```

### 2. Error Mapper Registry (Core — Protocol Blindness)

```java
package eu.exeris.kernel.core.exceptions;

public class ErrorMapperRegistry {

    public TransportErrorCode mapToTransportCode(Throwable ex) {
        if (ex instanceof MemoryExhaustedException) {
            return TransportErrorCode.EXCESSIVE_LOAD;
        }
        if (ex instanceof PrincipalContextMissingException) {
            return TransportErrorCode.UNAUTHORIZED;
        }
        return TransportErrorCode.INTERNAL_ERROR;
    }
}
```

---

## Testing Strategy

### Unit Tests

- Exception construction verifies `traceId` generation.
- `KernelErrorCodes` constant assignment matches the declared `EX-` string value.
- Error mapper registry correctly maps all domain exceptions to `TransportErrorCode` enums.

### Integration Tests

- Full exception flow: throw → `ErrorMapperRegistry.map()` → respond via active Transport Driver.
- Environment-aware disclosure: DEV exposes stack trace, PROD returns opaque error code only.
- Binary Glass-Box round-trip: `rawArgs[]` serialized → deserialized → fields match source primitives.

---

## Summary

The Exceptions subsystem provides type-safe, traced, and environment-aware error handling. By enforcing the `EX-` code
standard, the `rawArgs` binary contract, and CWE-532 privacy rules, it ensures that even under 1M RPS load the Kernel
leaves a perfect, zero-allocation forensic trail — without coupling itself to any specific network protocol or database
driver.
