Change under review — `exeris-kernel-community`, HTTP/1.1 request decode.

```java
final class Http1Decoder {

    void decodeHeaders(MemorySegment in, HttpExchange exchange) {
        byte[] scratch = new byte[(int) in.byteSize()];
        MemorySegment.copy(in, ValueLayout.JAVA_BYTE, 0, scratch, 0, scratch.length);
        exchange.headers(parse(new String(scratch, StandardCharsets.ISO_8859_1)));
    }
}
```

Called once per request from the reactor thread. `in` is a slice of the connection's receive
buffer.
