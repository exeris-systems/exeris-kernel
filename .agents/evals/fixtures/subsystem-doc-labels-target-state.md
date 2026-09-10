Change under review — `docs/subsystems/transport.md`.

```diff
+ ## Not in this repository
+
+ Submission-queue I/O (`io_uring`) is an enterprise-tier design and **no code in this repository
+ opens a ring**. The Community transport is a hybrid: NIO owns the selector and the accept loop,
+ and plain-TCP data I/O goes through POSIX `recv`/`send` via Panama FFM when the seam is armed.
+ This section exists so a reader who has seen the enterprise material does not conclude the ring
+ is here.
```

`grep -rn io_uring exeris-kernel-community/src/main/java` returns nothing, which is what the
paragraph says.
