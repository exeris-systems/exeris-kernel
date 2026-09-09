Change under review — `exeris-kernel-community-testkit`, a JUnit fixture.

```java
public final class RecordingClock implements KernelClock {

    // One instant per test thread, so a parallel run cannot see another test's clock.
    private static final ThreadLocal<Instant> NOW =
            ThreadLocal.withInitial(() -> Instant.EPOCH);

    @Override
    public Instant instant() {
        return NOW.get();
    }

    public static void set(Instant instant) {
        NOW.set(instant);
    }
}
```

Nothing in `src/main/java` of any runtime module references this class.
