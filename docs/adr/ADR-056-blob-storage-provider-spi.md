# ADR-056: Adopt a `BlobStorageProvider` SPI for binary-object storage

| Attribute       | Value                                                                                      |
|:----------------|:-------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                               |
| **Deciders**    | Arkadiusz Przychocki                                                                       |
| **Date**        | 2026-07-30                                                                                 |
| **Scope**       | `kernel/storage`                                                                           |
| **Owning Repo** | `exeris-kernel`                                                                            |
| **Driven By**   | `docs/ROADMAP.md` §"Storage: `BlobStorageProvider` SPI"; ADR-012 (isolation model)          |
| **Compliance**  | [No Waste Compute](../whitepaper.md) — streaming transfer with no `byte[]` round-trip       |

## Context and Problem Statement

File and media handling is the last common application concern with no kernel seam. A host application
that needs uploads, downloads, or signed-URL flows hand-wires S3, MinIO, GCS, or local-filesystem code
directly. Three costs follow, and they compound: the transfer path leaves the kernel's memory discipline
entirely (vendor SDKs deal in `byte[]` and `InputStream`, so a large object is copied through the heap
several times); nothing scopes objects to a tenant, so isolation — which ADR-012 governs rigorously for
rows — is re-invented per application, or forgotten; and `exeris-tooling` cannot generate upload or
download surfaces, because there is no kernel-side type to generate against.

Blob I/O satisfies the Wall test for SPI territory on both limbs. It is a runtime hot path: large-payload
streaming, and the natural home for a future `sendfile`-class zero-copy driver. And it has genuinely
alternative implementations — a local filesystem, an S3-compatible object store, and a cloud-vendor API
are not variations on one mechanism, they differ in addressing, in what a "signed URL" means, and in
whether one exists at all.

Doing nothing has a shape worth naming. The absence is not felt as a missing feature but as duplicated,
unaudited code in each downstream application — precisely the "software inflation" the kernel exists to
remove, and precisely where a tenant-isolation mistake is least likely to be reviewed.

**This ADR answers: what does the kernel promise about binary-object storage, and where does the promise
stop and the driver begin?**

## 🏁 The Decision

**Adopt a `BlobStorageProvider` SPI in a net-new top-level package `eu.exeris.kernel.spi.storage.blob`,
transferring bytes exclusively through `LoanedBuffer`, with every object reference resolved against the
ambient `StorageContext` so that a blob cannot address another tenant's namespace.**

The SPI describes *transfer and addressing*. It does not describe storage topology, vendor
authentication, or object lifecycle policy — those belong to drivers, and the contract says so explicitly
rather than leaving each driver to guess how much it may assume.

**Concrete obligations:**

1. **Package placement is `eu.exeris.kernel.spi.storage.blob` — a 17th top-level SPI package, not a
   subpackage of `persistence`.** Persistence is entity- and row-shaped: it owns transactions, an
   `EntityEncoder`/`EntityDecoder` pair, and the RLS predicate model. A blob is an opaque byte range with
   no schema, no transaction, and no row predicate. Folding blobs into `PersistenceEngine` would widen a
   contract whose current narrowness is what makes it enforceable. The intermediate `storage` segment
   exists so a later non-blob storage seam does not force a rename.

2. **Follow the house provider/engine split, deviating from the ROADMAP sketch.** `docs/ROADMAP.md`
   names `BlobStorageProvider` as though it were the operational type. Every other `*Provider` in the SPI
   (`PersistenceProvider`, `EventProvider`, `FlowProvider`, `TransportProvider`, `MemoryProvider`) is a
   ServiceLoader discovery handle carrying `providerId()` / `providerName()` / `priority()` and a
   `createEngine(config)` factory. This SPI keeps that shape: **`BlobStorageProvider`** discovers and
   constructs, **`BlobStore`** performs operations. Bootstrap exposes both through the established slot
   pair, `KernelProviders.BLOB_STORAGE_PROVIDER` and `KernelProviders.BLOB_STORE`. The ROADMAP wording is
   a naming sketch predating the type set; consistency with sixteen sibling packages wins over it.

3. **Bytes move on `LoanedBuffer`; the SPI exposes no `byte[]` and no `InputStream` on the transfer
   path.** Ownership follows the rule `LoanedBuffer` already documents for transport hand-off, stated
   once per direction so neither half is left to inference:
   - **Upload.** The caller owns each buffer it passes to `BlobUploadHandle`. A handle that retains a
     buffer beyond the call MUST `retain()` before returning and `close()` its own reference when done;
     the caller closes its reference regardless.
   - **Download.** The store owns each buffer it produces and transfers ownership to the caller, which
     MUST close it. A caller that forwards a buffer onward retains it first, per the same rule.
   - **Both handles are `AutoCloseable`,** and closing one releases every reference it still holds.
     Closing an upload handle without committing MUST NOT leave a partially written object visible.

4. **`BlobRef` is tenant-relative and never absolute.** It names a container and a key *within the
   caller's namespace*. Resolution to a physical location happens inside the store, using
   `StorageContext.isolationKey()` from the ambient context — never a value carried on the reference. A
   `BlobRef` forged or replayed from another tenant therefore resolves inside the caller's own namespace
   instead of escaping it: the isolation property holds structurally, not by validation.

5. **An absent `isolationKey` is a terminal deny, not a global namespace.** `StorageContext` leaves the
   key empty for system scope (`ImmutableStorageContext.GLOBAL`). A store MUST reject blob operations in
   that state rather than fall back to an unscoped location, on the same fail-closed reasoning ADR-012
   §4a applies to a declared-but-unhonourable isolation strategy: a shared fallback is the weakest
   possible placement, and reaching it silently is how tenant data ends up co-mingled. System-scope blob
   storage is out of scope (see below).

6. **Key derivation must be injection-safe, and this is a driver obligation the TCK checks.** Both
   plausible drivers interpolate a caller-supplied key into a namespace: a filesystem path, or an S3
   object key inside a bucket or prefix. A key containing `..`, a leading separator, or an encoded
   traversal MUST be rejected or neutralised so it cannot resolve outside the tenant namespace. This
   repeats a defect class the kernel has already paid for twice — the v0.8 `UriTemplate` work, which
   fixed path traversal and query injection in generated clients, and `PostgresIdentifier`, extracted to
   guard a `SET search_path` interpolation that cannot take a bind parameter — so the contract states it
   instead of trusting each driver to rediscover it.

7. **The signed-URL contract states what the SPI promises and, equally, what it does not.**
   - *Promised:* a returned URL grants exactly the one requested operation (read or write) on exactly
     one `BlobRef`, and is valid no longer than the requested time-to-live.
   - *Not promised:* the URL scheme, its internal structure, whether it embeds credentials, whether it
     can be revoked before expiry, or that it can be produced at all.
   - **Capability, not assumption.** A local-filesystem store has no meaningful signed URL, so the
     operation returns `Optional<URI>` and a store MAY decline uniformly. The TCK asserts the disjunction
     — a store either declines for every input or satisfies the promise for every input — because a store
     that sometimes returns a URL is the one shape a caller cannot program against.

8. **New error codes register in `KernelErrorCodes`.** The SPI opens an `EX-BLOB-*` family covering, at
   minimum, object-not-found, isolation-denied, signed-URL-unsupported, and transfer-failure. Exact
   numbers land with the SPI PR; the house rules apply unchanged — codes are constants in the single
   registry, never string literals at throw sites, and failure paths carry a `rawArgs` layout comment
   rather than formatted strings.

9. **An ArchUnit rule bans `java.io.File` and `java.nio.file.Files` inside the SPI package.** Filesystem
   types in the contract would encode one driver's addressing model into the seam. Drivers are
   unaffected: the filesystem binding uses NIO.2 freely, as Community drivers do elsewhere.

10. **Two in-repo bindings close the SPI.** A local-filesystem store and a minimal S3-compatible store
    over the existing `KernelWebClient` / `HttpClientEngine`. The S3 binding is deliberately narrow —
    single-object PUT, GET, ranged GET, and presigned-URL generation, path-style addressing against a
    MinIO-compatible target. **No multipart upload and no full SigV4 grid**; the supported authentication
    subset is documented with the driver rather than left to folklore. Two bindings are what turn the
    contract from a description into something falsifiable: a single binding proves only that the SPI can
    describe itself.

## Consequences

### ✅ Positive Outcomes

- **[+] Tenant isolation for blobs becomes structural.** Obligations 4 and 5 make cross-tenant addressing
  unrepresentable rather than merely forbidden — the property most likely to be got wrong in per-application
  code, and the one least likely to be reviewed there.
- **[+] The transfer path keeps the kernel's memory discipline.** A large object streams through pooled
  off-heap buffers instead of being copied through the heap by a vendor SDK, and the TCK's zero-leak
  assertion covers upload and download alike.
- **[+] `exeris-tooling` gains something to generate against.** Upload and download surfaces become
  generated code over a kernel type instead of hand-written vendor glue per application.
- **[+] The zero-copy driver becomes an implementation detail, not a rewrite.** A `sendfile`-class local
  driver slots in behind the same contract, because the SPI never promised a filesystem or a stream.

### ⚠️ Trade-offs

- **[-] A 17th top-level SPI package is real surface growth.** The kernel argues against abstraction
  layers that do not earn their keep, and this one must be judged by the same standard. The defence is
  the ≥2-driver test and the hot-path test, both of which blob I/O passes on their merits — but the cost
  is paid whether or not a given deployment stores blobs.
- **[-] `Optional<URI>` for signed URLs pushes a capability question onto every caller.** A caller must
  handle the declining case, which is more friction than a uniform API. The alternative — inventing a
  redirect-through-the-kernel fallback so the operation always succeeds — would put the kernel on the
  data path for every download, which is worse, and would hide from the caller that the deployment
  cannot do what it asked.
- **[-] The narrow S3 subset will not satisfy every target.** No multipart means very large uploads are
  a single request, and a partial SigV4 implementation may not interoperate with every S3-compatible
  vendor. The binding targets MinIO-compatible path-style access; broader coverage is a later decision,
  not an oversight.
- **[-] Ownership rules across a two-sided transfer are the SPI's sharpest edge.** `LoanedBuffer` is
  unforgiving — a missed `retain()` is a use-after-free, a missed `close()` is a leak — and an upload
  handle that buffers internally has exactly the shape where this goes wrong. The TCK runs
  `LeakDetectionMode.PARANOID` on both directions for that reason.

### 📋 What is NOT in scope

- **System-scope (`GLOBAL`) blob storage.** Obligation 5 denies it. Kernel-internal artefacts that need
  durable bytes have no such requirement today, and inventing a system namespace before there is a
  consumer would be target-state invention.
- **Shared-scope row visibility for blobs (ADR-012 §4b).** The shared-scope tier widens a *read
  predicate over rows*; a blob has no predicate to widen. Cross-tenant blob sharing is a separate
  question needing its own mechanism and its own decision. The two axes compose in the sense that a
  context may carry both — but nothing in this SPI consults `sharedScopeKey`, and the TCK pins that.
- **Multipart upload, the full SigV4 signing grid, and vendor drivers beyond S3-compatible** (GCS, Azure
  Blob).
- **Object lifecycle policy** — retention, expiry, versioning, legal hold. These are storage-vendor
  policy surfaces, not transfer contracts.
- **Content inspection** — type sniffing, virus scanning, thumbnailing, transformation.
- **CDN or cache integration.** The signed URL is the seam a CDN would attach to; the kernel does not
  model the CDN.
- **1.0 GA scope.** `docs/ROADMAP.md` marks the v0.11/v0.12 SPI row — Blob, Job, Cache, WebSocket,
  ServiceResolver, coordination — explicitly **post-1.0**. This SPI ships in v0.11 and 1.0 is not gated
  on it.

## Cross-references

- ADR-012 (Security Trust Model / isolation) — supplies `StorageContext.isolationKey()`, the fail-closed
  reasoning behind obligation 5, and the §4b shared-scope axis this SPI deliberately does not consult.
- ADR-006 (Spring-Free Kernel Boundary) — the SPI stays free of framework and vendor types; obligation 9
  is the local instance of that rule.
- ADR-045 (`HttpRetryPolicy`) — the S3-compatible driver composes retry through the existing policy seam
  rather than growing its own.
- `docs/ROADMAP.md` §"Storage: `BlobStorageProvider` SPI" — the entry this ADR realises, and the source
  of the naming sketch obligation 2 deviates from.
- `docs/subsystems/memory.md`, `CONTRIBUTING.md` §"Off-Heap Memory" — the `LoanedBuffer` lifecycle rules
  obligation 3 applies to a two-sided transfer.

## Engineering Protocol

The codebase is not yet compliant — this ADR precedes the SPI. Enforcement lands with the
implementation slices:

1. **`AbstractBlobStorageTck`** covers upload round-trip, download streaming, ranged read, the
   signed-URL disjunction (obligation 7), isolation-key scoping, the absent-key deny (obligation 5), and
   key-injection rejection (obligation 6). Bound by both drivers; `LeakDetectionMode.PARANOID`
   throughout.
2. **`ExerisArchitectureTest`** gains the obligation-9 rule: no `java.io.File` / `java.nio.file.Files`
   inside `eu.exeris.kernel.spi.storage..`.
3. **Testcontainers MinIO integration test** under `@Tag("integration")`, with its own CI gate, since the
   S3 binding cannot be proven against a stub.
4. **Registry check:** `EX-BLOB-*` codes present in `KernelErrorCodes` before any throw site references
   them.
5. **`docs/subsystems/storage.md`** lands with the SPI slice. Every kernel subsystem carries a contract
   doc; a seventeenth package without one is documentation drift on arrival.

Obligations 1, 2, and 9 are reviewable by inspection from the first SPI PR. Obligations 3–8 are only
proven by the TCK, so the SPI slice is not done until both bindings are green against it.
