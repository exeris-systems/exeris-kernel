#!/usr/bin/env bash
#
# Keeps .agents/evals/scenarios.yaml's own prose honest about its own contents.
#
# WHY THIS IS A GATE, and why it is this small.
#
# The suite's header states two numbers — how many cases assert a refusal to escalate, and how many
# of those are one half of a pair — and rule 14 makes the suite the thing that establishes
# behaviour. Both numbers were wrong within a day of being written, twice, in the pull request that
# introduced them: a case was added and the sentence describing the set was not.
#
# Getting them wrong is not cosmetic. The stated balance is the argument that the suite is not
# skewed toward positives, which is the failure mode its own header names — a suite of only
# positive cases passes against an agent that blocks everything. A wrong count is that argument
# resting on nothing.
#
# It also asserts the property the count is a shorthand FOR: that the `negative` tag and the
# assertion agree. A case tagged `negative` whose expectation is not a refusal, or a refusal that
# carries no tag, makes `--tags negative` select the wrong set — and that selector is how the
# number is meant to be derivable rather than remembered.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec python3 - "$ROOT/.agents/evals/scenarios.yaml" <<'PY'
import re, sys, yaml

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
cases = (yaml.safe_load(text) or {}).get("cases") or []
fails = []

tagged = {c["id"] for c in cases if "negative" in (c.get("tags") or [])}
# "asserts a refusal to escalate" — the answer the case expects is that the agent does NOT flag.
refusal = {c["id"] for c in cases
           if ((c.get("expect") or {}).get("fields") or {}).get("decision") == "PASS"
           or (c.get("expect") or {}).get("fields", {}).get("task_class") == "DOCS_ADR"
           and "negative" in (c.get("tags") or [])}
for cid in sorted(tagged - refusal):
    fails.append(f"'{cid}' is tagged negative but its expectation is not a refusal")
for cid in sorted(refusal - tagged):
    fails.append(f"'{cid}' expects a refusal but carries no `negative` tag — "
                 f"`--tags negative` would not select it")

WORDS = {"three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8,
         "nine": 9, "ten": 10, "eleven": 11, "twelve": 12}
m = re.search(r"^#\s+(\w+) of these cases assert a REFUSAL", text, re.M)
if not m:
    fails.append("the header no longer states how many cases assert a refusal — that sentence is "
                 "the argument that the suite is not skewed toward positives")
elif WORDS.get(m.group(1).lower()) != len(tagged):
    fails.append(f"the header says {m.group(1).lower()!r} cases assert a refusal; "
                 f"{len(tagged)} carry the tag")

m2 = re.search(r"^#\s+(\w+) of the (\d+) are one half of a PAIR", text, re.M)
if m2 and WORDS.get(m2.group(1).lower(), 0) > len(tagged):
    fails.append(f"the header claims {m2.group(1).lower()} of {m2.group(2)} are paired, "
                 f"which is more than the {len(tagged)} that exist")
if m2 and int(m2.group(2)) != len(tagged):
    fails.append(f"the header's pair sentence says {m2.group(2)}, the tag says {len(tagged)}")

if fails:
    print("eval-consistency-check: FAIL", file=sys.stderr)
    for f in fails:
        print(f"  - {f}", file=sys.stderr)
    raise SystemExit(1)
print(f"eval-consistency-check: OK — {len(cases)} cases, {len(tagged)} tagged `negative`, "
      f"and the tag agrees with the assertion in both directions.")
PY
