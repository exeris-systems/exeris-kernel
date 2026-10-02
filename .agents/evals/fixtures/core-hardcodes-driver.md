Change under review — `exeris-kernel-core`.

```java
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.community.persistence.CommunityJdbcFlowSnapshotStore;

final class BootstrapWiring {

    FlowSnapshotStore snapshotStore(KernelConfig config) {
        // ServiceLoader kept timing out in the integration suite, so wire it directly.
        return new CommunityJdbcFlowSnapshotStore(config.dataSource());
    }
}
```
