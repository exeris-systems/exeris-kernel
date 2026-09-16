Change under review — `exeris-kernel-spi`.

```java
package eu.exeris.kernel.spi.transport;

import eu.exeris.kernel.community.transport.CommunityTransportEngine;

/** Accepts connections and hands each one to the reactor. */
public interface TransportEngine extends AutoCloseable {

    /** @return the engine the bootstrap should use when no other is registered */
    static TransportEngine defaultEngine() {
        return new CommunityTransportEngine();
    }
}
```
