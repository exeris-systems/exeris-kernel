Change under review — `exeris-kernel-community`, HTTP client retry.

```java
final class CommunityBackoff {

    private static final long BASE_NANOS = 50_000_000L;
    private static final int MAX_SHIFT = 6;

    long delayNanos(int attempt) {
        long base = BASE_NANOS << Math.min(attempt, MAX_SHIFT);
        return base + ThreadLocalRandom.current().nextLong(base / 2 + 1);
    }
}
```

Called once per failed attempt, before the retry is scheduled.
