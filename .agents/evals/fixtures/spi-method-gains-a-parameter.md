Change under review — `exeris-kernel-spi`.

`BlobStorage.open(String key)` becomes `BlobStorage.open(String key, ReadIntent intent)`,
where `ReadIntent` is a new enum in the same package. `AbstractBlobStorageTck` is unchanged.
The Community binding is updated; the Kafka binding is not, and still compiles because it
does not implement `BlobStorage`.
