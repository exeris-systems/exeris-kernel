---
# DO NOT EDIT — generated from .agents/workflows/spi-purity.md (agents-md-schema.md rule 7). Edit the source.
description: Audit an Exeris SPI change for contract purity and The Wall compliance, with minimal corrective direction.
argument-hint: SPI diff or contract surface to audit
---
<!-- DO NOT EDIT. Generated from .agents/workflows/spi-purity.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
Audit this Exeris SPI change for purity and The Wall compliance.

SPI rules:
- SPI must remain implementation-blind.
- No driver/native/OS-specific details in contracts.
- No Community or Enterprise implementation knowledge.
- Contracts should express behavior and invariants, not implementation mechanism.

Change:
$ARGUMENTS

Please review:
1. Does the SPI surface leak implementation detail?
2. Are names/types/contracts too concrete to one implementation?
3. Is the behavior defined at contract level?
4. Would this SPI still make sense with multiple runtime implementations?
5. Minimal correction if purity is violated
