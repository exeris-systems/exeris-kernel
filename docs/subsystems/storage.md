# Storage Subsystem — Blob Contract

**Status:** SPI + two Community drivers shipped in v0.11 (ADR-056). Post-1.0 per the ROADMAP's
narrowed-core decision — 1.0 GA is not gated on this subsystem.

**SPI package:** `eu.exeris.kernel.spi.storage.blob`
**Drivers:** `CommunityFilesystemBlobStorageProvider` and `CommunityS3BlobStorageProvider`. Two bindings
is the point, not a convenience: a contract with one binding describes that binding, and a filesystem and
an object store disagree about almost everything below `BlobStore`, so a rule both satisfy is a rule
about blobs.

---

## What this subsystem is for

Binary objects — uploads, media, generated documents — whose bytes are opaque to the kernel and too large
to want on the heap. It exists as a kernel seam rather than per-application vendor code for two reasons:
the transfer path is a hot path where a `byte[]` round-trip is pure waste, and object placement is a
tenant-isolation decision, which is exactly the kind of decision applications should not each be making
for themselves.

It is **not** the persistence subsystem's neighbour by accident. Persistence owns rows: schemas,
transactions, and a row-visibility predicate. A blob has none of those. Keeping them apart is what lets
each contract stay small enough to enforce.

## The two properties worth knowing first

### Isolation is structural, not validated

A `BlobRef` names a container and a key. It carries **no tenant**. Every operation resolves it against
`StorageContext.isolationKey()` from the ambient scope:

```java
BlobRef ref = new BlobRef("documents", "invoices/2026-07.pdf");
// Tenant A and Tenant B may both hold this exact reference.
// It addresses a different object for each, and neither can name the other's.
```

The consequence worth stating plainly: a `BlobRef` forged, replayed, or leaked from another tenant
resolves inside the *resolving* caller's own namespace. There is no cross-tenant reference to reject,
because there is no way to write one down.

**An ambient context with no isolation key is a terminal deny** (`EX-BLOB-8002`), never an unscoped
fallback. `ImmutableStorageContext.GLOBAL` leaves the key empty, so system-scope code cannot store blobs.
That is deliberate: the fallback would place one tenant's object where every tenant can reach it, and it
would be reached by silence rather than by a decision.

### The caller owns the buffers, in both directions

Nothing crosses the seam owning memory. Uploads write out of a caller-owned segment; downloads read into
one:

```java
try (BlobUploadHandle upload = store.beginUpload(ref, size, "application/pdf");
     LoanedBuffer chunk = allocator.allocateInfrastructure(64 * 1024)) {
    while (source.hasNext()) {
        int n = source.fill(chunk.segment());
        upload.write(chunk.segment(), n);
    }
    upload.commit();
}
```

One pooled `LoanedBuffer` drives the whole transfer, and there is no `retain()` to forget — the failure
mode is absent rather than documented. `BlobDownloadHandle.read` mirrors `TransportStream.read` exactly,
including the non-positive no-op and `-1` at end of stream, so a drained read loop needs no special case.

## Visibility and failure

- **Nothing is visible until `commit()`.** An interrupted or aborted upload leaves no object, so a
  truncated transfer can never be mistaken for a complete one by a later reader.
- **Declared length is enforced.** `beginUpload` takes the exact byte count; writing more fails
  immediately, and committing fewer fails at commit (`EX-BLOB-8004`). The count is declared up front
  because a store speaking a request-oriented protocol must send it before the first byte.
- **`delete` is idempotent** — deleting an absent object returns `false` rather than throwing, so a
  retried delete is safe.
- **Ranges are forgiving at the tail.** A range past the end truncates; a range starting at the end is an
  immediate end-of-stream. A caller walking a large object with a fixed window should not need to know
  the size to avoid an exception on its final window.

## Signed URLs are a capability, not an assumption

`signedUrl` returns `Optional<URI>`, and **a store's answer must be uniform**: it signs for every input
or declines for every input. The filesystem driver declines, because a filesystem has no meaningful
signed URL.

A store that sometimes returned a URL could not be programmed against — the caller would be unable to
tell an unsupported operation from a missing object.

What the SPI promises about a URL it does return: exactly one operation, on exactly one `BlobRef`, valid
no longer than the requested TTL (capped by `BlobStorageConfig.maxSignedUrlTtl`). What it does not
promise: scheme, structure, whether credentials are embedded, or whether it can be revoked early.

## Filesystem driver notes

Layout is `<root>/t-<hex(isolationKey)>/objects/<container>/<key>`, alongside two sibling trees the
caller never addresses: `sidecars/<container>/<key>` for content types, and `staging/` for uploads in
flight.

The tenant directory is hex-encoded rather than used verbatim. `StorageContext.isolationKey()` is an
unconstrained `String` — `ImmutableStorageContext.shared` does not validate it — so what it may contain
is a policy question the store should not have to answer. Encoding makes the whole key one opaque
segment, which buys two things a `..` check would not: a key carrying separators (`../../etc`, or merely
`a/b`) cannot become a nested directory chain and climb out of the root, and the mapping is injective, so
two tenants cannot collide onto one directory. The `t-` prefix independently defeats a *bare* `..` key,
since `t-..` is an ordinary directory name; encoding is what covers the separator-bearing and colliding
cases. A containment check after resolution backs it up.

`CommunityFilesystemBlobLayoutTest` holds this property directly: strip the encoding and its
separator-bearing, deep-traversal, and injectivity cases fail, while the bare-`..` case still passes —
which is why the claim above is stated in terms of separators rather than `..`.

Uploads land in a staging file under `staging/` and are atomically moved into place on commit. Content
type is kept in a sidecar under `sidecars/`, because a filesystem has nowhere else to put it; extended
attributes would be tidier but are not portable across the filesystems a Community driver must run on.
The default content type is represented by the *absence* of a sidecar, so recording it means deleting
any the previous object left — an overwrite that only ever wrote would keep reporting the old type.

Both files used to be named by appending `.uploading` and `.ctype` to the object's own path. `BlobRef`
rejects traversal, not extensions, so those are endings a tenant may legitimately use: the suffix named
another object of the same tenant rather than a private file, and an upload to `report` truncated and
then moved away whatever was stored at `report.uploading`. Separate trees remove the collision instead
of forbidding the keys that expose it; disjointness is structural, since a container is always a child
of `objects` and nothing the caller controls is a direct child of the tenant directory. Staging files
are named by a random id rather than by the target key, so two concurrent uploads to one key no longer
share a file. `CommunityFilesystemBlobNamespaceTest` holds these properties.

**A staging file can outlive the process that made it, and the store does not sweep them.** A commit
either moves the staging file into place or deletes it, and an abort deletes it — but a kill signal
between opening the file and either outcome leaves it behind. The residue is bounded and harmless: it
is a random id under `staging/`, addressable by nothing, and never resolved by a read.

No sweep runs at store open, deliberately. A store root can be shared — two kernel instances, a rolling
deployment overlapping old and new — and from the filesystem an orphan and another process's in-flight
upload are the same thing: a staging file nobody here opened. Age does not separate them either, since
a large upload is legitimately old. Deleting one mid-flight would fail a live commit for the sake of
reclaiming a file nothing addresses. Reclaiming the space is therefore an operator task, and one they
can do safely because they know which instances are running.

## Guards

| Guard | What it holds |
|---|---|
| `AbstractBlobStorageTck` | The contract above, bound per driver. Every transfer runs through a `PARANOID` allocator, so a store that retains or releases a caller's buffer is caught here. |
| `BlobRefTest` | Key validation — traversal, absolute paths, separators, empty segments, NUL — rejected at construction, and ordinary keys (nesting, spaces, Unicode, dotted names) still accepted. |
| `ExerisArchitectureTest.noFilesystemTypesInStorageSpi` | No `java.nio.file` inside the storage SPI package. `java.io` is already covered SPI-wide by `noJavaIoInSpi`. |
| `CommunityFilesystemBlobLayoutTest` | A hostile isolation key cannot escape the store root, and the encoding is injective. Bound to the driver, not the SPI — the layout is a driver decision. |
| `CommunityBlobJfrTest` | Both failure events are actually committed, and no recording carries an object key. |
| `CommunityS3BlobStorageTckIT` | The same contract against live MinIO. `@Tag("integration")`, so it runs in the community integration gate, not the default build. |
| `CommunityS3ObjectKeyTest` | Tenant separation and wire encoding, in the default build — the integration gate proves a round trip works, not why two tenants stay apart. |
| `CommunityS3SignerTest` | Published SHA-256 vectors, that every signing input reaches the signature, and that the secret key is in no header and no minted URL. |

## Telemetry

Failures are raised through `CommunityBlobFailures`, which emits the JFR event and returns the exception,
so a call site reads `throw failures.transferFailed(...)`. The drivers have some twenty failure sites
across seven classes; pairing an emit with a throw by hand at each one makes "recorded but not thrown"
and "thrown but not recorded" both reachable by omission, and returning the exception removes the
pairing. The channel is bound once per driver to that driver's name, so a failure cannot be attributed to
the sibling driver by an argument slip, and both drivers share the event names — filter
`eu.exeris.kernel.storage.*` and read `providerName` to tell them apart. Both events are single-phase commits — transfers block on a channel and run on virtual threads,
so a `begin() → I/O → commit()` straddle would bind the carrier-local `EventWriter` across a park.

| Event | When | Fields |
|---|---|---|
| `eu.exeris.kernel.storage.BlobIsolationDenied` | Ambient context carries no isolation key, or a resolved path would leave the tenant directory | `providerName`, `operation`, `reason` (`no-isolation-key` / `path-escape`), `strategy` |
| `eu.exeris.kernel.storage.BlobTransferFailed` | An I/O failure on init, upload, download, stat, or delete — or a remote store refusing the request | `providerName`, `operation`, `container`, `exceptionClass`, `exceptionMessage`, `remoteStatus` (`0` when the failure was local) |
| `eu.exeris.kernel.storage.BlobCeilingExceeded` | A driver refuses a transfer larger than its configured single-object ceiling | `providerName`, `operation`, `container`, `declaredBytes`, `ceilingBytes` |

`reason` is a classification rather than the strategy name: `ImmutableStorageContext.GLOBAL` carries
strategy `SHARED` with an empty key, so reporting the strategy alone would tell an operator `SHARED` for
the one case where the interesting fact is that nothing was scoped. The strategy is recorded beside it.

Neither event carries an object key, for the same reason `BlobStorageException` refuses to capture one.

## Error codes

| Code | Meaning |
|---|---|
| `EX-BLOB-8001` | Object not found in the resolved namespace |
| `EX-BLOB-8002` | Isolation denied — no tenant scope to resolve against, or resolution left the tenant namespace |
| `EX-BLOB-8003` | Transfer failure (I/O) |
| `EX-BLOB-8004` | Upload contract violation — byte count differs from the declared length |
| `EX-BLOB-8005` | Object exceeds the driver's configured single-object ceiling — a refusal by policy, before any allocation or request |
| `EX-BLOB-8006` | A remote store answered and refused; the status is carried, because `403` is the caller's credential or clock and `5xx` is the store's problem |

No factory captures an object key: keys can carry application data, and a container plus a reason code
is enough to locate a fault without putting caller data into telemetry.

## S3-compatible driver notes

Layout is `t-<hex(isolationKey)>/<container>/<key>` under one bucket, path-style — the filesystem
driver's tenant prefix, without its `objects/` tree. S3 carries a content type as object metadata and
stages a multipart upload server-side, so it has no private files to keep out of the caller's namespace
and nothing to separate them from. The two layouts agree on everything an operator has to reason about
— the tenant prefix, its encoding, and the container/key tail. There is no containment re-check after resolution, unlike the
filesystem driver: nothing here normalises the key, so an assertion that a just-concatenated string
starts with its own prefix would confirm its own last statement. `BlobRef` refuses relative-navigation
segments, and the hex prefix is one opaque segment — the same two properties the filesystem layout rests
on.

**Configuration.** `location` is the endpoint; the rest arrives as properties.

| Property | Default | Meaning |
|---|---|---|
| `s3.bucket` | *(required)* | The single bucket every object lands in — tenants are separated by key prefix, not by bucket |
| `s3.accessKey` / `s3.secretKey` | *(required)* | SigV4 credentials |
| `s3.region` | `us-east-1` | SigV4 credential-scope region |
| `s3.maxObjectBytes` | 8 MiB | Ceiling on a single object. Bounded above at just under 2 GiB and refused at construction beyond it — the single-buffer design addresses an object with an `int`, so a larger ceiling could not be honoured |

**Cleartext only.** `CommunityHttpTransportFactory` wires certificate material for listeners, not for
client connections, so a `CLIENT`-mode engine speaks cleartext whatever the endpoint scheme says. An
`https://` endpoint is therefore **rejected at construction** rather than silently downgraded — sending
SigV4 credentials in the clear because a scheme was ignored is not a failure that may be quiet. The
target is a MinIO-compatible endpoint over a trusted network path.

**The ceiling is a memory budget, not just a limit.** The driver holds an object in one buffer for the
length of a transfer, because a single `PUT` must declare `Content-Length` before its first body byte and
multipart upload is out of scope. The Community HTTP client engine also reads every *response* into one
buffer sized from its configured body ceiling — so raising `s3.maxObjectBytes` raises the allocation a
`stat` pays, not only the largest object allowed. The default is deliberately modest for that reason.

That single buffer is also why the ceiling has an upper bound. Both the allocator and the engine's
aggregate sizing address it with an `int`, so a ceiling above roughly 2 GiB could not be allocated even
though it would pass its own limit check — the transfer would narrow to a wrapped size at allocation,
which is precisely the failure the named ceiling exists to replace with a loud refusal. The bound is
enforced at construction, so an unhonourable ceiling is a startup error rather than a first-transfer one.

**Two round trips per read.** Every download begins with a `HEAD`. The driver must know an object's size
before deciding whether to pull it into one buffer, and a ranged read needs the total for
`BlobMetadata.sizeBytes()` regardless. Skipping it would mean discovering the ceiling after the bytes had
arrived, or reporting a range length as the object size. `DELETE` pays the same cost for a different
reason: S3 answers `204` whether or not anything was there, and the contract promises the caller can tell
a removal from a no-op.

Two round trips means two answers that can disagree. The object may be overwritten smaller between the
`HEAD` and the `GET`, or a store may answer a `Range` with less than was asked. **The `GET` bounds the
read; the `HEAD` size only sizes the request.** A download handle hands out no more than the response
buffer's write cursor holds, and a slice offset past that is an immediate end-of-stream. The buffer is
pooled, so its segment spans the whole slot rather than this response — reading to the `HEAD` figure
would succeed and return whatever the previous response left there. `BlobMetadata.sizeBytes()` still
reports what `HEAD` said, so a caller that reads to end-of-stream can tell the two apart.

**Signing subset.** Header signing over a fixed three-header set (`host`, `x-amz-content-sha256`,
`x-amz-date`) and query signing for presigned URLs. Not implemented: chunked (`STREAMING-*`) payload
signing, session tokens, and signing arbitrary caller-supplied `x-amz-*` headers — each serves a
capability this driver does not have, so implementing it would be signing for requests it cannot make.

## Bootstrap and provider selection

`CommunityStorageSubsystem` (phase `SERVICES`, no dependencies) boots the subsystem, and
`StorageBootstrap` in Core selects the driver. Selection is **by configured id, not by ranking** —
the one bootstrap in the kernel that does not rank by `priority()`, because both Community drivers
register at the same priority and are not interchangeable: one needs a writable directory, the other
credentials and a reachable endpoint. Ranking them would decide where a tenant's objects land by
ServiceLoader order and a class-name tie-break.

| Key | Meaning |
|---|---|
| `storage.blob.provider` | The driver id: `blob-fs-community` or `blob-s3-community`. **Unset means blob storage is off.** |
| `storage.blob.location` | Driver-interpreted root. A **directory** for the filesystem driver; the **endpoint** `http://host:port` for S3 — not the bucket, which is a property. Required once the provider key is set. |
| `storage.blob.maxSignedUrlTtlSeconds` | Signed-URL ceiling; defaults to `BlobStorageConfig`'s. |
| `storage.blob.s3.bucket` | S3 only, **required**. |
| `storage.blob.s3.accessKey`, `storage.blob.s3.secretKey` | S3 only, **required**. |
| `storage.blob.s3.region`, `storage.blob.s3.maxObjectBytes` | S3 only, optional; the driver's defaults apply. |

The `storage.blob.s3.*` keys are forwarded into `BlobStorageConfig.properties()` under the driver's
own names (`s3.bucket`, …). They are **enumerated in the subsystem rather than swept from a prefix**,
because `ConfigProvider` answers `getString(key)` and nothing else — there is no way to ask it for
every key beneath a prefix. A driver that grows a property therefore gets it read only once that
list grows too; what stops that being silent is that the driver refuses a missing required property
at construction rather than starting half-configured. The alternative — a provider declaring its own
keys through the SPI — is a `BlobStorageProvider` change with a TCK obligation behind it, and is
recorded in the ROADMAP rather than taken.

**Absent configuration is not ambiguous configuration.** With `storage.blob.provider` unset the
subsystem binds nothing and reports running, exactly as a kernel with no storage behaves; both
drivers sit on every Community classpath, so refusing to boot without the key would stop every
deployment that never wanted blob storage. What is refused is asking for storage *without saying
which*: an id naming no discovered driver fails at boot with `EX-BLOB-8008`, carrying the key, the
value that was set and the ids that were available. An empty classpath is `EX-BLOB-8007` instead —
nothing is ambiguous there, and the fix is a dependency rather than a key.

Which driver won is recorded on the `eu.exeris.kernel.storage.StorageBootstrapSelected` JFR event.
The provider slots are `KernelProviders.BLOB_STORAGE_PROVIDER` and `BLOB_STORE` — named `BLOB_*`
rather than `STORAGE_*` because `STORAGE_CONTEXT` is ADR-012's tenant-isolation carrier and has
nothing to do with object storage.

## Not in this subsystem

Multipart upload, the full SigV4 signing grid, vendor drivers beyond S3-compatible, object lifecycle
policy (retention, expiry, versioning), content inspection, and CDN integration. System-scope blobs and
shared-scope row visibility for blobs are excluded by contract, not merely unimplemented — see ADR-056.

## References

- `docs/adr/ADR-056-blob-storage-provider-spi.md` — the decision, its obligations, and its two
  implementation amendments.
- ADR-012 — the isolation model this subsystem resolves against.
- `docs/subsystems/memory.md`, `CONTRIBUTING.md` §"Off-Heap Memory" — `LoanedBuffer` lifecycle.
