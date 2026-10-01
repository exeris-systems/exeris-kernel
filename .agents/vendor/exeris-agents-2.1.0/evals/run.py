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
import re
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import unquote, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))


class PathEscapeError(ValueError):
    """A path resolving outside the repository checkout."""


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

# How each runtime is driven, and the two things that were wrong about it.
#
# `--json-schema` takes a schema INLINE — its own `--help` example is a literal
# `{"type":"object",...}` — and it was handed `schema_path`, a filesystem path. The CLI was being
# given the name of a file where it documents a document.
#
# The deeper problem is that passing it at all makes the eval test a shape the profile never emits.
# Rule 13 says a role answers with its human-readable response and reproduces the decision as a
# fenced `json` block AFTER it; `--json-schema` puts the CLI into structured-output mode, so the
# turn the eval graded would not be the turn the role performs in review or in a session. An eval
# that exercises a different output than production is not evidence about production.
#
# So the schema is NOT pushed onto the CLI here. The runner drives the role the way it actually
# runs, `extract_json` takes the fenced block out of the answer, and `validate()` checks it against
# the same schema — which is where rule 13 says validation belongs ("validation happens in evals
# and in the CI review"). The schema argument stays in the signature because codex's
# `--output-schema` genuinely does take a path.
#
# NOT VERIFIED against a live CLI: the flag names and their argument kinds are read from
# `claude --help` on 2026-09-09 (`--agent <agent>`, `--json-schema <schema>` with an inline
# example) and from codex's documented `exec --output-schema`. Whether `--agent <name>` resolves a
# rendered `.claude/agents/<name>.md` profile is untested here, and `--dry-run` deliberately prints
# what would run so the vector can be inspected without spending a turn.
RUNTIMES = {
    "claude": lambda agent, schema, prompt: (
        ["claude", "-p", prompt, "--agent", agent], None),
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


def located(schema: dict, schema_path: str) -> dict:
    """The schema, carrying the file it was read from as its `$id`.

    Nothing in the bundle carries one: an `$id` in a vendored base would make its canonical
    identifier a URL to fetch or register, which is the whole reason the bases do without. But a
    validator with no `$id` starts from an empty base URI, and every `$ref` then resolves relative
    to relative — where the join is not the join anyone means. See `resolvable_from()`. The
    identifier is supplied here, at read time, out of where the file actually is: it names a
    location, never something to retrieve.

    The file wins over an `$id` the schema declares, and that is deliberate. A repository may give
    its schema an identifier — JSON Schema invites it — and every relative `$ref` in a vendored
    layout is still written relative to the file, because that is the only thing rule 8 lets a
    reference resolve from. Deferring to a declared `$id` sent those references off to join a URL
    the tree knows nothing about; the failure then arrived as a missing file, pointing the reader
    at the vendored tree instead of at the identifier that redirected them.
    """
    if not isinstance(schema, dict):
        return schema
    return {**schema, "$id": resolvable_from(schema_path)}


def resolvable_from(path: str) -> str:
    """An absolute path as the URI a `$ref` can be joined against.

    A relative base is the trap, and it cost a real failure: with the composed schema's own
    directory as the base, `urljoin("../vendor/<pin>/schemas/verdict.base.schema.json",
    "handoff.base.schema.json")` is `"vendor/<pin>/schemas/handoff.base.schema.json"` — the leading
    `../` is normalised away, the second hop lands in a directory that does not exist, and a
    verdict carrying a handoff raised `Unresolvable` out of the grader instead of being graded.
    An absolute `file:` URI joins by the rules the resolver assumes.
    """
    return Path(os.path.abspath(path)).as_uri()


def file_registry(schema_path: str):
    """Resolve `$ref` paths from the filesystem, so a composed schema works offline.

    A repository schema narrows a vendored base by `allOf` + a relative `$ref`, and that base has
    relative `$ref`s of its own — `handoff.base.schema.json`, beside it in the vendored tree. Each
    resolves against the file that names it, which is what the `file:` URIs here buy. Nothing
    touches the network: agents-md-schema.md rule 8 forbids fetching at runtime, and a bundle is
    vendored precisely so every base is a file on disk.
    """
    from referencing import Registry, Resource
    from referencing.jsonschema import DRAFT202012

    base_dir = os.path.dirname(os.path.abspath(schema_path))

    def retrieve(uri: str):
        # A `$ref` is a path fragment out of a JSON file, which is the same class of input as
        # `--scenarios` and `schema_dir`. Three of those were guarded and this one was not.
        if uri.startswith("file:"):
            target = unquote(urlparse(uri).path)
        elif os.path.isabs(uri):
            target = uri
        else:
            target = os.path.normpath(os.path.join(base_dir, uri))
        target = within_repo(target, f"$ref '{uri}'")
        with open(target, encoding="utf-8") as fh:
            return Resource.from_contents(json.load(fh), default_specification=DRAFT202012)

    return Registry(retrieve=retrieve)


UNEXPECTED_RE = re.compile(
    r"Unevaluated properties are not allowed \((.*?) was unexpected\)|"
    r"Unevaluated properties are not allowed \((.*?) were unexpected\)"
)

# Recognised, not parsed. The items message names the VALUES it refused — measured,
# `Unevaluated items are not allowed ('a', 'b' were unexpected)` — and two equal items are one
# string in it, so the positions cannot be read back out. `find_declared_item_indexes()` decides
# this line by coverage instead, and a regex that pretended to capture would be the one place
# claiming otherwise.
UNEVALUATED_ITEMS_RE = re.compile(r"Unevaluated items are not allowed \(")


def instance_at(instance, path: tuple):
    """The value at `path` inside `instance`, or None when the path is not there."""
    curr = instance
    for step in path:
        if isinstance(step, str) and isinstance(curr, dict):
            curr = curr.get(step)
        elif isinstance(step, int) and isinstance(curr, list) and 0 <= step < len(curr):
            curr = curr[step]
        else:
            return None
    return curr


def resolve_pointer(doc: dict | list | None, pointer: str):
    """Resolve a JSON Pointer fragment (e.g. '#/$defs/Name' or '/properties/foo') within `doc`."""
    if doc is None:
        return None
    if pointer.startswith("#"):
        pointer = pointer[1:]
    if not pointer:
        return doc
    parts = pointer.split("/")
    if parts[0] == "":
        parts = parts[1:]
    curr = doc
    for part in parts:
        part = unquote(part).replace("~1", "/").replace("~0", "~")
        if isinstance(curr, dict):
            curr = curr.get(part)
        elif isinstance(curr, list) and part.isdigit():
            idx = int(part)
            if idx < len(curr):
                curr = curr[idx]
            else:
                return None
        else:
            return None
        if curr is None:
            return None
    return curr


def in_definition_context(node: dict, owner: dict | None) -> dict:
    """`node` as a document of its own that can still reach `owner`'s local definitions.

    A subschema handed to `Draft202012Validator` becomes the root of its own document, and a
    `$ref: "#/$defs/..."` inside it then resolves against itself — where `$defs` is not. The
    resolver raises `Unresolvable`, the caller reads that as "the condition does not hold", and a
    branch the schema really does evaluate is reported as a foreign property. Measured: an
    instance satisfying its own `if` matched `False`, and the `then` branch's field came back as
    unexpected.

    Carrying the definition pools across is also what keeps a RECURSIVE condition working —
    `#/$defs/Node` resolves inside the probe, hop after hop — which inlining the reference would
    have needed a second cycle guard to survive.

    No `$id` is carried. The location `located()` supplies is a file URI, and putting it on a
    fragment of that file would send every relative `$ref` inside the fragment off to join a URL
    the tree knows nothing about: the exact redirection `located()`'s own docstring refuses.
    """
    if not isinstance(node, dict) or not isinstance(owner, dict) or node is owner:
        return node
    carried = {}
    for pool in ("$defs", "definitions"):
        theirs = owner.get(pool)
        if isinstance(theirs, dict):
            merged = dict(theirs)
            mine = node.get(pool)
            if isinstance(mine, dict):
                merged.update(mine)          # the node's own definitions win over the owner's
            carried[pool] = merged
    return {**node, **carried} if carried else node


def check_if_match(
    if_node: dict,
    inst_node,
    base_dir: str = "",
    root_schema: dict | None = None,
    registry=None,
) -> bool:
    """Whether `inst_node` satisfies the subschema `if_node`.

    Asked of an `if`, and of a `oneOf` / `anyOf` branch: it is one question, because only a
    subschema that HOLDS contributes annotations at this location, and that is the whole of what
    the caller needs to know. An absent instance value satisfies nothing — there is no value to
    condition on, and `None` here is also how a missing member arrives.
    """
    if inst_node is None:
        return False
    defs_owner = root_schema
    if "$ref" in if_node and isinstance(if_node["$ref"], str):
        ref = if_node["$ref"]
        if ref.startswith("#") and root_schema:
            resolved = resolve_pointer(root_schema, ref)
            if isinstance(resolved, dict):
                if_node = resolved
        elif not ref.startswith("#") and base_dir:
            file_part, fragment = ref.split("#", 1) if "#" in ref else (ref, "")
            if file_part.startswith("file:"):
                target = unquote(urlparse(file_part).path)
            elif os.path.isabs(file_part):
                target = file_part
            else:
                target = os.path.normpath(os.path.join(base_dir, file_part))
            try:
                target = within_repo(target, f"if $ref '{ref}'")
                if target and os.path.exists(target):
                    with open(target, encoding="utf-8") as fh:
                        ext_doc = json.load(fh)
                    resolved = resolve_pointer(ext_doc, "#" + fragment) if fragment else ext_doc
                    if isinstance(resolved, dict):
                        if_node = resolved
                        # The definitions this node's own pointers mean are ITS file's, not the
                        # composition's. Keeping the composed root here sent `#/$defs/x` inside
                        # an externally-referenced condition looking in the wrong document.
                        defs_owner = ext_doc
            except PathEscapeError:
                return False
    try:
        import jsonschema
        # `registry=None` is not "no registry": it replaces jsonschema's own default with None,
        # and the validator raises `AttributeError` reaching for `_resources` on first use. The
        # `except` below would then answer "the condition does not hold" — the defect this
        # function was just fixed for, arriving through a different door and silent, because the
        # catch is what answered. Everything inside `validate()` carries a real registry; the
        # callers that do not are this function's own signature, where `registry` defaults, and
        # `find_declared_props()`, which five cases in the regression suite call positionally.
        carried = {"registry": registry} if registry is not None else {}
        validator = jsonschema.Draft202012Validator(
            in_definition_context(if_node, defs_owner), **carried)
        return bool(validator.is_valid(inst_node))
    except ImportError:
        pass
    except Exception:
        # A reference that goes nowhere even in context, or a schema this validator refuses:
        # fail-closed. No longer the ordinary nested `$ref`, which now resolves.
        return False

    # Fallback only when jsonschema is not installed
    if "$ref" in if_node or not isinstance(inst_node, dict):
        return False
    if_props = if_node.get("properties") or {}
    for k, v in if_props.items():
        if k not in inst_node:
            # `properties` constrains the members that are THERE. Asking `inst_node.get(k)` for an
            # absent one compared `None` against the condition's `const`, `enum` and `type`, so a
            # condition naming any optional property could not be satisfied at all — measured:
            # `{"env": "prod"}` failed `{"env": {"const": "prod"}, "note": {"type": "string"}}`.
            # `required` below is the keyword that makes presence mandatory, and it is its own
            # check.
            continue
        if isinstance(v, dict):
            if "const" in v and inst_node.get(k) != v["const"]:
                return False
            if "enum" in v and inst_node.get(k) not in v["enum"]:
                return False
            if "type" in v:
                t = v["type"]
                val = inst_node.get(k)
                if t == "string" and not isinstance(val, str):
                    return False
                if t == "integer" and (isinstance(val, bool) or not isinstance(val, int)):
                    return False
                if t == "number" and (isinstance(val, bool) or not isinstance(val, (int, float))):
                    return False
                if t == "boolean" and not isinstance(val, bool):
                    return False
                if t == "array" and not isinstance(val, list):
                    return False
                if t == "object" and not isinstance(val, dict):
                    return False
    req = if_node.get("required") or []
    if any(k not in inst_node for k in req):
        return False
    return True


def inplace_branches(
    schema_node: dict,
    inst_node,
    base_dir: str,
    root_schema: dict | None,
    registry=None,
) -> list[dict]:
    """The in-place applicators at this node that account for what THIS instance evaluated.

    One list, read by both folds below. The two were the same walk with a different fold, and
    the fold that was missing is the one that never got written.
    """
    out: list[dict] = []
    for b in (schema_node.get("allOf") or []):
        if isinstance(b, dict):
            # Every branch, the failing ones included: a failing branch is precisely the case
            # this filter exists for. It declared the property, and its failure is what dropped
            # the annotation that said so.
            out.append(b)
    for key, dep in (schema_node.get("dependentSchemas") or {}).items():
        if isinstance(dep, dict) and isinstance(inst_node, dict) and key in inst_node:
            out.append(dep)
    if_node = schema_node.get("if")
    if isinstance(if_node, dict) and inst_node is not None:
        # `then` / `else` without `if` have no effect (Draft 2020-12), so they are reached only
        # from here.
        if check_if_match(if_node, inst_node, base_dir, root_schema, registry=registry):
            # The condition held, so the `if` subschema's OWN properties were evaluated too —
            # jsonschema credits them beside `then`'s. A schema naming a property only in its
            # condition was being reported as if it had never declared it.
            out.append(if_node)
            then_b = schema_node.get("then")
            if isinstance(then_b, dict):
                out.append(then_b)
        else:
            else_b = schema_node.get("else")
            if isinstance(else_b, dict):
                out.append(else_b)
    for key in ("oneOf", "anyOf"):
        alts = [b for b in (schema_node.get(key) or []) if isinstance(b, dict)]
        if not alts:
            continue
        holds = [b for b in alts
                 if check_if_match(b, inst_node, base_dir, root_schema, registry=registry)]
        # The branch that holds, which is exactly the one the validator counts. When NONE holds,
        # the union: the union's own failure is already reported on this path, so trimming its
        # secondary line cannot turn a failing case green — while leaving the union out reports
        # every field of the variant the answer was reaching for as a foreign property. Measured
        # on a discriminated `oneOf`: `'a_field' was unexpected`, beside the real failure.
        out.extend(holds or alts)
    return out


def nodes_at(
    schema_node: dict | None,
    path: tuple,
    base_dir: str,
    root_schema: dict | None = None,
    stack: tuple = (),
    inst_node=None,
    registry=None,
) -> list[dict]:
    """Every schema node that applies to the instance location `path`.

    Expanded through `$ref`, `$dynamicRef` and `inplace_branches()`, then down one instance step
    at a time. What each node then contributes is the caller's business — property names for
    `find_declared_props()`, item positions for `find_declared_item_indexes()`.

    `stack` breaks reference cycles WITHIN one step of the path, and is reset at every step the
    path advances. It has to be: carried down the descent, a recursive schema —
    `child: {"$ref": "#/$defs/Node"}` — looked like a cycle the moment its own root had been
    expanded, so every property below the first hop was collected as declared by nobody and
    reported as unexpected. Measured: `declared at ('child',)` was empty for a `Node` that
    declares three. The walk still terminates, because `path` strictly shrinks.
    """
    if not isinstance(schema_node, dict):
        return []
    if root_schema is None:
        root_schema = schema_node
    found: list[dict] = []

    for ref_key in ("$ref", "$dynamicRef"):
        ref = schema_node.get(ref_key)
        if not isinstance(ref, str):
            continue
        if "#" in ref:
            file_part, fragment = ref.split("#", 1)
            fragment = "#" + fragment
        else:
            file_part, fragment = ref, ""

        if not file_part:
            ref_id = (id(root_schema), fragment)
            if ref_id not in stack:
                target_sch = resolve_pointer(root_schema, fragment)
                if isinstance(target_sch, dict):
                    found += nodes_at(target_sch, path, base_dir, root_schema,
                                      stack + (ref_id,), inst_node, registry)
            continue

        if file_part.startswith("file:"):
            target = unquote(urlparse(file_part).path)
        elif os.path.isabs(file_part):
            target = file_part
        else:
            target = os.path.normpath(os.path.join(base_dir, file_part))
        try:
            target = within_repo(target, f"{ref_key} '{ref}'")
        except PathEscapeError:
            target = None

        if target and os.path.exists(target):
            ref_id = (target, fragment)
            if ref_id not in stack:
                try:
                    with open(target, encoding="utf-8") as fh:
                        ext_doc = json.load(fh)
                    target_sch = resolve_pointer(ext_doc, fragment) if fragment else ext_doc
                    if isinstance(target_sch, dict):
                        found += nodes_at(target_sch, path, os.path.dirname(target), ext_doc,
                                          stack + (ref_id,), inst_node, registry)
                except Exception:
                    pass

    for b in inplace_branches(schema_node, inst_node, base_dir, root_schema, registry):
        found += nodes_at(b, path, base_dir, root_schema, stack, inst_node, registry)

    if not path:
        found.append(schema_node)
        return found

    step, rest = path[0], path[1:]
    if isinstance(step, str):
        next_inst = inst_node.get(step) if isinstance(inst_node, dict) else None
        sub = (schema_node.get("properties") or {}).get(step)
        if isinstance(sub, dict):
            found += nodes_at(sub, rest, base_dir, root_schema, (), next_inst, registry)
        for pat, pat_sch in (schema_node.get("patternProperties") or {}).items():
            try:
                if re.search(pat, step) and isinstance(pat_sch, dict):
                    found += nodes_at(pat_sch, rest, base_dir, root_schema, (), next_inst, registry)
            except re.error:
                pass
    elif isinstance(step, int):
        next_inst = (inst_node[step] if isinstance(inst_node, list) and 0 <= step < len(inst_node)
                     else None)
        items = schema_node.get("items")
        if isinstance(items, dict):
            found += nodes_at(items, rest, base_dir, root_schema, (), next_inst, registry)
        prefix = schema_node.get("prefixItems") or []
        if isinstance(prefix, list) and step < len(prefix) and isinstance(prefix[step], dict):
            found += nodes_at(prefix[step], rest, base_dir, root_schema, (), next_inst, registry)
    return found


def find_declared_props(
    schema_node: dict | None,
    path: tuple,
    base_dir: str,
    root_schema: dict | None = None,
    stack: tuple = (),
    inst_node=None,
    registry=None,
) -> set[str]:
    """Property names this schema declares at `path` — through `$ref` and `$dynamicRef`, every
    `allOf` branch, the active conditional and dependent branches, the polymorphic branch that
    holds, and the `patternProperties` this instance's own keys match."""
    here = instance_at(inst_node, path)
    props: set[str] = set()
    for node in nodes_at(schema_node, path, base_dir, root_schema, stack, inst_node, registry):
        props.update((node.get("properties") or {}).keys())
        if not isinstance(here, dict):
            continue
        for pat in (node.get("patternProperties") or {}):
            for k in here:
                try:
                    if re.search(pat, k):
                        props.add(k)
                except re.error:
                    pass
    return props


def find_declared_item_indexes(
    schema_node: dict | None,
    path: tuple,
    base_dir: str,
    root_schema: dict | None = None,
    stack: tuple = (),
    inst_node=None,
    registry=None,
) -> set[int] | None:
    """Item positions this schema accounts for at `path`; `None` means every position.

    The array-side twin of `find_declared_props()`, over the same walk: `items` accounts for the
    whole array, `prefixItems` for its own length, `contains` for the positions that satisfy it.
    That is how jsonschema recomputes what an array location evaluated, and the reason this has
    to be positions rather than names is `unevaluatedItems`' message, which carries neither.
    """
    here = instance_at(inst_node, path)
    covered: set[int] = set()
    for node in nodes_at(schema_node, path, base_dir, root_schema, stack, inst_node, registry):
        if "items" in node:
            return None
        prefix = node.get("prefixItems")
        if isinstance(prefix, list):
            covered.update(range(len(prefix)))
        contains = node.get("contains")
        if isinstance(contains, dict) and isinstance(here, list):
            covered.update(i for i, v in enumerate(here)
                           if check_if_match(contains, v, base_dir, root_schema,
                                             registry=registry))
    return covered


def artefact(error, errors) -> bool:
    """Whether an `unevaluated*` error is the annotation artefact `BUNDLE.md` tells readers to
    skip: a subschema at or below this location failed, contributed no annotations, and the
    closer above it reported everything the instance carries.

    The same rule as `artefact()` in `tools/agents_file_check.py`, and deliberately the same
    shape. Neither can import the other — this file ships inside the vendored bundle and the
    checker does not ship at all — so the least the two owe a reader is to agree, and to say
    where the other one is.

    Scoped to the path, and by prefix: a failing in-place applicator reports from its own
    location or deeper, and nothing above it changes what this location evaluated, because the
    annotations are recomputed here. The document-wide reading this replaces let one error at the
    root switch the filter on inside every other object — which cannot green a failing case,
    since the error that enables it is itself reported, but can drop the one line that was the
    answer, and the comment above it claimed the scope the code did not have.

    `other is not error`, rather than "not an `unevaluated*` error": a closer firing one level
    down IS a cause here, because it fails the branch that carries it and that branch is what
    would have annotated this location. What no error may do is justify itself.
    """
    if error.validator not in ("unevaluatedProperties", "unevaluatedItems"):
        return False
    path = tuple(error.absolute_path)
    return any(other is not error and tuple(other.absolute_path)[:len(path)] == path
               for other in errors)


def validate(instance, schema_path: str) -> list[str]:
    """Schema conformance. Falls back to a shallow required-keys check when jsonschema is absent,
    and says which it did — a grader that silently weakens is worse than one that is missing."""
    name = os.path.basename(schema_path)
    # Read first, and guarded: an unparseable composed schema raised out of the grader and ended
    # the run, which is the class three entries of this release's `### Fixed` are about. `main()`
    # checks that the file exists and never that it parses.
    try:
        with open(schema_path, encoding="utf-8") as fh:
            schema = json.load(fh)
    except Exception as exc:
        return [f"cannot validate {name}: the schema itself is not readable JSON ({exc})"]
    if not isinstance(schema, dict):
        # `true` and `false` are legal JSON Schema, and an array is not but parses. The fallback
        # below asks the document for `.get`, which is an AttributeError out of the grader and one
        # more way to end a run — the same class as the three above.
        return [f"cannot validate {name}: the schema is {type(schema).__name__}, not an object, so "
                f"there is nothing here to validate against"]
    try:
        import jsonschema
    except ImportError:
        # A composed schema declares its `required` inside the `allOf` branches, not at the top
        # level — the base's branch is a `$ref` this fallback cannot follow, but a repository's own
        # branch is inline and readable. Collect what IS reachable before giving up.
        required = list(schema.get("required") or [])
        for branch in schema.get("allOf") or []:
            if isinstance(branch, dict):
                required += list(branch.get("required") or [])
        if not required:
            # The shallow check has nothing to check. Every schema written the way rule 13's
            # composition prescribes — an `allOf` of a `$ref` into the vendored base plus the
            # repository's enums — carries no top-level `required`, so this branch validated ZERO
            # fields and returned "valid". That is a grader silently weakening to nothing, which
            # this function's own docstring says is worse than one that is missing. Say so instead.
            return [f"cannot validate {name}: jsonschema is not installed and the schema "
                    f"declares no top-level `required` to fall back on (pip install jsonschema)"]
        missing = [k for k in required if k not in (instance or {})]
        return [f"missing required key '{k}' (shallow check: jsonschema not installed)"
                for k in missing]
    # No silent fallback here. Rebuilding the validator without the registry and without the
    # location `$id` drops the two things that make a vendored `$ref` resolve at all, and the
    # grader that comes back checks a fraction of the contract while reporting like the whole one.
    # Each half says which one is missing instead.
    try:
        registry = file_registry(schema_path)
    except ImportError:
        return [f"cannot validate {name}: `referencing` is not installed, so a `$ref` into the "
                f"vendored bundle cannot be resolved and the grader would be checking the "
                f"repository's own keywords and nothing the base carries "
                f"(pip install jsonschema referencing)"]
    try:
        v = jsonschema.Draft202012Validator(located(schema, schema_path), registry=registry)
    except TypeError as exc:
        return [f"cannot validate {name}: this jsonschema does not take a reference registry "
                f"({exc}); 4.18 and newer do, and without one a vendored `$ref` resolves to "
                f"nothing"]
    # Whatever goes wrong here belongs to the case that named this schema. Raised, it leaves
    # `grade()` and `run()`, neither of which catches anything, and ends the whole run — every
    # later case unreported over one bad path. The same reason `build_prompt`'s missing fixture is
    # recorded rather than thrown. Three outcomes, because a grader that misnames what went wrong
    # sends its reader to the wrong file.
    try:
        errors = list(v.iter_errors(instance))
        base_dir = os.path.dirname(os.path.abspath(schema_path))
        result = []
        for e in errors:
            path = tuple(e.path)
            # Both keywords, because both are secondary in the same way and only the properties
            # one was ever cleaned.
            if artefact(e, errors):
                if e.validator == "unevaluatedProperties":
                    m = UNEXPECTED_RE.match(e.message)
                    if m:
                        raw = m.group(1) or m.group(2)
                        unexpected = re.findall(r"'([^']*)'", raw) or [p.strip().strip("'\"") for p in raw.split(",")]
                        declared = find_declared_props(schema, path, base_dir, inst_node=instance, registry=registry)
                        genuine = [p for p in unexpected if p not in declared]
                        if not genuine:
                            # Pure annotation artefact from a failed allOf/ref branch: drop it
                            continue
                        if len(genuine) == 1:
                            msg = f"Unevaluated properties are not allowed ('{genuine[0]}' was unexpected)"
                        else:
                            quoted = ", ".join(f"'{g}'" for g in sorted(genuine))
                            msg = f"Unevaluated properties are not allowed ({quoted} were unexpected)"
                        result.append((path, msg))
                        continue
                elif UNEVALUATED_ITEMS_RE.match(e.message):
                    # Whole line or nothing. The properties message can be rebuilt around the
                    # names that are genuine; this one names values, so there is no position to
                    # subtract — two equal items are one string in it. Drop it only where the
                    # schema accounts for EVERY position, and otherwise report it as it stands.
                    covered = find_declared_item_indexes(schema, path, base_dir,
                                                         inst_node=instance, registry=registry)
                    here = instance_at(instance, path)
                    if covered is None or (isinstance(here, list)
                                           and covered >= set(range(len(here)))):
                        continue
            result.append((path, e.message))
        return [f"{'/'.join(str(p) for p in path) or '<root>'}: {msg}" for path, msg in result]
    except Exception as exc:
        escape_cause = exc if isinstance(exc, PathEscapeError) else None
        curr = exc
        while not escape_cause and curr:
            curr = getattr(curr, "_wrapped", None) or getattr(curr, "__cause__", None) or getattr(curr, "__context__", None)
            if isinstance(curr, PathEscapeError):
                escape_cause = curr
        if escape_cause:
            return [f"cannot validate {name}: a `$ref` resolved outside the repository and was refused "
                    f"({escape_cause}). Paths are repository-relative by design"]
        if isinstance(exc, unresolvable()):
            return [f"cannot validate {name}: a `$ref` did not resolve ({exc}). A vendored base is "
                    f"a file on disk, so this is a path that is not there"]
        return [f"cannot validate {name}: the grader failed on this instance "
                f"({type(exc).__name__}: {exc})"]


def unresolvable() -> tuple:
    """The exception classes that mean a reference went nowhere.

    jsonschema wraps referencing's `Unresolvable` and subclasses it, so one class covers both; the
    empty tuple keeps the caller honest where `referencing` is not importable at all.
    """
    try:
        from referencing.exceptions import Unresolvable
        return (Unresolvable,)
    except ImportError:
        return ()


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


def vendored(path: str) -> bool:
    """True for a schema inside `.agents/vendor/`, which makes it the bundle's and not this
    repository's."""
    return f"{os.sep}.agents{os.sep}vendor{os.sep}" in os.path.realpath(path) + os.sep


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
        raise PathEscapeError(f"eval-run: {what} resolves outside the repository ({resolved}); "
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
    ap.add_argument("--show-command", action="store_true",
                    help="with --dry-run, print the argument vector each case would run. "
                         "The only way to inspect it without spending a turn.")
    a = ap.parse_args()

    # Guard FIRST. This used to sit seven lines lower, after load_yaml() had already opened and
    # parsed the file — so the one CLI-controlled read the guard exists for still happened, and a
    # path outside the checkout produced a YAML parse error or a raw FileNotFoundError rather than
    # the refusal. A guard that runs after the sink is a comment.
    try:
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
    except PathEscapeError as exc:
        sys.exit(str(exc))

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
        try:
            schema_path = within_repo(os.path.join(schema_dir, named),
                                      f"case '{case['id']}' expect.schema")
        except PathEscapeError as exc:
            entry |= {"status": "error", "failures": [f"expect.schema resolves outside repository: {named} ({exc})"]}
            results.append(entry); failed += 1
            print(f"ERROR {case['id']}: expect.schema resolves outside repository"); continue
        # F5: a missing fixture is recorded like a missing schema. It used to raise out of
        # build_prompt and abort the whole run, so one typo in one case hid every later result.
        try:
            prompt = build_prompt(case, fixture_dir)
        except PathEscapeError as exc:
            entry |= {"status": "error", "failures": [f"fixture resolves outside repository: {case.get('fixture')} ({exc})"]}
            results.append(entry); failed += 1
            print(f"ERROR {case['id']}: fixture resolves outside repository"); continue
        except OSError as exc:
            entry |= {"status": "error", "failures": [f"fixture not readable: {exc}"]}
            results.append(entry); failed += 1
            print(f"ERROR {case['id']}: fixture not readable"); continue

        if not os.path.exists(schema_path):
            entry |= {"status": "error", "failures": [f"schema not found: {schema_path}"]}
            results.append(entry); failed += 1
            print(f"ERROR {case['id']}: schema not found"); continue
        # After the existence check, not before it: a path under `.agents/vendor/` that is not
        # there is a typo, and telling its author to name the composed schema instead sends them
        # to fix the wrong thing.
        if vendored(schema_path):
            entry |= {"status": "error", "failures": [
                f"expect.schema names a bundle base ({named}). A base fixes the shape and leaves "
                f"the vocabulary and every closer to the repository, so from 2.0.0 it accepts a "
                f"foreign property anywhere and any value the repository's own enums exclude — a "
                f"case graded against one passes on answers the repository refuses. Name the "
                f"composed schema in .agents/schemas/ instead."]}
            results.append(entry); failed += 1
            print(f"ERROR {case['id']}: expect.schema names a bundle base"); continue
        if a.dry_run:
            entry["status"] = "resolved"
            results.append(entry)
            print(f"ok    {case['id']} -> {case.get('agent')} / {os.path.basename(schema_path)}")
            if a.show_command:
                cmd, _ = RUNTIMES[runtime or "claude"](case["agent"], schema_path, prompt)
                print("      " + " ".join(shlex.quote(c) for c in cmd[:4]) + " …")
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
