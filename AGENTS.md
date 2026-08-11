# Rules

KISS. Minimum viable. No abstraction/future-proofing. Data safety = only exception, never cut corners.

Cascade: exact find/replace only. One change per prompt. git diff HEAD~1 after every edit. No worker deploy without real token test. No infra write+deploy same session. No direct commits to main — review/ branch, explicit approval to merge.

Code words:
DOCS = docs-vs-code audit, any time. Re-derive every status claim from git log/grep/file reads, fix drift.
PURGE = session-close repo hygiene. Debug/temp logs, scratch files, orphaned code, dupe versions, stale config (flag secrets, don't auto-remove). Propose deletion list, wait for confirm.
RELOAD = re-read this file + SESSION-PROMPT.md (in slippery-dashboard/docs/), discard conversation assumptions.

Rule discipline: add a new rule here only after the SAME issue occurs twice.
Feature commits: STATE.md update in same commit, sourced from checking the code just written.
Source of truth: this file. Memory (/areas/slippery.md) = pointer only, not a duplicate.
Output: Cascade prompts by default. DC only if explicitly requested. No code/prompts/infra until explicit go-ahead — holds mid-conversation too.
