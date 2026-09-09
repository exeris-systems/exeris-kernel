#!/usr/bin/env python3
"""Exeris agent hook — one implementation, six vendor wire formats.

Reads the canonical definitions from `.agents/hooks/hooks.yaml`, so a pattern is authored once
(agents-md-schema.md rule 12). The rendered per-vendor hook files do nothing but invoke this
script with `--hook <id> --vendor <name>`; they carry no patterns of their own.

Wire shapes differ per vendor in two places and only two: what arrives on stdin, and what a
decision looks like on stdout. Everything between them is shared.

  stdin   Claude/Codex : {"tool_name": "Bash", "tool_input": {"command": …, "file_path": …}}
          Copilot      : snake_case aliases of the same
          Cursor       : {"command": …} for beforeShellExecution
          Gemini       : {"toolName": …, "args": {…}}
          Antigravity  : {"tool_name": "run_command", "tool_input": {"command": …}}

  stdout  Claude/Codex/Copilot : {"hookSpecificOutput": {"permissionDecision": "deny", …}}
                                 and {"decision": "block", "reason": …} for a stop
          Cursor               : {"permission": "deny", "userMessage": …}
          Gemini/Antigravity   : {"decision": "deny", "reason": …}

Exit codes: 0 always, except where a vendor documents exit 2 as "blocked" and gives us no other
channel. The decision travels in the JSON; an exit code is a fallback, not the contract.

State lives under `.agents-state/<session>/` (git-ignored), keyed by the identifier the runtime
puts on the event. A shared directory would make a stop gate fire once per checkout and never
again — the first check ever run would discharge it for every session afterwards.

Failing closed is decided by `--on-error`, which the renderer sets from the hook's own `decision`
in hooks.yaml. It is NOT decided by the hook's name: a guarantee that holds only for hooks
someone happened to call `deny*` is not a guarantee, and the stop gate — the layer's actual
enforcement — is not called that. The default is `deny`, so a config this dispatcher cannot read
and a rendered command that predates this flag both fail in the safe direction.
"""
from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# Vendors whose stop event can actually block. Everywhere else `degrade: warn` applies and the
# gate prints its reason without stopping anything — recorded in manifest.yaml `degradations`.
STOP_BLOCKS = {"claude", "codex"}


def repo_root(start: str | None = None) -> str:
    """The repository under management — found from the WORKING DIRECTORY first.

    Walking up from this file finds whichever checkout the script itself lives in. Vendored that
    is the same repository; run from a bundle checkout, a plugin directory or a test it is not,
    and the dispatcher then reads another repository's rules. Every runtime in scope invokes a
    hook with the project as the working directory, so that is the authority; the script's own
    location is the fallback for the case where it is not.
    """
    for candidate in (start, os.environ.get("CLAUDE_PROJECT_DIR"), os.getcwd(), HERE):
        if not candidate:
            continue
        d = os.path.abspath(candidate)
        while d != os.path.dirname(d):
            if os.path.exists(os.path.join(d, ".agents")) or os.path.exists(os.path.join(d, ".git")):
                return d
            d = os.path.dirname(d)
    return os.getcwd()


def hooks_yaml() -> str:
    """The hook DEFINITIONS are the repository's; only this dispatcher is the bundle's.

    Vendored, this script sits at `.agents/vendor/<bundle>-<v>/hooks/bin/hook.py`, so a path
    relative to itself would find the bundle's own directory rather than the repository's rules.
    The repository's file wins; the sibling path is the fallback for an unvendored layout.
    """
    repo = os.path.join(repo_root(), ".agents", "hooks", "hooks.yaml")
    if os.path.exists(repo):
        return repo
    return os.path.join(HERE, "..", "hooks.yaml")


class ConfigUnavailable(RuntimeError):
    """The L0 rules could not be read. For a deny hook this is fatal, not permissive."""


def load_config() -> dict:
    try:
        import yaml
    except ImportError as exc:
        # Previously this returned {} and the dispatcher answered "allow" — so the layer ADR-085
        # calls "runtime, hard" switched itself off on any machine without pyyaml, silently.
        # A deny hook now refuses instead; a recorder still yields, because recording nothing is
        # not a safety failure.
        raise ConfigUnavailable("pyyaml is not installed, so the L0 rules cannot be read "
                                "(pip install pyyaml)") from exc
    path = hooks_yaml()
    if not os.path.exists(path):
        raise ConfigUnavailable(f"no hooks.yaml at {path}")
    try:
        with open(path, encoding="utf-8") as fh:
            return yaml.safe_load(fh) or {}
    except Exception as exc:
        raise ConfigUnavailable(f"hooks.yaml is unreadable: {type(exc).__name__}: {exc}") from exc


def find_hook(cfg: dict, hook_id: str) -> dict | None:
    for h in cfg.get("hooks") or []:
        if h.get("id") == hook_id:
            return h
    return None


def read_event() -> dict:
    raw = sys.stdin.read() if not sys.stdin.isatty() else ""
    if not raw.strip():
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {}


def _first(d: dict, *keys):
    for k in keys:
        if isinstance(d, dict) and d.get(k) not in (None, ""):
            return d[k]
    return None


def extract(event: dict) -> tuple[str, str]:
    """Return (command, file_path) from whichever vendor shape arrived."""
    args = _first(event, "tool_input", "toolInput", "args", "arguments", "input") or {}
    if not isinstance(args, dict):
        args = {}
    command = _first(event, "command") or _first(args, "command", "cmd", "script", "shellCommand") or ""
    path = _first(args, "file_path", "filePath", "path", "target_file", "notebook_path") or ""
    return str(command), str(path)


# Canonical event -> the name the Claude/Codex/Copilot envelope must carry. A hook wired to
# PostToolUse that answers "PreToolUse" is claiming to gate an action that already happened.
ENVELOPE_EVENT = {"pre-tool": "PreToolUse", "post-tool": "PostToolUse",
                  "stop": "Stop", "session-start": "SessionStart"}


def emit(vendor: str, event_kind: str, decision: str, reason: str) -> int:
    """Print the vendor's decision shape. `decision` is allow | deny | block."""
    if vendor == "cursor":
        payload = {"permission": "allow" if decision == "allow" else "deny"}
        if reason:
            payload["userMessage"] = reason
            payload["agentMessage"] = reason
    elif vendor in ("gemini", "antigravity"):
        payload = {"decision": decision, "reason": reason} if reason else {"decision": decision}
    elif event_kind == "stop":
        payload = {"decision": "block", "reason": reason} if decision == "block" else {}
    elif event_kind == "pre-tool":
        out = {"permissionDecision": "allow" if decision == "allow" else "deny"}
        if reason:
            out["permissionDecisionReason"] = reason
        payload = {"hookSpecificOutput": {"hookEventName": "PreToolUse", **out}}
    else:
        # post-tool and session-start take no permission decision: there is nothing left to permit.
        # An empty object is the documented "nothing to say" answer.
        payload = {}
    print(json.dumps(payload))
    if decision != "allow" and reason:
        print(reason, file=sys.stderr)
    # Exit 2 is the documented "blocked" fallback where the JSON channel is ignored.
    return 2 if decision in ("deny", "block") and vendor in ("claude", "codex", "copilot") else 0


def session_key(event: dict) -> str:
    """Which session this state belongs to.

    Without this the state directory is per *checkout*: `guardrails-run` is append-only and
    nothing clears it, so the first check ever run in a clone discharges the stop gate for every
    session afterwards — the gate fires exactly once and then never again. Every runtime in scope
    puts a session identifier on the event; where one is absent the parent process id is a
    serviceable proxy for "this agent run", and a missing key is treated as a fresh session rather
    than as a shared one.
    """
    def safe(v: str) -> str:
        # No dots: `..` survives a naive sanitiser and `.agents-state/../` is the repository root,
        # outside the gitignore entry and on top of whatever is named there.
        cleaned = re.sub(r"[^A-Za-z0-9_-]", "_", str(v))[:64].strip("_")
        return cleaned or "unnamed"

    for k in ("session_id", "sessionId", "conversation_id", "conversationId"):
        if event.get(k):
            return safe(event[k])
    env = os.environ.get("EXERIS_SESSION_ID") or os.environ.get("CLAUDE_SESSION_ID")
    if env:
        return safe(env)
    # NOT the process id. Each hook is spawned by its own shell, so a pid-derived key gives the
    # recorder and the gate different directories and the gate then never fires — a worse
    # fail-open than the one it replaced. One shared key instead, which the stop gate clears when
    # it passes, so the bleed between sessions is bounded rather than permanent.
    return "no-session"


def state_dir(cfg: dict, session: str) -> str:
    return os.path.join(repo_root(), cfg.get("state-dir") or ".agents-state", session)


def state_path(cfg: dict, name: str, session: str, create: bool = False) -> str:
    d = state_dir(cfg, session)
    if create:
        os.makedirs(d, exist_ok=True)
    return os.path.join(d, name)


def append_state(cfg: dict, name: str, value: str, session: str) -> None:
    if not value:
        return
    p = state_path(cfg, name, session, create=True)
    existing = set()
    if os.path.exists(p):
        existing = {l.strip() for l in open(p, encoding="utf-8") if l.strip()}
    if value not in existing:
        with open(p, "a", encoding="utf-8") as fh:
            fh.write(value + "\n")


def read_state(cfg: dict, name: str, session: str) -> list[str]:
    p = state_path(cfg, name, session)
    if not os.path.exists(p):
        return []
    return [l.strip() for l in open(p, encoding="utf-8") if l.strip()]


def tool_result(event: dict) -> bool | None:
    """True = the runtime said it failed, False = it said it succeeded, None = it said nothing.

    The distinction is the point. Most runtimes in scope report no status on a post-tool event, so
    a recorder can establish that a check was INVOKED and — on those runtimes — not that it
    passed. Collapsing "no information" into "succeeded" is what let a failing guardrail script
    discharge the stop gate, and collapsing it into "failed" would make the recorder useless.
    The gate says which of the two it observed rather than implying the stronger one.
    """
    resp = _first(event, "tool_response", "toolResponse", "response")
    if isinstance(resp, str):
        return None
    if not isinstance(resp, dict):
        return None
    for key in ("is_error", "isError", "error", "interrupted"):
        if key in resp:
            return bool(resp[key])
    code = resp.get("exit_code", resp.get("exitCode"), )
    if isinstance(code, int):
        return code != 0
    return None


def matches_any(patterns, text: str) -> bool:
    return any(re.search(p, text) for p in patterns or [])


def path_matches(globs, path: str) -> bool:
    rel = os.path.relpath(path, repo_root()) if os.path.isabs(path) else path
    rel = rel.replace(os.sep, "/")
    for g in globs or []:
        if fnmatch.fnmatch(rel, g):
            return True
        # `**` in fnmatch does not cross the directory separator the way the layout implies.
        if g.endswith("/**") and rel.startswith(g[:-3] + "/"):
            return True
        if g.endswith("/**") and rel == g[:-3]:
            return True
    return False


def refuse(vendor: str, on_error: str, why: str, kind: str = "pre-tool") -> int:
    """What to do when the dispatcher cannot establish what the rule says.

    `on_error` is rendered from the hook's `decision`, so a hook that enforces refuses and a
    recorder yields — decided by the rule rather than by the hook's name.
    """
    if on_error == "allow":
        print(f"exeris-hook: {why}; this hook only records, so it yields", file=sys.stderr)
        return emit(vendor, kind, "allow", "")
    decision = "block" if kind == "stop" else "deny"
    return emit(vendor, kind, decision,
                f"L0 cannot establish its rules and this hook enforces one, so it refuses "
                f"rather than waving the action through: {why}")


def run(hook_id: str, vendor: str, on_error: str, wired_event: str) -> int:
    event = read_event()
    session = session_key(event)
    try:
        cfg = load_config()
    except ConfigUnavailable as exc:
        return refuse(vendor, on_error, str(exc), wired_event)

    spec = find_hook(cfg, hook_id)
    if not spec:
        return refuse(vendor, on_error, f"no hook '{hook_id}' in hooks.yaml", wired_event)

    kind = spec.get("event", wired_event)
    # The config IS readable here, so the rule itself decides — the flag was only ever the
    # fallback for the case where it could not be read.
    on_error = "deny" if spec.get("decision") in ("deny", "block-or-allow") else "allow"
    command, path = extract(event)

    if spec.get("decision") == "deny":
        if command and matches_any(spec.get("match"), command):
            return emit(vendor, kind, "deny", " ".join((spec.get("reason") or "").split()))
        return emit(vendor, kind, "allow", "")

    if spec.get("record"):
        failed = tool_result(event)
        if failed is True:
            # A check that ran and FAILED has discharged nothing, on either branch — an edit the
            # runtime rejected did not happen either.
            return emit(vendor, kind, "allow", "")
        # `?` marks an entry whose result the runtime did not report, so the gate can say what it
        # actually observed instead of implying success.
        suffix = "" if failed is False else "?"
        if spec.get("tool") == "shell":
            for pat in spec.get("match") or []:
                m = re.search(pat, command)
                if m:
                    append_state(cfg, spec["record"],
                                 m.group(0).replace("\\", "").strip() + suffix, session)
                    break
        elif path and path_matches(spec.get("paths"), path):
            rel = os.path.relpath(path, repo_root()) if os.path.isabs(path) else path
            append_state(cfg, spec["record"], rel.replace(os.sep, "/"), session)
        return emit(vendor, kind, "allow", "")

    if kind == "stop":
        edited = read_state(cfg, "docs-edited", session)
        ran = read_state(cfg, "guardrails-run", session)
        blocked = []
        for rule in spec.get("rules") or []:
            hits = [f for f in edited if path_matches(rule.get("when-edited"), f)]
            if not hits:
                continue
            required = rule.get("requires") or []
            # ALL of them. `any` here would mean a rule listing two checks is discharged by
            # running either — the gate softens silently the moment a second requirement is added,
            # which is the failure this whole layer exists to prevent.
            missing = [req for req in required
                       if not any(req.rstrip("$").replace("\\", "") in r.rstrip("?") for r in ran)]
            if not missing:
                continue
            reason = " ".join((rule.get("reason") or "").split())
            blocked.append(f"{reason} (edited: {', '.join(sorted(hits)[:4])}; "
                           f"not run: {', '.join(missing)})")
        if not blocked:
            # Clear on a clean stop. Without this the `no-session` key — used where a runtime
            # names no session — would accumulate across sessions and discharge the next one's
            # gate before it started.
            import shutil
            shutil.rmtree(state_dir(cfg, session), ignore_errors=True)
            return emit(vendor, kind, "allow", "")
        unverified = [r for r in ran if r.endswith("?")]
        text = "L0 gate: " + " | ".join(blocked)
        if unverified:
            text += (f" [invocation observed but this runtime reported no result for: "
                     f"{', '.join(sorted(x.rstrip('?') for x in unverified))} — the gate verifies "
                     f"that a check ran, never that it passed]")
        if vendor in STOP_BLOCKS:
            return emit(vendor, kind, "block", text)
        # Degraded: the runtime documents no way to block a stop, so the gate reports and yields.
        print(f"exeris-hook (warning, {vendor} cannot block a stop): {text}", file=sys.stderr)
        return emit(vendor, kind, "allow", "")

    return emit(vendor, kind, "allow", "")


def main() -> int:
    ap = argparse.ArgumentParser(description="Exeris agent hook dispatcher")
    ap.add_argument("--hook", required=True, help="hook id from .agents/hooks/hooks.yaml")
    ap.add_argument("--vendor", default=os.environ.get("EXERIS_HOOK_VENDOR", "claude"),
                    choices=["claude", "copilot", "codex", "gemini", "antigravity", "cursor"])
    ap.add_argument("--event", dest="event", default="pre-tool",
                    choices=["pre-tool", "post-tool", "stop", "session-start"],
                    help="the event this hook is wired to. Rendered alongside --on-error, because "
                         "the event normally comes from the config — and when the config is what "
                         "cannot be read, a stop gate must still answer with a stop-shaped refusal "
                         "rather than a pre-tool one.")
    ap.add_argument("--on-error", dest="on_error", default="deny", choices=["deny", "allow"],
                    help="what to do when the rules cannot be read. Rendered from the hook's own "
                         "`decision`; the default is deny, so a command predating this flag fails "
                         "in the safe direction.")
    a = ap.parse_args()
    try:
        return run(a.hook, a.vendor, a.on_error, a.event)
    except Exception as exc:
        # Re-raising ConfigUnavailable here turned the one failure this layer made fatal into an
        # uncaught traceback and exit 1 — which every runtime reads as a non-blocking hook error,
        # i.e. allow. Every escape goes through `refuse`, which honours --on-error.
        print(f"exeris-hook: {type(exc).__name__}: {exc}", file=sys.stderr)
        return refuse(a.vendor, a.on_error, f"{type(exc).__name__}: {exc}", a.event)


if __name__ == "__main__":
    sys.exit(main())
