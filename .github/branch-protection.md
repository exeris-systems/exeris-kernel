# Branch Protection Guidance

Target branch: main

Configure repository settings so merge protection matches the in-repo release gate.

## Required Settings

- Require a pull request before merging.
- Require at least 1 approving review.
- Dismiss stale pull request approvals when new commits are pushed.
- Require branches to be up to date before merging.
- Require conversation resolution before merging.

## Required Status Checks

Use these exact check names:

- Build & TCK Verification
- Persistence RLS/Interceptor Gate
- SonarQube New Code Gate

## Informational but Non-Blocking Checks

These are useful for observability and performance review, but they should not be mandatory for routine PR merges because they run on push or schedule only:

- JMH Benchmarks (Community + Core)
- Parse JFR → Lab JSON
- Publish JFR Data → GH Pages
