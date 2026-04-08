# Exeris Community/Open-Core Prompts

Reusable prompts for Community/Open-Core review and implementation workflows.

## Starter Slash Prompts
- `community-architecture-review`
- `community-implementer`
- `community-tck-first-review`
- `community-performance-memory`
- `open-core-boundary`
- `community-pr-review`

## Extended Slash Prompts
- `core-vs-community-placement`
- `spi-purity`
- `community-provider-serviceloader`
- `community-docs-adr-review`
- `community-refactor-safety`
- `community-subsystem-review`

## Full Prompt Catalog
- See `exeris-community-prompt-pack.md` for the extended set (12 templates + quick prompts).

## Usage
1. Type `/` in Copilot Chat.
2. Select one starter prompt.
3. Paste PR diff, file list, or concrete task context.

## Guiding Intent
- Preserve The Wall and contract purity.
- Keep Core driver-agnostic and Community implementation-focused.
- Keep TCK-first discipline when observable contract behavior changes.
- Keep docs aligned with repository reality (no placeholder-as-implemented drift).
