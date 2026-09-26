Change under review — `docs/subsystems/transport.md`.

```diff
+ ## io_uring
+
+ The transport submits reads and writes through `io_uring`, which removes the syscall
+ per operation and is what lets the reactor stay on one thread under load.
```

`grep -rn io_uring exeris-kernel-community/src/main/java` returns nothing. The submission
queue is described in an enterprise-tier design note and no code in this repository opens
a ring.
