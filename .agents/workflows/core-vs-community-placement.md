---
description: Decide whether an Exeris change belongs in Core or Community, with boundary/dependency implications and a minimal safe refactoring path.
argument-hint: Change scope to place in Core vs Community
---

Help decide whether this Exeris change belongs in Core or Community.

Use these principles:
- Core contains shared, driver-agnostic orchestration and infrastructure.
- Community contains OSS runtime implementations behind SPI contracts.
- Core must not hard-code Community implementation details.
- Community may depend on Core, not the other way around.
- If the capability is shared by Community and Enterprise and does not violate The Wall, prefer Core.
- If it is a concrete OSS driver/runtime implementation detail, prefer Community.

Change:
$ARGUMENTS

Output:
1. Recommended placement
2. Why not the other module?
3. Dependency/boundary implications
4. Smallest safe refactoring path
