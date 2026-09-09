Change under review — `exeris-kernel-community`.

```java
package eu.exeris.kernel.community.persistence;

import eu.exeris.kernel.core.flow.FlowSnapshotValidator;
import eu.exeris.kernel.spi.persistence.FlowSnapshotStore;

public final class CommunityJdbcFlowSnapshotStore implements FlowSnapshotStore {

    private final FlowSnapshotValidator validator = new FlowSnapshotValidator();

    @Override
    public void save(FlowSnapshot snapshot) {
        validator.check(snapshot);
        // … JDBC write
    }
}
```

A new import of a Core type from a Community driver, plus the SPI interface it implements.
