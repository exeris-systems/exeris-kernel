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

* **[-] Maintenance Overhead:** We take on responsibility for the DB/Redis lifecycle (or use K8s operators),
  rather than relying on fully managed cloud wrappers.
* **[-] Performance Tuning:** Requires internal expertise to tune K8s/Postgres on bare metal, whereas Cloud PaaS
  handles this automatically.

## Engineering Protocol

Once this decision is ACCEPTED, it must be committed to the repository to maintain the Single Source of Truth.