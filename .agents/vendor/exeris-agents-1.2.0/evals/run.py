#!/usr/bin/env python3
"""Eval runner — agents-md-schema.md rule 14.

Drives whichever agent CLI is on PATH, one turn per scenario, and grades the structured answer
against the schema the scenario names. Deterministic graders only: every assertion here is an
equality, a membership or a count. A rubric-driven judge is a separate tool and a separate cost.

    python3 .agents/evals/run.py                      # every case, first available runtime
    python3 .agents/evals/run.py --tags registry      # a subset
    python3 .agents/evals/run.py --runtime codex      # pin the runtime
    python3 .agents/evals/run.py --dry-run            # resolve and validate cases, invoke nothing

Exit code is the number of failed cases, capped at 125, so a scheduled run can gate on it.

Not run on pull requests. Model-in-the-loop CI on a solo project is unbounded cost against a
signal the deterministic gates already give; this runs on demand and on a schedule.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def repo_root(start: str) -> str:
    """Walk up to the git checkout.

    `HERE/../..` would be right only when this file sits at `.agents/evals/`. Vendored — and
    `evals` is in the bundle's vendored set — it sits at `.agents/vendor/<bundle>-<v>/evals/`,
    where two levels up is `.agents/vendor/`. The same trap the hook dispatcher was written to
    avoid, and the same escape.
    """
    d = os.path.abspath(start)
    while d != os.path.dirname(d):
        if os.path.exists(os.path.join(d, ".git")):
            return d
        d = os.path.dirname(d)
    return os.getcwd()


REPO = repo_root(HERE)

# Each runtime returns schema-validated JSON on stdout, which is why the schemas of rule 13 are the
# grader's input rather than a parsing problem.
RUNTIMES = {
    "claude": lambda agent, schema, prompt: (
        ["claude", "-p", prompt, "--agent", agent, "--json-schema", schema], None),
    "codex": lambda agent, schema, prompt: (
        ["codex", "exec", "--output-schema", schema, prompt], None),
}


def load_yaml(path: str):
    try:
        import yaml
    except ImportError:
        sys.exit("eval runner needs pyyaml: pip install pyyaml")
    with open(path, encoding="utf-8") as fh:
        return yaml.safe_load(fh) or {}


def file_registry(schema_path: str):
    """Resolve `$ref` relative paths from the filesystem, so a composed schema works offline.

    A repository schema narrows a vendored base by `allOf` + a relative `$ref`. Nothing here
    touches the network: agents-md-schema.md rule 8 forbids fetching at runtime, and a bundle is
    vendored precisely so the base is a file on disk.
    """
    from referencing import Registry, Resource
    from referencing.jsonschema import DRAFT202012

    base_dir = os.path.dirname(os.path.abspath(schema_path))

    def retrieve(uri: str):
        # A `$ref` is a path fragment out of a JSON file, which is the same class of input as
        # `--scenarios` and `schema_dir`. Three of those were guarded and this one was not.
        target = uri if os.path.isabs(uri) else os.path.normpath(os.path.join(base_dir, uri))
        target = within_repo(target, f"$ref '{uri}'")
        with open(target, encoding="utf-8") as fh:
            return Resource.from_contents(json.load(fh), default_specification=DRAFT202012)

    return Registry(retrieve=retrieve)


def validate(instance, schema_path: str) -> list[str]:
    """Schema conformance. Falls back to a shallow required-keys check when jsonschema is absent,
    and says which it did — a grader that silently weakens is worse than one that is missing."""
    try:
        import jsonschema
    except ImportError:
        schema = json.load(open(schema_path, encoding="utf-8"))
        missing = [k for k in schema.get("required", []) if k not in (instance or {})]
        return [f"missing required key '{k}' (shallow check: jsonschema not installed)"
                for k in missing]
    schema = json.load(open(schema_path, encoding="utf-8"))
    try:
        v = jsonschema.Draft202012Validator(schema, registry=file_registry(schema_path))
    except (ImportError, TypeError):
        v = jsonschema.Draft202012Validator(schema)
    return [f"{'/'.join(str(p) for p in e.path) or '<root>'}: {e.message}"
            for e in v.iter_errors(instance)]


def extract_json(raw: str):
    """The runtimes return JSON; a role that also printed its Markdown returns it in a fence."""
    raw = raw.strip()
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        pass
    start = raw.rfind("```json")
    if start != -1:
        end = raw.find("```", start + 7)
        if end != -1:
            try:
                return json.loads(raw[start + 7:end])
            except json.JSONDecodeError:
                pass
    first, last = raw.find("{"), raw.rfind("}")
    if first != -1 and last > first:
        try:
            return json.loads(raw[first:last + 1])
        except json.JSONDecodeError:
            pass
    return None


def grade(case: dict, parsed, raw: str, schema_path: str) -> list[str]:
    exp = case.get("expect") or {}
    fails: list[str] = []
    if parsed is None:
        return ["response was not parseable as JSON"]
    fails += validate(parsed, schema_path)
    for key, want in (exp.get("fields") or {}).items():
        got = parsed.get(key)
        if got != want:
            fails.append(f"field '{key}': expected {want!r}, got {got!r}")
    for needle in exp.get("contains") or []:
        if needle.lower() not in raw.lower():
            fails.append(f"expected the answer to mention {needle!r}")
    for needle in exp.get("forbidden") or []:
        if needle.lower() in raw.lower():
            fails.append(f"answer restated a forbidden string: {needle!r}")
    if "min_findings" in exp:
        n = len(parsed.get("findings") or [])
        if n < exp["min_findings"]:
            fails.append(f"expected at least {exp['min_findings']} findings, got {n}")
    if "any_check_result" in exp:
        want = exp["any_check_result"]
        results = [c.get("result") for c in (parsed.get("checks_run") or [])]
        if want not in results:
            fails.append(f"expected at least one check reported as {want!r}, got {results!r}")
    return fails


def build_prompt(case: dict, fixture_dir: str) -> str:
    parts = [case.get("prompt", "").strip()]
    fixture = case.get("fixture")
    if fixture:
        path = within_repo(os.path.join(fixture_dir, fixture), f"fixture '{fixture}'")
        parts.append(f"\n--- {fixture} ---\n{open(path, encoding='utf-8').read().strip()}")
    parts.append("\nAnswer with the JSON object your response contract requires, and nothing else.")
    return "\n".join(p for p in parts if p)


def within_repo(path: str, what: str) -> str:
    """Resolve `path` and refuse it if it leaves the checkout.

    `--scenarios` is a CLI argument and `defaults.schema_dir` / `fixture_dir` are values in a YAML
    file, so both reach `open()` as attacker- or typo-controlled path fragments. The runner has no
    business reading anything outside the repository it is evaluating, and a `schema_dir` that
    silently resolves somewhere else is the same failure this function's callers were written to
    fix, one level up: a path that resolves to *something* rather than to the right thing.
    """
    resolved = os.path.realpath(path)
    root = os.path.realpath(REPO)
    if resolved != root and not resolved.startswith(root + os.sep):
        sys.exit(f"eval-run: {what} resolves outside the repository ({resolved}); "
                 f"paths are repository-relative by design")
    return resolved


def default_scenarios() -> str:
    """The repository's own scenarios, not the vendored copy's.

    `HERE/scenarios.yaml` is right only when this runner sits at `.agents/evals/`. Vendored it does
    not, and `evals/` carries no scenarios file at all — so the documented invocation exited with a
    FileNotFoundError against a path inside the vendored tree.
    """
    repo_local = os.path.join(REPO, ".agents", "evals", "scenarios.yaml")
    return repo_local if os.path.exists(repo_local) else os.path.join(HERE, "scenarios.yaml")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenarios", default=default_scenarios())
    ap.add_argument("--runtime", choices=sorted(RUNTIMES))
    ap.add_argument("--tags", help="comma-separated; run only cases carrying one of them")
    ap.add_argument("--case", help="run a single case by id")
    ap.add_argument("--report", default=os.path.join(REPO, "working-notes", "eval-report.json"))
    ap.add_argument("--timeout", type=int, default=300)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    # Guard FIRST. This used to sit seven lines lower, after load_yaml() had already opened and
    # parsed the file — so the one CLI-controlled read the guard exists for still happened, and a
    # path outside the checkout produced a YAML parse error or a raw FileNotFoundError rather than
    # the refusal. A guard that runs after the sink is a comment.
    scenarios = within_repo(a.scenarios, "--scenarios")
    report_path = within_repo(a.report, "--report")

    cfg = load_yaml(scenarios)
    defaults = cfg.get("defaults") or {}
    # Relative to the SCENARIOS FILE, not to this script. The two were the same only while the
    # runner lived at `.agents/evals/` — vendored, it sits at `.agents/vendor/<bundle>-<v>/evals/`,
    # so `../schemas` resolved to the bundle's BASE schemas and `fixtures` to a directory the
    # vendored tree does not have. Every case then failed to resolve, in every consumer, with the
    # documented defaults. The same trap the dispatcher and repo_root() above were written for.
    base = os.path.dirname(scenarios)
    schema_dir = within_repo(os.path.join(base, defaults.get("schema_dir", "../schemas")),
                             "defaults.schema_dir")
    fixture_dir = within_repo(os.path.join(base, defaults.get("fixture_dir", "fixtures")),
                              "defaults.fixture_dir")

    cases = cfg.get("cases") or []
    if a.case:
        cases = [c for c in cases if c.get("id") == a.case]
    if a.tags:
        wanted = {t.strip() for t in a.tags.split(",")}
        cases = [c for c in cases if wanted & set(c.get("tags") or [])]
    if not cases:
        sys.exit("no cases selected")

    runtime = a.runtime or next((r for r in RUNTIMES if shutil.which(r)), None)
    if not runtime and not a.dry_run:
        sys.exit(f"no agent runtime on PATH (looked for: {', '.join(sorted(RUNTIMES))})")

    results, failed = [], 0
    for case in cases:
        entry = {"id": case["id"], "agent": case.get("agent"), "tags": case.get("tags") or []}
        named = (case.get("expect") or {}).get("schema")
        if not named:
            # Joining "" onto the schema directory resolves to the DIRECTORY, which exists, so the
            # case was reported `ok` while naming no schema at all — the same "resolves to
            # something rather than to the right thing" this runner's guards were written for.
            entry |= {"status": "error", "failures": ["case names no expect.schema"]}
            results.append(entry); failed += 1
            print(f"ERROR {case['id']}: no expect.schema"); continue
        schema_path = within_repo(os.path.join(schema_dir, named),
                                  f"case '{case['id']}' expect.schema")

        # F5: a missing fixture is recorded like a missing schema. It used to raise out of
        # build_prompt and abort the whole run, so one typo in one case hid every later result.
        try:
            prompt = build_prompt(case, fixture_dir)
        except OSError as exc:
            entry |= {"status": "error", "failures": [f"fixture not readable: {exc}"]}
            results.append(entry); failed += 1
            print(f"ERROR {case['id']}: fixture not readable"); continue

        if not os.path.exists(schema_path):
            entry |= {"status": "error", "failures": [f"schema not found: {schema_path}"]}
            results.append(entry); failed += 1
            print(f"ERROR {case['id']}: schema not found"); continue
        if a.dry_run:
            entry["status"] = "resolved"
            results.append(entry)
            print(f"ok    {case['id']} -> {case.get('agent')} / {os.path.basename(schema_path)}")
            continue

        cmd, _ = RUNTIMES[runtime](case["agent"], schema_path, prompt)
        started = time.time()
        try:
            proc = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True, timeout=a.timeout)
            raw = proc.stdout
        except subprocess.TimeoutExpired:
            entry |= {"status": "timeout", "failures": [f"no answer in {a.timeout}s"]}
            results.append(entry); failed += 1
            print(f"TIMEOUT {case['id']}"); continue

        fails = grade(case, extract_json(raw), raw, schema_path)
        entry |= {"status": "pass" if not fails else "fail", "failures": fails,
                  "seconds": round(time.time() - started, 1), "runtime": runtime}
        results.append(entry)
        if fails:
            failed += 1
            print(f"FAIL  {case['id']}")
            for f in fails:
                print(f"        {f}")
        else:
            print(f"pass  {case['id']}  ({entry['seconds']}s)")

    report = {"generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "runtime": runtime, "total": len(results), "failed": failed, "cases": results}
    os.makedirs(os.path.dirname(report_path), exist_ok=True)
    with open(report_path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)
        fh.write("\n")
    print(f"\n{len(results) - failed}/{len(results)} passed — report: {os.path.relpath(report_path, REPO)}")
    return min(failed, 125)


if __name__ == "__main__":
    sys.exit(main())
