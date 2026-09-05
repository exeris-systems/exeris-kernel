---
title: Policy — definition of done
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
---

# Policy — definition of done

All of these, in order, before work is called finished. The commands themselves are in
[`../references/build-and-ci.md`](../references/build-and-ci.md); this file is what may not be
skipped.

1. **`mvn clean install` green** — the full reactor for a cross-module change, `-pl <module> -am`
   for an isolated one. `compile` proves nothing in this repository.
2. **Lint-clean on the changed modules.** Step 1 covers this **unless** any `-Dpmd.skip` or
   `-Dcheckstyle.skip` was used anywhere in the loop; then re-check standalone. No new PMD or
   Checkstyle suppression without written justification.
3. **The architecture guards green — all of them.** The inventory and what each can see is
   [`scoped-bans.md`](scoped-bans.md); the commands are
   [`../references/build-and-ci.md`](../references/build-and-ci.md). Run them yourself rather than
   assuming CI covers it — the guard has historically been `@ArchIgnore`'d. **One invocation is not
   enough:** `-pl exeris-kernel-tck -am` never builds `exeris-kernel-community`, so
   `coreDoesNotDependOnCommunity` — the rule that would catch a Core → Community import — does not
   run under it. On this line the tck invocation additionally fails
   with `[No Class Loaded]` for a classpath reason, not a boundary one — take that suite from the
   full `mvn clean install` of step 1.
4. **Contract or SPI change → TCK and binding tests updated and green**, and the tagged gates run if
   their subject changed. A green default build is not evidence about a tagged test.
5. **Docs and ADR impact triaged** (`exeris-doc-impact-triage`); drift fixed, or deferred with a
   stated reason.
6. **Release notes and CHANGELOG describe published behaviour only** — no local-only links, no
   cross-repo private pull-request references.

Run the `exeris-pr-preflight` skill before any commit, push or pull request; it encodes this list as
a go/no-go gate. **Never report "ready to PR" from a green `verify` alone.**

## Why the lint step has its own clause

The parent POM binds `checkstyle:check` to `validate` and `pmd:check` to `verify`, both failing on
violation, so a full `mvn clean install` with no skip flags **is** lint-gated. The footgun is the
skip flags: they get used for fast iteration and for JDK 26 SIGSEGV workarounds, and because PMD
binds at `verify`, a `mvn test` loop never reaches it. A build that skipped lint proves nothing
about lint, however green it looked.

**Re-check on a tree you have already built.** PMD's type-resolution rules need an auxclasspath that
only exists after `compile`, so on a fresh worktree the standalone command reports violations that
are not there — measured on an untouched `development/0.12.0`: 16 `PMD Failure` lines and
`BUILD FAILURE` before compiling, 0 and `BUILD SUCCESS` after, same commit and same ruleset. It cries
wolf rather than missing anything, but a phantom `LawOfDemeter` finding has cost time before.
