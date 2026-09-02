# Kernel Subsystem: Exceptions (L0 Foundation)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.exceptions.*` (Domain exceptions, Error codes, GlassBox support)
- Core: `eu.exeris.kernel.core.telemetry.*` (Error mappers, Registry)

> **Note:** `ConfigProviderException` is a nested static class inside `eu.exeris.kernel.spi.config.ConfigProvider` — it is not in the `spi.exceptions` package tree.

**Layer:** L0 (Foundation)
**Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Exceptions subsystem** provides a unified hierarchy and error-mapping framework designed for high-density
environments. It introduces **Zero-Allocation Telemetry** capabilities by capturing raw context without string
formatting. It implements:

- **Standardized Error Codes:** All exceptions enforce the `EX-[DOMAIN]-[ID]` format (e.g., `EX-MEM-1001`).
- **Glass Box Telemetry Support:** Base exceptions capture a `rawArgs: Object[]` payload of raw primitives (`long`,
  int`, `Enum`) instead of concatenated Strings, enabling binary crash logs to be structured and processed efficiently.
- **Centralized Error Mapper Registry:** Translates internal Kernel states to **Abstract Transport Codes**, which
  drivers later translate into protocol-specific responses (e.g., HTTP 503 or HTTP/3 `H3_EXCESSIVE_LOAD`).
- **Environment-Aware Disclosure:** PROD/TEST surface only the opaque `errorCode` + `traceId` envelope and suppress stack traces; DEV surfaces the original message, `rawArgs`, and full stack trace. The redaction policy lives in `eu.exeris.kernel.spi.exceptions.ExceptionDisclosure` and is consumed by every Community sink that produces operator-visible artifacts (see [Disclosure rendering](#disclosure-rendering) below).
- **Distributed Tracing:** Every exception automatically captures a UUID `traceId`.

---

## Core Philosophy: Every Exception is a Data Frame

- **Glass Box Pattern:** We store raw primitives (`long`, `int`, `Enum`) in `rawArgs[]`. Binary crash logs are dumped
  in nanoseconds — `StringBuilder` and `String.formatted()` are strictly banned in constructors.
- **Autoboxing on the Exception Path:** `rawArgs: Object[]` requires autoboxing primitives (e.g., `long` → `Long`).
  This is the **only** place in the Kernel where autoboxing is permitted, because exceptions are exceptional states —
  not data flow. A healthy system never pays this cost.
- **Protocol Blindness:** Core is blind to HTTP, QUIC, or Database protocols. It speaks only in abstract states:
  `EXCESSIVE_LOAD`, `UNAUTHORIZED`, `INTERNAL_ERROR`. Each Driver independently translates
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

### HTTP (`EX-HTTP-`, codec-level violations 4001..4006; subsystem, streaming and request-decode faults 4007..)

| Code           | Description                                  | Glass-Box Payload                      |
|:---------------|:---------------------------------------------|:---------------------------------------|
| `EX-HTTP-4001` | Huffman Decode/Encode Violation              | `[0] String detail`                    |
| `EX-HTTP-4002` | HPACK Decode Violation                       | `[0] String detail`                    |
| `EX-HTTP-4003` | HTTP/2 SETTINGS Validation                   | `[0] String settingName, [1] long actualValue, ...` |
| `EX-HTTP-4004` | HTTP/1.1 Parse Violation (malformed/DoS)     | `[0] String detail`                    |
| `EX-HTTP-4005` | HTTP/2 CONTINUATION Sequence Violation       | `[0] String detail`                    |
| `EX-HTTP-4006` | HTTP/2 Frame Encoding Violation              | `[0] String detail`                    |
| `EX-HTTP-4007` | HTTP Provider Bootstrap Failure              | `rawArgs[0]: String providerName`       |
| `EX-HTTP-4008` | HTTP Server Engine Start Failure             | `rawArgs[0]: String providerName, rawArgs[1]: int port` |
| `EX-HTTP-4009` | HTTP Client Engine Connection Failure        | `rawArgs[0]: String providerName, rawArgs[1]: String host, rawArgs[2]: int port` |
| `EX-HTTP-4010` | HTTP/2 Rapid Reset Flood Defense (CVE-2023-44487) | `rawArgs[0]: int resetCount, rawArgs[1]: int lastProcessedStreamId` |
| `EX-HTTP-4011` | Stream Emit After Close (ADR-043)            | `rawArgs[0]: long eventsEmitted`        |
| `EX-HTTP-4012` | Stream Principal Expired Mid-Stream (ADR-012 §5) | `rawArgs[0]: long streamAgeMillis, rawArgs[1]: long eventsEmitted` |
| `EX-HTTP-4013` | Request Body Decode Failure (caller fault → 400, ADR-036) | `rawArgs[0]: String targetTypeName, rawArgs[1]: long bodySize` |
| `EX-HTTP-4014` | WebSocket Send After Close (ADR-084 §8)      | `rawArgs[0]: long connectionAgeMillis, rawArgs[1]: long messagesSent, rawArgs[2]: int closeCode` |
| `EX-HTTP-4015` | WebSocket Protocol Violation (caller fault, RFC 6455) | `rawArgs[0]: int closeCode`             |

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
| `EX-PERS-5008` | Unsupported Column Type (ADR-080 §2) | `[0] String declaredTypeName, [1] Integer columnIndex, [2] String accessor` |

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
| `EX-EVENT-6005` | Outbox Dead-Letter Queue | `rawArgs[0]: String eventType, rawArgs[1]: String reason, rawArgs[2]: int retryCount` |
| `EX-EVENT-6006` | Projection Handler Threw | `rawArgs[0]: String projectionName, rawArgs[1]: int eventTypeOrdinal` |
| `EX-EVENT-6007` | Event-Loop VT Uncaught Exception | `rawArgs[0]: String loopName, rawArgs[1]: String exceptionType` |
| `EX-EVENT-6008` | Append Version Conflict (ADR-049) | `[0] String streamType, [1] long expectedVersion, [2] long actualVersion` |

### Flow / Saga (`EX-FLOW-`)

| Code           | Description               | Glass-Box Payload                                                               |
|:---------------|:--------------------------|:--------------------------------------------------------------------------------|
| `EX-FLOW-7001` | Provider Engine Failure   | `[0] String providerName, [1] String reason`                                    |
| `EX-FLOW-7002` | Engine Lifecycle Failure  | `[0] String engineName, [1] String phase, [2] String reasonCode, [3] int ctx` — **except `phase="WAKE"`, which carries five slots**: `[3] long instanceIdMost, [4] long instanceIdLeast` (since 0.12; a flow identity is 128 bits and does not fit the `int`). Read this layout by phase, not by arity; index 2 is the reason code on every phase |
| `EX-FLOW-7003` | Step Execution Failure    | `[0] String definitionName, [1] long instanceIdMost, [2] long instanceIdLeast, [3] int stepIndex, [4] String staticReasonCode ("STEP_FAILED" \| "COMPENSATION_FAILED"), [5] String causeType (cause.getClass().getName() or "none")` |
| `EX-FLOW-7004` | Registry Conflict         | `[0] int stepId, [1] String reason`                                             |

### Config (`EX-CFG-`)

| Code          | Description              | Glass-Box Payload                                                             |
|:--------------|:-------------------------|:------------------------------------------------------------------------------|
| `EX-CFG-1001` | Missing Property         | `[0] String missingKey, [1] String providerName`                              |
| `EX-CFG-1002` | Type Mismatch            | `[0] String key, [1] String expectedType, [2] String actualValue` ⚠️ redact  |
| `EX-CFG-1003` | Hot-Reload Read Error    | `[0] String filename, [1] String reason`                                      |
| `EX-CFG-1004` | Immutable Key Reload Refused | `[0] String filename, [1] String key`                                     |

### Blob Storage (`EX-BLOB-`)

| Code            | Description                          | Glass-Box Payload                                                              |
|:----------------|:-------------------------------------|:--------------------------------------------------------------------------------|
| `EX-BLOB-8001` | Object Not Found                     | `[0] String providerName, [1] String container`                                 |
| `EX-BLOB-8002` | No Isolation Key (ADR-056 §5)        | `[0] String providerName, [1] String denyReason`                                |
| `EX-BLOB-8003` | Transfer I/O Failure                 | `[0] String providerName, [1] String container`                                 |
| `EX-BLOB-8004` | Declared/Actual Length Mismatch      | `[0] String providerName, [1] long declaredLength, [2] long actualLength`       |
| `EX-BLOB-8005` | Single-Object Ceiling Exceeded       | `[0] String providerName, [1] long declaredBytes, [2] long ceilingBytes`        |
| `EX-BLOB-8006` | Remote Store Refused                 | `[0] String providerName, [1] String container, [2] int statusCode`             |
| `EX-BLOB-8007` | No Provider on Classpath             | `[0] String component`                                                          |
| `EX-BLOB-8008` | Provider Id Does Not Resolve         | `[0] String configKey, [1] String configuredId, [2] String availableIds`        |
| `EX-BLOB-8009` | Required Configuration Key Unset     | `[0] String configKey, [1] String expected`                                     |

### Job Scheduling (`EX-JOB-`)

`9001` and `9003` are **JFR-only**: a dispatched job runs on its own thread and has no caller to
throw to, so both are recorded on `eu.exeris.kernel.scheduling.JobFailure` rather than carried on an
exception, and neither has a `rawArgs` layout.

| Code           | Description                                | Glass-Box Payload                              |
|:---------------|:-------------------------------------------|:------------------------------------------------|
| `EX-JOB-9001` | Dispatch Refused — No Identity (ADR-057 §5) | *(JFR-only — no rawArgs)*                      |
| `EX-JOB-9002` | Submission to a Closed Scheduler            | `[0] String schedulerName, [1] String jobName` |
| `EX-JOB-9003` | Job Body Threw                              | *(JFR-only — no rawArgs)*                      |
| `EX-JOB-9004` | No Provider on Classpath                    | `[0] String component`                         |

### Diagnostics audit (`EX-DIAG-`, ADR-033)

**Not exceptions.** Each out-of-process `KernelDiagnostics` call emits one INFO-level JFR event so
operators can audit who introspected the kernel; the codes exist to name those events in the same
namespace as failures. Cold path, so emission allocation is acceptable (ADR-033 Obligation 2).

| Code            | Description                    | Glass-Box Payload |
|:----------------|:-------------------------------|:--------------------|
| `EX-DIAG-1001` | `listProviders()` invoked      | *(no rawArgs)*     |
| `EX-DIAG-1003` | `getBootstrapDag()` invoked    | *(no rawArgs)*     |
| `EX-DIAG-1004` | `describeSubsystem()` invoked  | *(no rawArgs)*     |
| `EX-DIAG-1005` | `getJvmErgonomics()` invoked   | *(no rawArgs)*     |

`EX-DIAG-1002` is a **reserved gap**, not an omission: the `listCapabilities()` method it audited was
removed pre-1.0 and the number was left rather than renumbered, so `1003..1005` stay stable.

### Unclassified (`EX-UNK-`)

| Code           | Description                                | Glass-Box Payload |
|:---------------|:-------------------------------------------|:--------------------|
| `EX-UNK-0000` | Telemetry record carried no code of its own | *(no rawArgs)*     |

---

## Fault origin — whose fault it was (ADR-083)

An error code says *what* failed. `faultOrigin()` says **who has to change something for the
operation to succeed**, which is the question a protocol adapter answers before it picks a status:

| Constant | Meaning |
|---|---|
| `FaultOrigin.CALLER` | the request, its arguments or its credentials are at fault; repeating it unchanged fails identically |
| `FaultOrigin.SYSTEM` | the runtime, its configuration or its dependencies are at fault; the caller can do nothing about it |

**`SYSTEM` is the default and an unclassified subclass keeps it.** That is what the runtime did
before the method existed, so classifying more subclasses later adds information rather than
changing behaviour. The asymmetry is deliberate: reporting a caller's mistake as a server error is a
worse message, while reporting a broken deployment as the caller's mistake hides an outage behind a
`4xx` nobody pages on.

**Classify at a catch site with `FaultOrigin.classify(throwable)`, not with `instanceof`.** A handler
catches throwables, not kernel exceptions, and anything that is not one carries no origin — guessing
one from a JDK type is how a bare `NoSuchElementException` from an unbound kernel binding came to be
answered as a bad request.

```java
} catch (RuntimeException failure) {
    respond(FaultOrigin.classify(failure) == FaultOrigin.CALLER
            ? HttpStatus.BAD_REQUEST
            : HttpStatus.INTERNAL_SERVER_ERROR);
}
```

The origin is **not** a status code. HTTP reads `CALLER` as `4xx` but picks between `400`, `401`,
`403` and `409` from the exception itself; a non-HTTP binding maps it elsewhere. Keeping status out
of the SPI is the same constraint that put status mapping on the handler in ADR-036.

Four subclasses declare `CALLER` today — `RequestBodyDecodeException`,
`SecurityAuthenticationException`, `InsufficientPrivilegesException` and
`EventStreamAppendConflictException`. **A new subclass should state its origin when the answer is
clear from its own contract, and leave the default when it is not**; a wrong `CALLER` is worse than
an unclassified `SYSTEM`.

## Code Examples

### 1. The Glass-Box Ready Exception (SPI)

// NOTE: Illustrative pseudocode — VirtualThreadPinningException is NOT present in exeris-kernel-spi. For a real SPI example, see MemoryExhaustedException.

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
package eu.exeris.kernel.core.telemetry;

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

> **Note:** `EX-HTTP-*` and `EX-GRPH-*` codes map to `INTERNAL_ERROR` by policy — these represent internal infrastructure failures not surfaced as distinct protocol-level codes.

---

## Disclosure rendering

Operator-visible artifacts (log lines, HTTP error bodies, console output) MUST be shaped by `eu.exeris.kernel.spi.exceptions.ExceptionDisclosure` so that no operational primitive leaks unless the active profile permits it. The helper is intentionally a static utility: redaction is a rendering concern at the sink boundary, never a hot-path decision.

| Helper | Contract |
|:--|:--|
| `discloseMessage(ex, profile)` | DEV → original `getMessage()`. PROD/TEST → `"<errorCode> [traceId=<uuid>]"` envelope (correlation preserved, primitives redacted). |
| `discloseRawArgs(ex, profile)` | DEV → original `rawArgs()` array reference (Glass-Box binary contract unchanged). PROD/TEST → `EMPTY_ARGS` sentinel. |
| `discloseStackTrace(profile)` | Tracks `KernelProfile.enablesFullErrorDisclosure()`. SLF4J-style sinks consult this to decide whether to forward the throwable to the underlying logger. |
| `activeProfile()` | Reads `KernelProviders.CURRENT_CONFIG.kernelSettings().profile()`. Falls back to `KernelProfile.PROD` when the slot is unbound (early bootstrap, unit tests outside a kernel scope) — PROD is the safe default. |

**Profile mapping** (from `KernelProfile`):

| Profile | `enablesFullErrorDisclosure` | Operator visible artifact |
|:--|:--|:--|
| `DEV` | `true` | message + rawArgs + stack trace |
| `TEST` | `false` | opaque envelope, redacted rawArgs, no stack trace |
| `PROD` | `false` | opaque envelope, redacted rawArgs, no stack trace |

Community bindings:

- `Slf4jTelemetrySink` resolves the active profile per emit and shapes the JSON line + throwable forwarding accordingly. The package-private 2-arg test constructor pins DEV so that existing serialization fixtures remain meaningful; production callers use the no-arg constructor which delegates to `ExceptionDisclosure::activeProfile`.
- `ConsoleSink` and `FileSink` are diagnostic-only paths; their disclosure adoption is tracked as Sprint 7 follow-up if a production deployment requires their output to be redacted.

TCK obligations:

- `AbstractDisclosureModeTck` (in `exeris-kernel-tck`) verifies the SPI helper across DEV/TEST/PROD and the unbound-scope fallback. Every binding that re-exports the helper must extend the abstract.
- `Slf4jTelemetrySinkDisclosureTest` (in `exeris-kernel-community`) pins the sink-level integration: PROD/TEST suppress the throwable and replace `rawArgs` with `[]`; DEV forwards both.

---

## Testing Strategy

### Unit Tests

- Exception construction verifies `traceId` generation.
- `KernelErrorCodes` constant assignment matches the declared `EX-` string value.
- Error mapper registry correctly maps all domain exceptions to `TransportErrorCode` enums.

### Integration Tests

- Full exception flow: throw → `ErrorMapperRegistry.map()` → respond via active Transport Driver. **(Target-state / not yet implemented)**
- Environment-aware disclosure: DEV exposes stack trace, PROD returns opaque error code only. Pinned by `AbstractDisclosureModeTck` (helper contract) and `Slf4jTelemetrySinkDisclosureTest` (sink boundary).
- Binary Glass-Box round-trip: `rawArgs[]` serialized → deserialized → fields match source primitives. **(Target-state / not yet implemented)**

---

## Summary

The Exceptions subsystem provides type-safe, traced, and environment-aware error handling. By enforcing the `EX-` code
standard, the `rawArgs` binary contract, and CWE-532 privacy rules, it ensures that even under 1M RPS load the Kernel
leaves a perfect, zero-allocation forensic trail — without coupling itself to any specific network protocol or database
driver.

---

## Stability

This subsystem's SPI surface (`eu.exeris.kernel.spi.exceptions.*`) is classified **stable** in the
[SPI Stability Matrix](../stability-matrix.md). See the matrix for the semver policy and TCK
coverage status.
