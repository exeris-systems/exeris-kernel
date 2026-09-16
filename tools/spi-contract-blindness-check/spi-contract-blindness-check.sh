#!/usr/bin/env bash
#
# Fails when a CONTRACT position in exeris-kernel-spi reports what a shipped driver does today.
#
# Why this is a gate rather than a review note: the SPI states obligations, and a driver that does
# not meet one is a bug against the SPI. Documentation drifts the other way under pressure — when a
# claim is found false, the cheap repair is to rewrite the claim to match the code that falsified
# it, and for an implementation that is the right repair. For an implementation-blind SPI (ADR-006)
# it inverts the direction of the contract: the interface stops saying what an implementer owes and
# starts reporting what one particular binding happens to do, defect included. That reads as
# accurate, passes every other gate, and ships to Maven Central as the contract.
#
# It happened. The 0.12 Javadoc sweep's adversarial pass refuted a set of claims; the correction
# pass then wrote sentences like "release of that buffer after the write is not currently performed
# by the Community client engine" into an Ownership line, and "Community implementations MUST close
# their own Bolt client" into an @implSpec. Nine such sentences reached the tree before review.
#
# WHAT IS ALLOWED in a contract position — the three rule-7 lines and @implSpec:
#   * an obligation on any implementation;
#   * a TIER-conditional obligation. The open-core tiers are part of this SPI's contract: the
#     priority 0/100 rank convention and "an Enterprise-tier implementation allocates nothing on
#     the heap" are contracts, not driver reports;
#   * the Wall asserted negatively — "MUST NOT reference JDBC, HikariCP or io_uring" — which is the
#     SPI declaring its own blindness;
#   * "this interface does not establish <X>", when that is the honest answer.
#
# WHAT IS NOT:
#   * a present-tense report of a named tier's behaviour ("the Community engine still performs…",
#     "…is currently a no-op");
#   * a concrete driver technology inside a positive obligation ("MUST close their own Bolt client").
#
# Those facts are not deleted, they are relocated: javadoc-conventions.md rule 6 gives @implNote as
# the home for "facts about this implementation that may change (Community driver behaviour)".
#
# CALIBRATION. The pattern is anchored by measurement, on the discipline javadoc-conventions.md
# rule 12 states for its own regexp. A first draft matched bare tense words in a contract position:
# 15 hits on this module and every one legitimate — "the default implementation is a no-op" is an
# idempotency contract, "the caller never closes a published payload" is an ownership contract. The
# discriminating signal is not the tense word but a TIER OR DRIVER AS ITS SUBJECT. Anchored that
# way the pattern catches 9 of the 9 sentences the repair removed and raises 0 false alarms against
# the 10 legitimate contract sentences the first draft had flagged.
#
# Two further corrections came from running it: a PERMISSION granted to a tier ("a Community-tier
# implementation MAY decode it into an intermediate representation") is a contract and not a report,
# so "MAY" no longer counts as a tense marker; and a driver technology inside a parenthetical
# example ("(e.g., io_uring on Linux only, IOCP on Windows only)") illustrates an obligation rather
# than being one, so parentheticals are stripped before the technology test. Each correction was
# made because the check fired on a sentence that was right, which is the only reason to widen an
# exception.
#
# Scope is exeris-kernel-spi ONLY, and that is the whole point rather than an omission: in
# exeris-kernel-core and exeris-kernel-community the same sentence is correct, because those modules
# ARE the driver.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$ROOT/exeris-kernel-spi/src/main/java"

[ -d "$SRC" ] || { echo "spi-contract-blindness-check: $SRC not found" >&2; exit 2; }

python3 - "$SRC" <<'PY'
import os, re, sys

SRC = sys.argv[1]

# A contract position: the three rule-7 lines of javadoc-conventions.md, and @implSpec.
CONTRACT = (
    r'<p><b>(?:Allocation|Thread confinement|Ownership):</b>\s*(.+?)(?=\n\s*\*\s*<p>|\n\s*\*\s*@|\n\s*\*/)',
    r'@implSpec\s+(.+?)(?=\n\s*\*\s*@|\n\s*\*/)',
)
TIER  = (r'(?:Community|Enterprise)(?:[\'’]s)?(?:[- ]tier)?'
         r'(?:\s+(?:reference\s+)?(?:engine|binding|client|implementations?|backend|provider|driver|sink))?')
TENSE = (r'(?:currently|still\s+\w+s|never\s+\w+s|'
         r'does\s+not\s+(?:perform|close|release|back|establish)|is\s+a\s+no-op|'
         r'allocates\s+lazily)')
REPORT = re.compile(rf'{TIER}[^.;]{{0,90}}?{TENSE}|{TENSE}[^.;]{{0,90}}?(?:by\s+the\s+)?{TIER}', re.I)
TECH   = re.compile(r'\b(?:Bolt|Neo4j|HikariCP|pgjdbc|JDBC|io_uring|Netty|IOCP)\b', re.I)
PAREN  = re.compile(r'\((?:e\.g\.|for example)[^)]*\)', re.I)   # an illustration, not an obligation
MUST   = re.compile(r'\b(?:MUST|MAY|SHALL)\b(?!\s+NOT\s+(?:reference|import))')
NEG    = re.compile(r'MUST NOT (?:reference|import)|with no knowledge of|no references to')

findings = []
for dirpath, _, files in os.walk(SRC):
    for fn in sorted(files):
        if not fn.endswith('.java'):
            continue
        path = os.path.join(dirpath, fn)
        text = open(path, encoding='utf-8').read()
        rel = os.path.relpath(path, SRC)
        for pat in CONTRACT:
            for m in re.finditer(pat, text, re.S):
                blk = re.sub(r'\s*\n\s*\*\s*', ' ', m.group(1)).strip()
                if NEG.search(blk):
                    continue
                line = text.count('\n', 0, m.start()) + 1
                if REPORT.search(blk):
                    why = 'reports what a named tier does today'
                elif TECH.search(PAREN.sub('', blk)) and MUST.search(blk):
                    why = 'names a driver technology inside a positive obligation'
                else:
                    continue
                findings.append((rel, line, why, blk))

if not findings:
    print('spi-contract-blindness-check: OK — no contract position in exeris-kernel-spi '
          'reports driver behaviour')
    raise SystemExit(0)

print(f'spi-contract-blindness-check: {len(findings)} contract position(s) describe an '
      f'implementation rather than a contract\n')
for rel, line, why, blk in findings:
    print(f'  {rel}:{line}  — {why}')
    print(f'      {blk[:200]}{"…" if len(blk) > 200 else ""}')
    print()
print('Move the driver fact to @implNote (javadoc-conventions.md rule 6) and leave the contract')
print('position saying what an implementation owes, or that this interface does not establish it.')
raise SystemExit(1)
PY
