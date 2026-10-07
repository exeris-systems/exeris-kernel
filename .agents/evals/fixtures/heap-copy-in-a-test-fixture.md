Change under review — `exeris-kernel-community-testkit`, a JUnit fixture.

```java
public final class CapturedRequests {

    /** Renders a captured request as text, for assertion messages. */
    public static String asText(MemorySegment captured) {
        byte[] copy = new byte[(int) captured.byteSize()];
        MemorySegment.copy(captured, ValueLayout.JAVA_BYTE, 0, copy, 0, copy.length);
        return new String(copy, StandardCharsets.ISO_8859_1);
    }
}
```

Nothing in `src/main/java` of any runtime module references this class.
