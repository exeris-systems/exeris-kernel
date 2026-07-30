# Storage Subsystem — Blob Contract

**Status:** SPI + Community filesystem driver shipped in v0.11 (ADR-056). Post-1.0 per the ROADMAP's
narrowed-core decision — 1.0 GA is not gated on this subsystem.

**SPI package:** `eu.exeris.kernel.spi.storage.blob`
**Drivers:** `CommunityFilesystemBlobStorageProvider` (in repo); an S3-compatible driver is the second
in-repo binding that closes the ≥2-binding gate.

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

Layout is `<root>/t-<hex(isolationKey)>/<container>/<key>`.

The tenant directory is hex-encoded rather than used verbatim. An isolation key arrives from a verified
token claim, and a value of `..` would otherwise be a directory name that escapes the root — encoding
removes the question instead of enumerating the bad values. A containment check after resolution backs
it up.

Uploads land in a `.uploading` staging file and are atomically moved into place on commit. Content type
is kept in a `.ctype` sidecar, because a filesystem has nowhere else to put it; extended attributes would
be tidier but are not portable across the filesystems a Community driver must run on.

## Guards

| Guard | What it holds |
|---|---|
| `AbstractBlobStorageTck` | The contract above, bound per driver. Every transfer runs through a `PARANOID` allocator, so a store that retains or releases a caller's buffer is caught here. |
| `BlobRefTest` | Key validation — traversal, absolute paths, separators, empty segments, NUL — rejected at construction, and ordinary keys (nesting, spaces, Unicode, dotted names) still accepted. |
| `ExerisArchitectureTest.noFilesystemTypesInStorageSpi` | No `java.nio.file` inside the storage SPI package. `java.io` is already covered SPI-wide by `noJavaIoInSpi`. |

## Error codes

| Code | Meaning |
|---|---|
| `EX-BLOB-8001` | Object not found in the resolved namespace |
| `EX-BLOB-8002` | Isolation denied — no tenant scope to resolve against |
| `EX-BLOB-8003` | Transfer failure (I/O) |
| `EX-BLOB-8004` | Upload contract violation — byte count differs from the declared length |

No factory captures an object key: keys can carry application data, and a container plus a reason code
is enough to locate a fault without putting caller data into telemetry.

## Not in this subsystem

Multipart upload, the full SigV4 signing grid, vendor drivers beyond S3-compatible, object lifecycle
policy (retention, expiry, versioning), content inspection, and CDN integration. System-scope blobs and
shared-scope row visibility for blobs are excluded by contract, not merely unimplemented — see ADR-056.

## References

- `docs/adr/ADR-056-blob-storage-provider-spi.md` — the decision, its obligations, and its two
  implementation amendments.
- ADR-012 — the isolation model this subsystem resolves against.
- `docs/subsystems/memory.md`, `CONTRIBUTING.md` §"Off-Heap Memory" — `LoanedBuffer` lifecycle.
