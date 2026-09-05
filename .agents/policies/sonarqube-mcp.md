---
title: Policy — using the SonarQube MCP server
type: reference
visibility: public
owning-repo: exeris-kernel
status: active
last-verified: 2026-09-05
---

# Policy — using the SonarQube MCP server

Applies whenever the SonarQube MCP server is connected. Connection settings, tokens and permissions
are provider-owned operational configuration and are **not** here; this file is what the session
must do with the server once it is there.

One caution before acting on any result: a finding on `main` and the same finding on a
`development/*` branch are not comparable — SonarCloud treats the development lines as short-lived
branches and reports only changed files against them, and `main` is analysed by a different path
again. A Sonar finding is a question, not a verdict; several of this project's reported blockers
would have broken the code if applied as written.

## Analysis lifecycle

- **Starting a task:** disable automatic analysis with `toggle_automatic_analysis`, if the tool
  exists.
- **Finishing a task:** call `analyze_file_list` on the files created or modified, then re-enable
  automatic analysis with `toggle_automatic_analysis`.
- **After fixing an issue:** do not try to confirm the fix through `search_sonar_issues_in_projects`.
  The server does not reflect the change yet, and a stale result reads as a failed fix.

## Project keys and scope

- When a project key is mentioned, resolve it with `search_my_sonarqube_projects` first. Never guess
  a key.
- Many operations take a branch. When the work is on a feature branch, pass it — the default is not
  the branch you are on.
- Snippet analysis does not replace a project scan, and it infers the language from syntax. Give the
  full file where the answer depends on context.

## Troubleshooting

- `SonarQube answered with Not authorized` usually means the wrong kind of token: the server needs a
  **USER** token, not a project token.
- Project not found: list with `search_my_sonarqube_projects` and check the key's exact spelling.
