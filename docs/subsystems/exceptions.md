# Kernel Subsystem: Exceptions (L0 Foundation)

**Physical Layout:**

- SPI: `eu.exeris.kernel.spi.exceptions.*` (Domain exceptions, Error codes, BlackBox support)
- Core: `eu.exeris.kernel.core.exceptions.*` (Error mappers, Registry)
  **Layer:** L0 (Foundation)  
  **Status:** Validated Architectural Prototype (TRL-3)

---

## Overview

The **Exceptions subsystem** provides the unified exception hierarchy and error mapping framework for the Exeris Kernel.
It goes beyond traditional try-catch mechanisms by introducing **Zero-Allocation Telemetry** capabilities. It
implements:

- **Standardized Error Codes:** All exceptions enforce the `EX-[DOMAIN]-[ID]` format (e.g., `EX-SYS-1001`).
- **Black Box Telemetry Support:** Base exceptions capture raw arguments (`Object[] rawArgs`) instead of concatenated
  Strings, enabling the Enterprise tier to dump binary crash logs in nanoseconds without triggering the Garbage
  Collector.
- **Centralized Error Mapper Registry:** Translates internal Java exceptions to **Abstract Transport Codes**, which
  drivers
  later translate into protocol-specific codes (e.g., HTTP 503 or HTTP/3 `H3_EXCESSIVE_LOAD`).
- **Environment-Aware Disclosure:** (PROD shows minimal info, DEV shows full stack).
- **Distributed Tracing:** Every exception automatically generates a UUID `traceId`.

### Core Philosophy

Every exception is a **first-class citizen** in the architecture:

- **Zero-Allocation Ready:** Never concatenate Strings on the hot path (e.g., `throw new Exception("Error " + id)`).
  Pass raw arguments to the `ExerisKernelException` constructor.
- **Protocol Blindness:** Core exceptions know nothing about HTTP/1.1, HTTP/2, or HTTP/3. They map to generic states (
  like `EXCESSIVE_LOAD`).
- **Safe:** PROD mode reveals nothing to the network; DEV mode reveals everything.

---

## Responsibilities

**What Exceptions SPI DOES:**

1. Define the `ExerisKernelException` base class and specific domain exceptions.
2. Enforce the presence of an `EX-` error code and `traceId`.
3. Provide the structure for raw argument capture (Black Box readiness).

**What Exceptions Core DOES:**

1. Maintain the `ErrorMapperRegistry` mapping Java exceptions to generic abstract transport codes.
2. Map external library exceptions (e.g., `SQLException`, `TimeoutException`) to standard Exeris domain exceptions.

---

## Standardized Error Codes (The `EX-` Prefix)

Exeris Kernel strictly uses structured codes for log scraping and Black Box translation:

| Code Domain | Example       | Meaning                                                   |
|:------------|:--------------|:----------------------------------------------------------|
| `EX-SYS-*`  | `EX-SYS-1001` | System/Memory errors (e.g., Off-Heap Leak).               |
| `EX-SEC-*`  | `EX-SEC-2001` | Security/Context errors (e.g., Missing PrincipalContext). |
| `EX-RUN-*`  | `EX-RUN-3002` | Runtime/Scheduler errors (e.g., Virtual Thread Pinning).  |
| `EX-NET-*`  | `EX-NET-4001` | Transport errors (e.g., Protocol Handshake Failure).      |

---

## Code Examples

### 1. The Black-Box Ready Exception (SPI)

```java
package eu.exeris.kernel.spi.exceptions;

public class VirtualThreadPinningException extends ExerisKernelException {

    public VirtualThreadPinningException(long blockTimeMs, String carrierName) {
        // We pass raw arguments (blockTimeMs, carrierName) instead of building a String.
        // The Enterprise BlackBox will serialize this as a binary struct.
        super("EX-RUN-3002", "Virtual Thread pinned the Carrier", blockTimeMs, carrierName);
    }
}
```

### 2. Error Mapper Registry (Core)

```java
package eu.exeris.kernel.core.exceptions;

import eu.exeris.kernel.spi.transport.TransportErrorCode;

public class ErrorMapperRegistry {

    public TransportErrorCode mapToTransportCode(Throwable ex) {
        if (ex instanceof MemoryExhaustedException) {
            // Driver will translate this to HTTP 503 (Community) or H3_EXCESSIVE_LOAD (Enterprise)
            return TransportErrorCode.EXCESSIVE_LOAD;
        }
        if (ex instanceof PrincipalContextMissingException) {
            // Driver will translate this to HTTP 401 or drop stream silently
            return TransportErrorCode.UNAUTHORIZED;
        }
        return TransportErrorCode.INTERNAL_ERROR;
    }
}
```

## Testing Strategy

### Unit Tests

Exception creation verifies traceId generation.

Correct assignment of EX- error codes.

Error mapper registry correctly maps domain exceptions to abstract TransportErrorCode enums.

### Integration Tests

Full exception flow (throw → map → respond via active Transport Driver).

Environment-aware disclosure logic (DEV vs PROD stack trace exposure).

## Summary

The Exceptions subsystem provides type-safe, traced, and environment-aware error handling. By adopting the EX- standard
and the "Black Box" raw arguments pattern, it ensures that even when the Kernel crashes under the load of 1 million RPS,
it leaves a perfect, zero-allocation forensic trail—without ever coupling itself to a specific network protocol.