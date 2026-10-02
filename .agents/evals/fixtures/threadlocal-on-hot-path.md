Change under review — `exeris-kernel-community`, HTTP/1.1 request decode.

```java
final class Http1Decoder {

    private static final ThreadLocal<byte[]> SCRATCH =
            ThreadLocal.withInitial(() -> new byte[8192]);

    void decodeHeaders(MemorySegment in, HttpExchange exchange) {
        byte[] scratch = SCRATCH.get();
        int n = (int) Math.min(in.byteSize(), scratch.length);
        MemorySegment.copy(in, ValueLayout.JAVA_BYTE, 0, scratch, 0, n);
        exchange.headers(parse(new String(scratch, 0, n, StandardCharsets.ISO_8859_1)));
    }
}
```

Called once per request from the reactor thread.
