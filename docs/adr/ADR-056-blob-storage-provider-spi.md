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
   constructs, **`BlobStore`** performs operations. Bootstrap will expose both through the established
   slot pair, `KernelProviders.BLOB_STORAGE_PROVIDER` and `KernelProviders.BLOB_STORE`. The ROADMAP
   wording is a naming sketch predating the type set; consistency with sixteen sibling packages wins
   over it.

   *(Amended 2026-07-30, with the SPI: stated in the future tense, because the slot pair does not exist
   yet. The SPI and the first driver land ahead of bootstrap wiring, as `GraphProvider` did; the slots
   and the config binding land with the second driver, where a provider-selection decision first has
   something to select between. Until then a `BlobStore` is constructed directly by its caller.)*

3. **Bytes move on `LoanedBuffer`; the SPI exposes no `byte[]` and no `InputStream` on the transfer
   path.** *(Amended 2026-07-30, with the SPI. The original text set out a `retain()`/`close()`
   protocol per direction. Implementation showed no hand-off is needed at all, so the protocol is
   replaced by the stronger rule below — see the retired trade-off in Consequences.)*
   - **The caller owns its buffers, in both directions, throughout.** `BlobUploadHandle.write` takes a
     `MemorySegment` and MUST NOT retain a reference to it past the call;
     `BlobDownloadHandle.read` fills a caller-supplied `MemorySegment` and returns a count. Both mirror
     `TransportStream.write` / `TransportStream.read` exactly, including the non-positive no-op and the
     `-1` end-of-stream signal, so a reader who knows the transport seam already knows this one.
   - **Why nothing transfers ownership.** Per-chunk transfer would force a fresh allocation per chunk;
     caller-owned buffers let one pooled `LoanedBuffer` drive an entire transfer. It also deletes the
     failure mode outright: with no ownership crossing the seam there is no `retain()` to forget. A
     store that needs to defer a chunk must copy, and that copy is then visible in its own code rather
     than hidden in a lifetime rule.
   - **Both handles are `AutoCloseable`,** and closing one releases everything it holds. Closing an
     upload handle without committing MUST NOT leave a partially written object visible.

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

6. **Key derivation must be injection-safe.** *(Originally worded "a driver obligation the TCK checks";
   see the amendment below — it became a carrier invariant, so `BlobRefTest` checks it, not the TCK.)*
   Both
   plausible drivers interpolate a caller-supplied key into a namespace: a filesystem path, or an S3
   object key inside a bucket or prefix. A key containing `..`, a leading separator, or an encoded
   traversal MUST be rejected or neutralised so it cannot resolve outside the tenant namespace. This
   repeats a defect class the kernel has already paid for twice — the v0.8 `UriTemplate` work, which
   fixed path traversal and query injection in generated clients, and `PostgresIdentifier`, extracted to
   guard a `SET search_path` interpolation that cannot take a bind parameter — so the contract states it
   instead of trusting each driver to rediscover it.

   *(Amended 2026-07-30, with the SPI: discharged one level earlier than "driver obligation" implied.
   `BlobRef` rejects relative-navigation segments (`.` and `..`), absolute keys, separators, empty
   segments, and NUL at construction, so
   an unsafe reference is unrepresentable and no driver can forget the check. Driver-specific
   restrictions remain the driver's business — the carrier is the floor, not the ceiling. The
   filesystem binding additionally verifies containment after resolution, as a backstop that holds even
   if the carrier is later relaxed.)*

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

    *(Amended 2026-08-01, with the S3 binding. Four things the original wording left open, settled by
    building it:*

    - ***The authentication subset is header signing over `host`, `x-amz-content-sha256` and
      `x-amz-date`, plus query signing for presigned URLs.** Not implemented: chunked (`STREAMING-*`)
      payload signing, session tokens, and signing arbitrary caller-supplied `x-amz-*` headers. Each
      serves a capability this driver does not have — multipart upload, temporary credentials,
      server-side encryption — so implementing them would be signing for requests it cannot make.*
    - ***The endpoint must be `http://`, and `https://` is rejected at construction.** The Community
      HTTP client engine has no client-side TLS: `CommunityHttpTransportFactory` wires certificate
      material for listeners only, so a `CLIENT`-mode engine speaks cleartext whatever the scheme says.
      Accepting an `https` endpoint would send SigV4 credentials in the clear because a scheme was
      ignored. The driver targets a MinIO-compatible endpoint over a trusted network path; a public S3
      endpoint needs the Enterprise transport.*
    - ***A configurable single-object ceiling (`s3.maxObjectBytes`, default 8 MiB) replaces an implicit
      one.** Without multipart upload an object is held in one buffer for the length of a transfer, so a
      ceiling exists whether or not it is named; naming it makes the refusal loud (`EX-BLOB-8005`, before
      any allocation or request) instead of surfacing as an allocation failure or a decode error. It is
      also a per-request cost, because the client engine sizes every response buffer from its configured
      body ceiling — which is why the default is modest rather than generous. **Bounded above** at just
      under 2 GiB and refused at construction beyond it: the single-buffer design addresses an object
      with an `int`, so a larger ceiling would pass its own limit check and then narrow to a wrapped
      allocation size — the same failure the named ceiling replaces, reintroduced one level up.*
    - ***A prerequisite fix landed in the HTTP client.** The client decoder read `Content-Length` as a
      promise of bytes to come, so every `HEAD` ended as a truncation failure and the method was
      unusable — found the moment the driver tried to `stat` an object without downloading it. Framing
      is now decided by the request method (RFC 9110 §6.4.1), threaded from the engine because a
      response cannot carry it.)*

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
- **[-] ~~Ownership rules across a two-sided transfer are the SPI's sharpest edge.~~** *(Retired
  2026-07-30 by the obligation-3 amendment.)* The concern was real for the shape originally specified —
  `LoanedBuffer` is unforgiving, and an upload handle that buffers internally is exactly where a missed
  `retain()` becomes a use-after-free. It no longer applies, because nothing crosses the seam owning
  memory. The `LeakDetectionMode.PARANOID` TCK stays: it now proves the absence, catching a store that
  retains or releases a caller's buffer against a contract that says it must not.

### 📋 What is NOT in scope

- **System-scope (`GLOBAL`) blob storage.** Obligation 5 denies it. Kernel-internal artefacts that need
  durable bytes have no such requirement today, and inventing a system namespace before there is a
  consumer would be target-state invention.

  **What an application should do instead** (added 2026-09-02, after the question arrived from
  outside). The exclusion above says what is refused and not what to reach for, which leaves every
  application improvising the same answer. Rows have three scopes and blobs have one, so an
  application that models a `GLOBAL` entity has nowhere to put that entity's binary content — and the
  gap is real enough to name, even though the tier stays out.

  Sort the content by **who authors it**, because that decides the answer more reliably than who
  reads it:

  - **Authored by the developer, identical for every tenant, versioned with the code** — product
    imagery, icons, fonts, seed documents, catalogue art. This is a *build artefact*, not stored
    content. It belongs in the deployment artifact or behind a CDN, where it is cached, served
    without an isolation check that would mean nothing, and — the part that decides it — **rolled
    back with the code that references it**. Putting it in object storage buys nothing and adds a
    deployment coupling: the bytes and the code that names them start versioning separately.
  - **Authored by a tenant, owned by one, read by many** — a published document, a shared export, a
    public profile image. This is the genuinely uncovered case, and it is the one that would justify
    a tier. It is not covered today and an application needing it must carry its own store.

  Almost everything that presents as "shared assets" is the first kind. That is why this exclusion
  has cost so little in practice, and why a tier is gated on the second kind appearing rather than on
  the argument being made — the shape it would take is already known (widen the read, pin the write
  to the owner, as the shared-scope row tier does), so the missing input is a consumer, not a design.

  The reason to keep refusing until then is not effort. This subsystem's strongest property is that a
  cross-tenant reference is **not expressible**; a tier makes it expressible, and every safety
  argument here becomes conditional on the tier being set correctly. The blast radius is also
  asymmetric with the row tier: a mis-scoped row leaks a record, a mis-scoped blob leaks a file.
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
   signed-URL disjunction (obligation 7), isolation-key scoping, and the absent-key deny
   (obligation 5). Bound by both drivers; `LeakDetectionMode.PARANOID` throughout.
   *(Amended 2026-07-30: key-injection rejection is **not** here. Once obligation 6 moved into
   `BlobRef`'s constructor, an unsafe key stopped being reachable through the store at all, so the
   check belongs to `BlobRefTest` in the SPI module. A driver-level test would have had to construct an
   invalid carrier to exercise it, which the carrier forbids.)*
   Driver-local guards sit beside it, not in the TCK, because they assert driver decisions rather than
   contract: `CommunityFilesystemBlobLayoutTest` (a hostile isolation key cannot escape the store root;
   the encoding is injective) and `CommunityBlobJfrTest` (both failure events commit, and no recording
   carries an object key).
2. **`ExerisArchitectureTest`** gains the obligation-9 rule: no `java.io.File` / `java.nio.file.Files`
   inside `eu.exeris.kernel.spi.storage..`.
3. **Testcontainers MinIO integration test** under `@Tag("integration")`, since the S3 binding cannot be
   proven against a stub.
   *(Amended 2026-08-01: no new CI gate. The existing community integration job already runs every
   `@Tag("integration")` test in `exeris-kernel-community` — it is named for the persistence work that
   created it, but the Keycloak suite has ridden it since v0.10 and the MinIO suite joins them. A second
   job selecting the same tag in the same module would run the same tests twice.)*
4. **Registry check:** `EX-BLOB-*` codes present in `KernelErrorCodes` before any throw site references
   them.
5. **`docs/subsystems/storage.md`** lands with the SPI slice. Every kernel subsystem carries a contract
   doc; a seventeenth package without one is documentation drift on arrival.
6. **JFR failure events** for both the isolation deny and the transfer failure, registered in
   `docs/subsystems/telemetry.md` §Required Events.
   *(Added 2026-07-30, with the first driver. The original list omitted telemetry, and the milestone
   plan scheduled it a slice later with the second driver — but the failure sites ship now, and a slice
   that lands blind failure paths and instruments them afterwards is the worse order. Emitted from a
   choke point that also builds the exception, so the two cannot drift; the events are shared by both
   drivers.)*

Obligations 1, 2, and 9 are reviewable by inspection from the first SPI PR. Obligations 3–8 are only
proven by the TCK, so the SPI slice is not done until both bindings are green against it.
