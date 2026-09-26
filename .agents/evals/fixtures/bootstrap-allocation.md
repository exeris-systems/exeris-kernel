Change under review — `exeris-kernel-core`, subsystem bootstrap.

```java
final class BootstrapDag {

    List<SubsystemNode> topologicalOrder(Map<String, Subsystem> registered) {
        // Built once, during start(), before the first connection is accepted.
        var indegree = new HashMap<String, Integer>(registered.size());
        var order = new ArrayList<SubsystemNode>(registered.size());
        var queue = new ArrayDeque<String>();
        …
        return List.copyOf(order);
    }
}
```

Called exactly once per JVM, from `KernelRuntime.start()`. Nothing on the request path reaches it;
the resulting list is read afterwards and never rebuilt.
