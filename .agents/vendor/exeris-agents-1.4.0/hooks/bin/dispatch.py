#!/usr/bin/env python3
"""Version-free indirection between a rendered hook config and the vendored dispatcher.

A rendered `.claude/settings.json` used to name the hook by its vendored path, and that path
carries the pin: `.agents/vendor/exeris-agents-1.2.0/hooks/bin/hook.py`. One string, two owners —
the adapter, written when the renderer last ran, and the vendored tree, replaced at every bump. Any
checkout holding one of them at a version the other does not have a command pointing at a file that
is not there.

That failure is not a warning. The interpreter exits non-zero on a file it cannot open, and a
non-zero exit from a `PreToolUse` hook is read as a block, so a recorder that declares
`--on-error allow` blocks the tool call anyway: the interpreter answers before the layer can. Every
shell call in the session is denied, and the reason is a path, not a rule.

It is also not hypothetical. The review environment for a pull request pairs the base branch's
protected `.claude/` with the branch's own tree, so the first review of every bundle bump ran with
no shell at all and reported its checks as `not-run` — twice on exeris-docs #106 before anyone
looked at why.

The renderer copies this file to `.agents/hooks/bin/dispatch.py` and the rendered config names that
instead. It carries no version: it reads the pin from `.agents/manifest.yaml` when the hook fires,
so a stale adapter and a fresh tree still meet. `manifest.yaml` remains the single authority for
which bundle runs — it is simply read later, at a moment when both halves are on disk together.

The hook then runs in THIS process, which is why two things here look defensive rather than
convenient. `sys.path[0]` is repointed at the vendored directory, because the interpreter set it to
*this* file's directory and that directory is not covered by the pin's digest — leaving it would
let a file dropped beside this one satisfy an import inside the gate. And every escape returns
through `emit`, because a traceback exits 1, and 1 is the code every runtime reads as a hook that
errored rather than a hook that refused.
"""
from __future__ import annotations

import sys

# BEFORE anything else is imported. The interpreter puts this file's own directory first on
# `sys.path`, and that directory is a generated adapter's home, not part of the tree the pin's
# digest covers — so a file dropped beside this one would satisfy an import made here or inside the
# gate this file starts. It is removed first and put back only if it turns out not to be the script
# directory after all (`-P` and PYTHONSAFEPATH mean the interpreter did not add one), which is a
# comparison that needs `os` — imported while the directory is already off the path.
_dropped = sys.path.pop(0) if sys.path else None

import os                                                           # noqa: E402

if _dropped is not None and os.path.realpath(_dropped) != os.path.dirname(
        os.path.realpath(__file__)):
    sys.path.insert(0, _dropped)

import json                                                         # noqa: E402
import re                                                           # noqa: E402
import runpy                                                        # noqa: E402

AGENTS = ".agents"
MANIFEST = os.path.join(AGENTS, "manifest.yaml")
FALLBACK = os.path.join(AGENTS, "hooks", "bin", "hook.py")
VENDOR = os.path.join(AGENTS, "vendor")
# A pin component is a plain name. Leading `.` is excluded, so `..` never reaches a path join, and
# no separator can appear in one — the two ways repository content could aim this file at code the
# digest in rule 8 does not vouch for.
SAFE = re.compile(r"\A[A-Za-z0-9][A-Za-z0-9._-]*\Z")
# What a rendered hook command may contain. This file does not own the flag vocabulary — hook.py
# does, and duplicating it here would put the argument contract in two places — so it checks the
# SHAPE of the vector: `--flag` followed by a plain value, nothing positional. The value alphabet
# is the same one `agents_render.py` requires of a hook id, a vendor and an event before it will
# build a command from them, so the renderer cannot emit a command this file then refuses; the
# renderer's copy of the pattern names this one.
FLAG = re.compile(r"\A--[a-z][a-z0-9-]*\Z")
VALUE = re.compile(r"\A[A-Za-z0-9][A-Za-z0-9._:@+-]*\Z")
BLOCK_BY_EXIT = ("claude", "codex", "copilot")


def repo_root() -> str:
    """The checkout holding `.agents/manifest.yaml`, working directory first.

    Deliberately smaller than hook.py's rule, and it does not have to agree with it: this answer
    only locates the manifest and the vendored file. Which repository's *rules* apply is decided by
    hook.py, from its own working directory, after this file has handed off to it.
    """
    for start in (os.environ.get("CLAUDE_PROJECT_DIR"), os.getcwd(),
                  os.path.dirname(os.path.abspath(__file__))):
        if not start:
            continue
        cur = os.path.abspath(start)
        while True:
            if os.path.exists(os.path.join(cur, MANIFEST)):
                return cur
            parent = os.path.dirname(cur)
            if parent == cur:
                break
            cur = parent
    return os.path.abspath(os.getcwd())


def pinned(text: str) -> tuple[str, str] | None:
    """`(bundle, version)` from the first import naming both, or None.

    pyyaml when it is importable, because the manifest is YAML and a line reader cannot see a
    flow-style `imports: [{bundle: …, version: …}]` that the renderer accepts — a disagreement
    about which bundle runs is worse than a dependency. The line reader below is the fallback for a
    checkout without pyyaml, where hook.py could not read its own rules either.
    """
    try:
        import yaml
    except Exception:
        yaml = None
    if yaml is not None:
        try:
            data = yaml.safe_load(text)
        except Exception:
            data = None                       # malformed; the line reader may still find the pin
        if isinstance(data, dict):
            for imp in data.get("imports") or []:
                if isinstance(imp, dict) and imp.get("bundle") and imp.get("version"):
                    return str(imp["bundle"]), str(imp["version"])
            return None
    return pinned_by_line(text)


def pinned_by_line(text: str) -> tuple[str, str] | None:
    """The block-style `imports:` list, read a line at a time.

    Scoped to that key. Scanning the whole file for the first `- bundle:` would let a sequence
    under any other key resolve a bundle the renderer and the checker do not — which is a
    disagreement about which code runs, arrived at silently.
    """
    inside = False
    item: dict[str, str] = {}
    for line in text.splitlines():
        entry = line.strip()
        if not entry or entry.startswith("#"):
            continue
        if not line[0].isspace():                             # a top-level key
            inside = entry.split(":", 1)[0].strip() == "imports"
            item = {}
            continue
        if not inside:
            continue
        if entry.startswith("-"):                             # a new list item
            item = {}
            entry = entry[1:].strip()
        key, sep, value = entry.partition(":")
        if sep and key.strip() in ("bundle", "version") and value.split():
            item[key.strip()] = value.split()[0].strip("'\"")
        if "bundle" in item and "version" in item:
            return item["bundle"], item["version"]
    return None


def under(base: str, path: str) -> str | None:
    """`path` resolved, or None if it does not stay inside `base`.

    Both ends are realpath'd, so a symlink pointing out of the checkout is caught as well as a
    `..` that survived the component check. This file runs what it returns; a path that left the
    tree would be code the pin cannot vouch for, executing with the session's permissions.
    """
    root = os.path.realpath(base)
    full = os.path.realpath(path)
    return full if full == root or full.startswith(root + os.sep) else None


def target(root: str) -> str | None:
    """The hook.py this call should run: the pinned vendored copy, else a repository-owned one.

    The pin is repository content and its value becomes a path this file executes, so it is
    checked rather than trusted: components must be plain names and the result must stay under
    `.agents/vendor/`. Rule 8's digest vouches for what is inside that tree, and for nothing else.
    """
    manifest = os.path.join(root, MANIFEST)
    if os.path.exists(manifest):
        try:
            with open(manifest, encoding="utf-8") as fh:
                pin = pinned(fh.read())
        except Exception:
            # A manifest that is unreadable for ANY reason — not only OSError; a file that is not
            # valid UTF-8 raises ValueError — must not escape as a traceback. A traceback exits 1,
            # and 1 is the code every runtime reads as "the hook errored", which is allow.
            pin = None
        if pin and SAFE.match(pin[0]) and SAFE.match(pin[1]):
            base = os.path.join(root, VENDOR)
            vendored = under(base, os.path.join(base, f"{pin[0]}-{pin[1]}",
                                                "hooks", "bin", "hook.py"))
            if vendored and os.path.exists(vendored):
                return vendored
    local = under(root, os.path.join(root, FALLBACK))
    return local if local and os.path.exists(local) else None


def flag_value(argv: list[str], name: str, default: str) -> str:
    """One `--name value` out of the vector, without assuming the vector is well formed."""
    if name in argv:
        i = argv.index(name)
        if i + 1 < len(argv):
            return argv[i + 1]
    return default


def payload(vendor: str, event: str, decision: str, reason: str) -> dict:
    """The decision in the shape this runtime reads.

    A wire format written twice can drift, and this is the one place that cost is worth paying:
    the file that owns the formats is the file that could not be found.
    """
    allow = decision == "allow"
    if vendor == "cursor":
        out: dict = {"permission": "allow" if allow else "deny"}
        if reason:
            out["userMessage"] = out["agentMessage"] = reason
        return out
    if vendor in ("gemini", "antigravity"):
        return {"decision": decision, "reason": reason} if reason else {"decision": decision}
    if event == "stop":
        return {} if allow else {"decision": "block", "reason": reason}
    if event == "pre-tool":
        out = {"permissionDecision": "allow" if allow else "deny"}
        if reason:
            out["permissionDecisionReason"] = reason
        return {"hookSpecificOutput": {"hookEventName": "PreToolUse", **out}}
    return {}                                 # post-tool and session-start permit nothing


def emit(vendor: str, event: str, decision: str, reason: str) -> int:
    """Write the decision and answer with the exit code that carries it where JSON does not.

    Exit 2 is the documented block on Claude, Codex and Copilot and is ignored on the others, so
    without the JSON above an `--on-error deny` would fail OPEN on cursor, gemini and antigravity.
    """
    print(json.dumps(payload(vendor, event, decision, reason)))
    if decision != "allow" and reason:
        print(reason, file=sys.stderr)
    return 2 if decision != "allow" and vendor in BLOCK_BY_EXIT else 0


def refuse(argv: list[str], reason: str) -> int:
    """No hook.py to run, or a command this file cannot recognise.

    It applies the caller's own `--on-error` the way hook.py applies it to an unreadable config:
    fail closed where the *rule* says to, open where it does not. It was the interpreter's exit
    code that decided before, which meant a recorder blocked too.
    """
    vendor = flag_value(argv, "--vendor", "claude")
    event = flag_value(argv, "--event", "pre-tool")
    if flag_value(argv, "--on-error", "deny") == "allow":
        print(f"exeris hook dispatch: {reason}; this hook only records, so it yields",
              file=sys.stderr)
        return emit(vendor, event, "allow", "")
    return emit(vendor, event, "block" if event == "stop" else "deny",
                f"L0 cannot reach its dispatcher and this hook enforces a rule, so it refuses "
                f"rather than waving the action through: {reason}")


def sanitised(argv: list[str]) -> list[str] | None:
    """The argument vector if it is `--flag value` pairs and nothing else, otherwise None.

    Shape only, and deliberately. The vocabulary — which flags exist and which values each accepts
    — belongs to `hook.py`, which defines it; checking it here would put one list in two artifacts
    on two different pins (this file comes from the MANIFEST-pinned vendored tree, the command that
    invokes it from the renderer at the ref CI pins), which is the "one string, two owners" defect
    this file's own docstring says it exists to remove — moved from the path to the vocabulary.

    The failure that motivated checking it here — argparse answering an unknown flag or an
    out-of-`choices` value by exiting 2, which from a pre-tool hook is a deny on every shell call —
    is fixed in `hook.py`, where a parse error now becomes a refusal in the vendor's shape.
    """
    if len(argv) % 2:
        return None
    seen: set[str] = set()
    for flag, value in zip(argv[0::2], argv[1::2]):
        if not FLAG.match(flag) or not VALUE.match(value) or flag in seen:
            return None
        seen.add(flag)
    return list(argv)


def main(argv: list[str]) -> int:
    root = repo_root()
    hook = target(root)
    if not hook:
        return refuse(argv, f"no hook.py under {root} — the manifest pins no vendored bundle, or "
                            f"the pinned tree is missing; re-vendor the bundle and re-render")
    args = sanitised(argv)
    if args is None:
        return refuse(argv, "the rendered hook command is not a sequence of --flag value pairs; "
                            "re-render it rather than hand-editing the provider config")
    # The hook runs here rather than in a second interpreter: no OS command, no process launch, and
    # no interpreter startup charged to every tool event. Its own directory goes first on the path,
    # which is what exec'ing it used to do implicitly — and that directory, unlike this file's, is
    # inside the tree the pin's digest covers.
    sys.path.insert(0, os.path.dirname(hook))
    sys.argv = [hook] + args
    # hook.py's `sys.exit(...)` raises SystemExit, which is not an Exception and so passes both
    # this handler and the one at the bottom of the file untouched, straight to the interpreter.
    # Catching it to read `.code` and return the same number was a longer way of writing what the
    # interpreter already does, and it discarded the message a non-integer exit carries.
    try:
        runpy.run_path(hook, run_name="__main__")
    except Exception as exc:                  # a hook that cannot start is not a hook that allows
        return refuse(argv, f"cannot run {hook}: {type(exc).__name__}: {exc}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except SystemExit:
        raise
    except Exception as exc:
        # Nothing reaches a runtime as a traceback. Exit 1 is "the hook errored", which every
        # runtime in scope treats as allow, so an unexpected failure here would silently disable
        # the layer — the single outcome this file exists to prevent.
        sys.exit(refuse(sys.argv[1:], f"{type(exc).__name__}: {exc}"))
