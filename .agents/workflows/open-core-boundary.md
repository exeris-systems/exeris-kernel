---
description: Review Exeris changes through the Open-Core boundary — SPI/Core/Community/Enterprise placement, leakage risks, and smallest boundary-safe approach.
argument-hint: Change scope to evaluate through Open-Core split
---

Review this change specifically through the Exeris Open-Core boundary.

Change:
$ARGUMENTS

Questions to answer:
1. Is this capability part of SPI contract, Core shared infrastructure, Community OSS implementation, or Enterprise-only specialization?
2. Does this change accidentally move Enterprise-specific detail into Core or SPI?
3. Does this change weaken the Community experience in a way that violates the open-core intent?
4. Does this align with the current Core/Community/Enterprise split?
5. What is the smallest boundary-safe implementation approach?

Optimize for open-core clarity: Community should remain strong, but Enterprise-only implementation detail must not leak upward.
