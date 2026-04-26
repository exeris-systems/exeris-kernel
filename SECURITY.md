# Security Policy

## Supported Versions

Exeris Kernel is currently an architectural/prototype-stage project. Security fixes are applied to the active default branch unless a supported release line is explicitly announced.

| Version / Branch | Security Support |
| ---------------- | ---------------- |
| default branch | ✅ Supported |
| latest tagged prototype release | Best effort |
| stale branches, forks, or experimental branches | ❌ Not supported |

Prototype, research, and pre-release artifacts may change rapidly and should not be assumed to receive long-term security backports.

---

## Security Model

Exeris Kernel follows a **fail-closed resource-server security model**.

Security-sensitive behavior is governed by the repository architecture, subsystem contracts, ADRs, and TCK suites, especially:

- `docs/subsystems/security.md`
- `docs/subsystems/crypto.md`
- `docs/subsystems/transport.md`
- `docs/subsystems/persistence.md`
- `docs/adr/ADR-012 Security Trust Model Upgrade for Resource-Server Validation and Fail-Closed Runtime.md`
- `docs/modules/01-spi.md`
- `docs/modules/02-core.md`
- `docs/modules/03-community.md`
- `docs/modules/05-tck.md`
- `docs/performance-contract.md`

Core principles:

- SPI must remain implementation-blind.
- Core must remain driver-agnostic and orchestrate only through SPI contracts.
- Authentication and authorization must fail closed on uncertainty.
- JWT/JWS/JWKS/OIDC validation must use deterministic trust boundaries.
- `PrincipalContext` and `StorageContext` must be immutable and propagated with `ScopedValue`, not `ThreadLocal`.
- Tenant isolation must be derived from verified security claims, not trusted from upper application layers.
- Native memory, TLS state, and off-heap resources must have explicit ownership and deterministic lifecycle.
- Security telemetry must never expose raw tokens, key bytes, credentials, private keys, sensitive claims, or tenant data.
- Security-relevant runtime paths must preserve No Waste Compute, admission control, backpressure, and fail-fast bootstrap behavior.

No fail-open fallback is permitted for resource-server authorization decisions.

---

## Repository-Specific Security Scope

This policy applies to security-relevant behavior across the public Exeris Kernel repository, including:

- `exeris-kernel-spi`: security-sensitive contracts, immutable carriers, error taxonomy, and boundary integrity.
- `exeris-kernel-core`: bootstrap orchestration, provider discovery, `ScopedValue` context binding, HTTP codec/runtime code currently embedded in Core, TLS/core crypto orchestration, telemetry, and admission gates.
- `exeris-kernel-community`: Community provider behavior, JWT/JWKS validation, TLS integration, persistence routing, and contract-conforming implementations.
- `exeris-kernel-tck`: abstract tests that codify observable SPI/security behavior.
- Enterprise-related behavior when a public SPI/Core contract is affected, even if the Enterprise implementation is distributed outside this repository.

A vulnerability may be in scope even when it appears as a performance issue if it allows an unauthenticated or low-cost input to violate No Waste Compute, bypass admission control, consume unbounded native memory, pin carrier threads, or force avoidable heap allocation on runtime hot paths.

---

## What to Report

Please report vulnerabilities involving:

- authentication bypass;
- authorization bypass;
- fail-open behavior during JWT/JWKS/OIDC uncertainty;
- invalid issuer, audience, expiry, signature, or `kid` handling;
- JWKS cache poisoning, stale-key acceptance, or unsafe key rotation;
- cross-tenant data access or missing `StorageContext` enforcement;
- leaks of raw tokens, credentials, private keys, key material, authorization headers, or sensitive claims;
- `ThreadLocal`-based runtime security context propagation;
- mutable or replaceable `PrincipalContext` / `StorageContext`;
- SPI/Core boundary violations that expose provider, native, framework, or driver internals;
- unsafe native memory ownership in Crypto/TLS paths;
- TLS/cipher hot-path changes that introduce heap copies, untracked arenas, or secret-retention risks;
- password hashing weaknesses, non-constant-time verification, or missing secret zeroing;
- missing TCK coverage for observable security contract changes.

---

## Additional Vulnerability Classes

### SPI / Core Boundary Integrity

Please report issues involving:

- SPI contracts exposing provider, native, framework, OpenSSL, JDBC, HikariCP, file descriptor, or transport-driver details.
- Core importing or depending on Community/Enterprise implementation classes.
- `ServiceLoader` provider selection, validation, or bootstrap behavior that permits a malicious, missing, duplicate, or contract-violating provider to enter serving state.
- Mutable SPI carriers or identity-sensitive carrier behavior that can corrupt authorization or tenant isolation decisions.
- Error taxonomy changes that make security failures ambiguous or silently misclassified.
- Public contracts that permit provider-specific behavior to leak across The Wall.

### HTTP and Protocol Parsing

Please report issues involving:

- HTTP request smuggling, header normalization ambiguity, CRLF/header injection, or incorrect handling of `Authorization`.
- HTTP/2 pseudo-header validation bugs, stream state confusion, continuation-frame abuse, or malformed frame handling that bypasses security gates.
- HPACK/Huffman parser bugs, compression bombs, or malformed header blocks that cause unbounded CPU, heap allocation, native allocation, or parser state corruption.
- Incorrect protocol state transitions that allow unauthenticated input to reach business logic, persistence, or expensive runtime paths before fail-closed admission.
- Header parsing behavior that leaks credentials, normalizes security-sensitive values incorrectly, or accepts ambiguous authority/host identity.

### Resource-Server Validation

Please report issues involving:

- JWT algorithm confusion, downgrade, `none` acceptance, HS/RS confusion, or acceptance of unsupported token forms.
- Missing, ambiguous, duplicated, stale, or untrusted `kid` / JWKS material.
- Use of `jku`, `x5u`, embedded JWKs, or remote key material unless explicitly allowed by the provider contract.
- Unbounded clock skew, unsafe handling of `nbf`/`iat`/`exp`, or issuer/audience confusion.
- Blocking JWKS/OIDC fetches on the request critical path when cache/prefetch semantics are required.
- JWKS/OIDC outage, cache corruption, or rotation uncertainty causing fail-open behavior.
- Trust-anchor, issuer, audience, algorithm, or claim policy ambiguity that is not denied deterministically.
- Revocation, expiry, or key-rotation handling that accepts an indeterminate authorization state.

### Storage Isolation

Please report issues involving:

- Incorrect handling of `KernelIsolationClaims` for `SHARED`, `SEPARATED_SCHEMA`, or `DEDICATED` storage routing.
- Schema name or datasource key injection.
- Cross-tenant routing caused by trusting application-layer storage hints instead of verified security claims.
- Use of SHARED-only fallback paths for separated-schema or dedicated routing when the provider contract requires a stronger isolation mode.
- Unknown dedicated datasource keys, missing isolation sub-claims, or invalid tenant claims that do not fail closed according to the storage isolation contract.
- Persistence access without an established `StorageContext`.
- Row-Level Security bypasses, tenant identifier confusion, or query paths that can expose cross-tenant data.

### Native Memory, TLS, and Secret Lifecycle

Please report issues involving:

- `LoanedBuffer` / `MemorySegment` use-after-free, double-close, ref-count corruption, or ownership ambiguity.
- Native memory allocation that bypasses `MemoryAllocator`, `WatermarkManager`, or approved ownership wrappers.
- Direct `Arena` management in Crypto/TLS runtime paths where subsystem contracts require allocator-owned memory.
- TLS session state reuse, ALPN confusion, or `SSL*`/`BIO*`/`SSL_CTX*` pointer leakage across sessions, tenants, SPI boundaries, logs, or JFR events.
- Secret material retained in heap/native buffers after use.
- Secret material emitted through logs, exceptions, JFR, telemetry payloads, crash dumps, or diagnostic output.
- Cipher/TLS hot-path behavior that creates heap copies, unbounded allocations, or unsafe native pointer exposure.
- Native errors that are swallowed, misclassified, or converted into fail-open behavior.

### Native Library Loading and FFM Boundary

Please report issues involving:

- loading OpenSSL or other native libraries from attacker-controlled paths;
- unsafe fallback to unexpected library versions;
- symbol resolution ambiguity that changes security behavior;
- native function signatures that allow memory corruption;
- FFM downcalls that do not preserve ownership, bounds, lifecycle, or error semantics;
- native pointer disclosure where it weakens isolation or exploit resistance;
- unsafe conversion between native memory and heap arrays;
- native provider initialization that succeeds when required crypto/TLS prerequisites are unavailable.

### Runtime Context and Concurrency

Please report issues involving:

- runtime security context propagation through `ThreadLocal` instead of `ScopedValue`;
- principal or storage context leakage across virtual threads, structured scopes, parked sagas, or request boundaries;
- unstructured concurrency, raw executors, or blocking calls that bypass expected structured orchestration in security-sensitive runtime paths;
- carrier-thread pinning reachable from unauthenticated or low-privilege input;
- unbounded virtual-thread creation reachable from unauthenticated input;
- request context reuse, mutation, or inheritance across unrelated requests;
- blocking remote trust decisions on admission or authorization hot paths.

### Configuration and Secret Management

Please report issues involving:

- insecure defaults for issuer, audience, JWKS, TLS, persistence, or isolation settings;
- accepting empty, wildcard, or overly broad issuer/audience policies;
- unsafe fallback to anonymous, system, default tenant, default schema, or default datasource;
- secrets loaded from untrusted paths or environment variables without validation;
- dynamic secret rotation causing stale, mixed, or fail-open runtime state;
- bootstrap readiness incorrectly succeeding when required secrets, trust anchors, or validation dependencies are unavailable;
- configuration hot-reload that partially applies security-sensitive state;
- production credentials, private keys, tokens, or tenant data appearing in generated config, logs, diagnostics, or examples.

### Supply Chain and Build Integrity

Please report issues involving:

- dependency confusion or unsafe repository resolution;
- malicious, compromised, or over-privileged build plugins;
- unsafe generated sources affecting security-sensitive contracts;
- annotation processors that alter authorization, isolation, or telemetry behavior unexpectedly;
- release artifacts that cannot be traced back to source;
- unexpected native library loading paths;
- unsafe OpenSSL/library lookup behavior;
- build scripts that execute untrusted code during normal verification;
- CI workflows that expose secrets to forks, pull requests, logs, caches, or artifacts;
- Maven, GitHub Actions, or release automation behavior that allows tampering with security-relevant artifacts.

---

## Denial-of-Service and No Waste Compute

Exeris treats some resource-exhaustion bugs as security issues.

Please report cases where remote or low-privilege input can:

- force heap allocation on a production runtime hot path;
- trigger unbounded native memory allocation;
- bypass watermarks, admission gates, PAQS/backpressure, or fail-fast bootstrap checks;
- cause repeated remote JWKS/OIDC fetches on the request critical path;
- pin carrier threads or introduce blocking I/O in security-sensitive orchestration;
- create unbounded parser work through HTTP/2, HPACK, TLS, token, or header inputs;
- cause fail-open behavior during overload, dependency outage, key rotation, or cache corruption;
- reach expensive runtime paths before authentication, authorization, or admission checks complete;
- cause native resources, TLS sessions, buffers, or structured scopes to leak.

Pure benchmark regressions are normally out of scope unless they create a practical availability, isolation, admission-control, or fail-closed risk.

---

## Credential and Password Handling

Please report issues involving:

- plaintext password storage;
- weak password hashing algorithms;
- incorrect Argon2id parameter handling;
- non-constant-time password or token comparisons;
- password hashes outside the expected PHC-style format where applicable;
- password, token, or secret material retained in memory longer than necessary;
- missing secret zeroing where subsystem contracts require it;
- credential material exposed in telemetry, logs, exceptions, tests, fixtures, or documentation examples.

---

## Telemetry and Diagnostic Privacy

Security telemetry must be useful without exposing secrets.

Do not include raw tokens, private keys, passwords, authorization headers, key bytes, production credentials, or sensitive tenant data in reports.

Security-sensitive runtime diagnostics, including JFR events, exception payloads, error `rawArgs`, logs, crash dumps, and bootstrap failure details, must not expose:

- raw JWTs or opaque tokens;
- signing keys, TLS private keys, password material, or derived key bytes;
- sensitive claims beyond what is required for safe operational diagnosis;
- native pointer values when their disclosure would weaken isolation or exploit resistance;
- tenant data, schema names, datasource keys, or identifiers beyond what is safe and necessary for diagnosis.

Reports about telemetry leaks should include the event name, error code, log path, exception type, or diagnostic artifact where the leak occurs, with all secrets redacted.

---

## Third-Party Dependency Vulnerabilities

Reports about third-party CVEs are in scope when the vulnerable code path is reachable through Exeris Kernel or affects Exeris security guarantees.

Please include:

- dependency name and version;
- CVE or advisory identifier;
- reachable Exeris code path;
- exploitability conditions;
- whether default configuration is affected;
- whether the issue impacts authentication, authorization, tenant isolation, TLS, native memory, telemetry, build integrity, or fail-closed behavior.

Dependency CVEs without a reachable Exeris impact may be treated as maintenance issues rather than security vulnerabilities.

---

## Out of Scope

The following are generally out of scope unless they bypass an Exeris security contract:

- vulnerabilities only affecting unsupported branches, stale forks, or modified downstream distributions;
- social engineering against maintainers or users;
- attacks requiring physical access to the host;
- denial-of-service based solely on unrealistic local resource limits;
- missing per-principal rate limiting, unless it bypasses PAQS/admission/backpressure guarantees;
- lack of mTLS in Community deployments where the documented deployment model requires a terminating proxy;
- issues in third-party infrastructure, identity providers, gateways, databases, proxies, or operating systems unless Exeris incorrectly trusts or consumes their output;
- pure benchmark regressions without a practical security, availability, isolation, or admission-control impact;
- vulnerability claims based only on hypothetical misconfiguration without a reachable Exeris behavior issue;
- scanner-only dependency reports without evidence of reachability or impact.

---

## Severity Guidance

The following issues are generally treated as high or critical severity:

- authentication or authorization bypass;
- cross-tenant data exposure;
- fail-open behavior in JWT/JWKS/OIDC validation;
- arbitrary trust-anchor or key-material acceptance;
- unsafe issuer, audience, signature, expiry, or `kid` validation;
- remote unauthenticated native memory exhaustion;
- use-after-free, double-free, or ref-count corruption in security-sensitive native memory paths;
- leaks of private keys, credentials, raw tokens, authorization headers, or sensitive tenant data;
- unauthenticated protocol input reaching business logic or persistence before admission checks;
- unsafe native library loading from attacker-controlled paths;
- bootstrap readiness succeeding when required security dependencies are unavailable.

The following are typically medium severity unless they enable a stronger impact:

- secret-safe telemetry violations involving partial metadata;
- denial-of-service requiring authentication or unusual configuration;
- non-hot-path allocation regressions with bounded impact;
- missing TCK coverage for a security-relevant behavior that is still correctly implemented;
- configuration validation issues that require privileged deployment control;
- dependency vulnerabilities with limited reachable impact.

The following are normally low severity or maintenance issues unless a concrete exploit path is demonstrated:

- purely theoretical concerns;
- style or code-quality issues without security impact;
- scanner findings without reachability analysis;
- benchmark-only regressions;
- documentation ambiguity that does not affect implementation behavior.

Severity may be adjusted based on exploitability, default exposure, authentication requirements, tenant impact, secret exposure, native memory safety, and whether the issue violates fail-closed behavior.

---

## Security Fix Requirements

Security-relevant fixes should include, where applicable:

- regression tests for the affected module;
- abstract TCK coverage when observable SPI behavior changes;
- Community binding validation for public provider behavior;
- updated documentation when subsystem or ADR contracts change;
- secret-safe telemetry validation when error payloads, JFR events, logs, diagnostics, or exceptions are modified;
- fail-closed tests for uncertain, degraded, malformed, stale, missing, or ambiguous trust states;
- memory ownership tests for native/off-heap resource lifecycle bugs;
- parser tests for malformed protocol inputs;
- bootstrap/readiness tests for required security dependencies.

A fix that changes security semantics without tests or documentation may be considered incomplete.

---

## Vulnerability Report Template

Please include:

- affected commit, branch, tag, release, or artifact;
- affected module or subsystem;
- vulnerability class;
- reproduction steps or proof of concept;
- expected fail-closed behavior;
- actual behavior;
- authentication level required;
- whether default configuration is affected;
- whether tenant isolation, secrets, native memory, telemetry, protocol parsing, supply chain, or trust boundaries are affected;
- logs, JFR event names, error codes, stack traces, or crash details with secrets redacted;
- dependency/CVE information, if relevant;
- suggested fix or mitigation, if known.

Do not include raw tokens, private keys, passwords, production credentials, authorization headers, sensitive tenant data, or unredacted customer information.

---

## Reporting a Vulnerability

Please report suspected vulnerabilities privately using GitHub private vulnerability reporting for this repository.

Do not open a public issue for suspected vulnerabilities.

When reporting, include the minimum information necessary to reproduce and understand the issue. If accidental access to secrets, tenant data, or sensitive runtime material occurs, stop testing immediately and report with only the minimum necessary detail.

---

## Disclosure Process and Safe Harbor

Maintainers aim to:

1. acknowledge receipt within 72 hours;
2. provide an initial triage result or follow-up questions within 7 days;
3. validate the issue against the Exeris security model, subsystem contracts, and TCK obligations;
4. assess affected branches, tags, artifacts, and downstream contract impact;
5. prepare a fix, test coverage, and documentation update where required;
6. coordinate disclosure after a mitigation is available.

Good-faith research that avoids data destruction, persistence, lateral movement, public disclosure before coordination, and access to third-party secrets is welcomed.

This safe harbor statement is not legal advice and does not authorize testing against third-party systems, production tenants, infrastructure not owned by the reporter, or systems where the reporter does not have permission to test.

---

## Coordinated Disclosure

Reporters are asked to avoid public disclosure until maintainers have had a reasonable opportunity to investigate and release a mitigation.

If disclosure timelines are required by the reporter or their organization, please state them in the initial report so maintainers can coordinate expectations early.

If a suspected vulnerability is accidentally reported in a public issue, maintainers may temporarily limit discussion, remove sensitive details where possible, and move coordination to the private reporting channel.

Please do not continue exploit discussion publicly once a maintainer identifies the issue as security-sensitive.

---

## Deployment Responsibilities

Some controls are expected to be provided by deployment architecture rather than the Kernel itself.

The following are generally deployment responsibilities unless they bypass an Exeris security contract:

- per-principal rate limiting;
- edge WAF policy;
- API gateway authentication policy;
- mTLS termination for Community deployments;
- external identity-provider hardening;
- database, proxy, operating system, or cloud-provider hardening;
- production secret distribution outside the repository.

For mTLS or per-principal rate limiting, deploy a trusted proxy or API gateway such as Envoy or Nginx in front of the Exeris transport layer.

Deployment responsibilities do not excuse Exeris from safely validating, consuming, rejecting, or failing closed on inputs received from those systems.

---

## Final Security Invariant

Security fixes and feature changes must preserve:

- fail-closed behavior;
- The Wall between SPI, Core, Community, and Enterprise implementations;
- immutable identity and storage context propagation;
- tenant isolation;
- deterministic native/off-heap ownership;
- secret-safe telemetry;
- No Waste Compute;
- observable behavior encoded in tests and TCK suites.

If implementation behavior differs from the documented security contract, update the implementation plus tests, or amend the relevant documentation/ADR before merge.
