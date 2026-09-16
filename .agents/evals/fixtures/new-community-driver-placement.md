Task — add Redis-backed caching.

A `CacheProvider` SPI interface exists. The request is for a Redis implementation using
Lettuce, plus the wiring that makes it the default when `exeris.cache.provider=redis`.
Nothing has been written yet; the question is where each piece belongs.
