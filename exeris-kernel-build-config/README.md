# Exeris Kernel Build Config

**Module:** `eu.exeris:exeris-kernel-build-config`  
**Role:** Code Quality & Architectural Guardrails

## Overview
This module contains the shared configuration for static analysis tools. It enforces the **Glass Box** principle: code must be readable, auditable, and free of legacy Java anti-patterns.

## 🛡️ Enforced Guardrails
- **Checkstyle:** Bans `ThreadLocal` (in favor of `ScopedValue`), `java.util.Date`, and legacy thread pools (`ExecutorService`).
- **PMD 7.21+:** Detects high cyclomatic complexity and violations of the "No Waste Compute" mantra.
- **License Headers:** Ensures all Java source files in this module carry the Proprietary Exeris Software License.