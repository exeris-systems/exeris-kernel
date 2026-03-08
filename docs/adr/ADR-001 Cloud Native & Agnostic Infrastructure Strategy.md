# ADR-001: Cloud Native & Agnostic Infrastructure Strategy

| Atrybut        | Wartość                                                       |
|:---------------|:--------------------------------------------------------------|
| **Status**     | **ACCEPTED**                                                  |
| **Deciders**   | Arkadiusz Przychocki                                          |
| **Date**       | 2025-10-10                                                    |
| **Driven By**  | RFC-2025-10-10: Cloud Agnostic Infrastructure Strategy        |
| **Compliance** | [Strategic Pillar: Clean IP & Detachment](../architecture.md) |

## Context and Problem Statement

Building Exeris on top of closed, proprietary Cloud PaaS solutions (e.g., AWS Lambda, DynamoDB, Azure Functions)
creates **"Vendor Lock-in"**. This forces a hard dependency on a specific cloud provider's API. As a result, our
**"Code Detachment"** business model becomes impossible to execute, because clients cannot "take the code" and run
it on their own infrastructure without carrying along the entire cloud service stack.

We require an infrastructure strategy that guarantees **100% portability** across AWS, Azure, GCP, and Bare Metal
(On-Premise) servers.

## 🏁 The Decision

We adopt the **CNCF (Cloud Native Computing Foundation)** landscape as our strict baseline. We reject proprietary
PaaS in favor of open, portable standards defined as Code (IaC).

**Selected Components:**

* **Compute:** Kubernetes (K8s) is the universal runtime environment.
* **State:** PostgreSQL & Redis (Dockerized/Helm) replace dependencies on RDS/Aurora/ElastiCache.
* **Storage:** S3-compatible API (MinIO for on-prem/local, generic S3 for cloud).

## Consequences

### ✅ Positive Outcomes

* **[+] 100% Portability:** The platform can run on any major cloud or bare metal servers.
* **[+] Code Detachment:** Clients can legally and technically inherit the infrastructure definitions.
* **[+] Unified Developer Experience:** `docker compose up` is identical to production (via Minikube).

### ⚠️ Trade-offs

* **[-] Operational Debt (K8s Operators):** Replacing managed PaaS services (RDS, ElastiCache) with Kubernetes
  operators (e.g., CloudNativePG, Redis Operator) transfers the operational lifecycle responsibility entirely to the
  platform team. This includes backup orchestration, failover testing, certificate rotation, and storage class tuning.
  This is a deliberate and accepted debt, not an oversight. The cost is bounded and predictable; vendor lock-in is not.

* **[-] Performance Tuning:** Requires internal expertise to tune K8s/Postgres on bare metal, whereas Cloud PaaS
  handles this automatically.

* **[-] Portability is IaC-Level, Not Zero-Cost:** The "100% Portability" claim is bounded to the level of IaC
  definitions (Helm charts, Kubernetes manifests). Each target cloud environment carries provider-specific network
  overhead (VPC routing latency, CNI plugin performance, cross-AZ bandwidth costs) that may require dedicated tuning
  of the Transport Layer (L2) — specifically the PAQS backpressure thresholds and `io_uring` ring sizing parameters.
  Portability does not imply identical performance across all environments.

### 📋 Incremental Expertise Clause

This decision is accepted with the explicit understanding that operational expertise in Kubernetes database operators
(CloudNativePG, Redis Operator) will be acquired incrementally. This knowledge gap does **not** constitute a blocker
for the **TRL-3 (Validated Architectural Prototype)** milestone. The initial prototype phase will operate on
single-node Dockerized instances (`docker compose`) with Helm-based K8s graduation deferred to the TRL-5 milestone.

### 🗄️ Redis: Role & Scope

Redis is listed in the infrastructure stack, but its role relative to the Exeris Kernel must be precisely defined:

| Question                                        | Answer                                                                                                                                                                                                                                                                                                        |
|:------------------------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Is Redis an internal Kernel mechanism?**      | **No.** The Exeris Kernel (`exeris-kernel-*` modules) has zero runtime dependency on Redis. The kernel is a single-process runtime that manages its own off-heap memory and state via `MemoryAllocator` and `LoanedBuffer`. It does not use Redis for internal coordination.                                  |
| **Is Saga/Flow coordination backed by Redis?**  | **No.** Flow Engine (L4) Saga state is persisted to the primary datastore (PostgreSQL) via the `CitadelRepository` SPI. Redis is not used for distributed Saga coordination at TRL-3/TRL-4.                                                                                                                   |
| **What is Redis used for?**                     | Redis is a **recommended application-tier cache** for business logic built on top of the Kernel — e.g., session caches, rate-limit counters, and distributed locks for application-level workflows. It is an operational recommendation, not a kernel dependency.                                             |
| **Will Redis ever become a Kernel dependency?** | Only if a future ADR explicitly introduces a `DistributedStateProvider` SPI with a Redis-backed implementation in `exeris-kernel-community`. No such ADR exists at this time. Any such change must pass The Wall audit (Redis client imports must not appear in `exeris-kernel-spi` or `exeris-kernel-core`). |

## Engineering Protocol

Once this decision is ACCEPTED, it must be committed to the repository to maintain the Single Source of Truth.