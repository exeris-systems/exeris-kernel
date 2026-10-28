Change under review — `exeris-kernel-community`, persistence.

```java
final class CommunityTenantContext {

    private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();

    static void runAs(TenantId tenant, Runnable work) {
        CURRENT.set(tenant);
        try {
            work.run();
        } finally {
            CURRENT.remove();
        }
    }

    static TenantId current() {
        return CURRENT.get();
    }
}
```

Called once per transaction. The connection interceptor reads `current()` to set the session's
row-level-security tenant before the first statement.
