# Rules

KISS. Minimum viable. No abstraction/future-proofing. Data safety = only exception, never cut corners.

Cascade: exact find/replace only. One change per prompt. git diff HEAD~1 after every edit. No worker deploy without real token test. No infra write+deploy same session. No direct commits to main — review/ branch, explicit approval to merge.

Code words:
DOCS = docs-vs-code audit, any time. Re-derive every status claim from git log/grep/file reads, fix drift.
PURGE = session-close repo hygiene. Debug/temp logs, scratch files, orphaned code, dupe versions, stale config (flag secrets, don't auto-remove). Propose deletion list, wait for confirm.
RELOAD = call read_file on this file NOW, before responding. Discard conversation assumptions, use only what the fresh read returns.

Session start: check if `opencode serve` is running on port 4096 (`curl -s http://localhost:4096/doc`); if not, start it in the background. In the first response, always include: "Watch/intervene live: `opencode attach http://localhost:4096` (exact `--session <id>` given if work gets delegated)". New session → also briefly restate the opencode Control summary below.

opencode Control (2026-08-15, verified): OPENCODE-RULES.md = soft instruction (opencode-only), lists uneditable files (STATE/SESSION-LOG/FEATURES/SCHEMA/TRANSACTIONS/AGENTS.md, itself, opencode.json) — real but not enforced. `edit:"ask"` + `bash:"ask"` in opencode.json = hard-enforced, confirmed via real tests — this is the actual safety net. Per-path deny patterns = BROKEN (known upstream bug), don't trust them. No Supabase/Cloudflare MCP connected (removed — permission bug made them unsafe); only sequential-thinking remains. Commit/push to review branches, merge to main: fine on instruction, no loop-back needed. Deploy: always manual. Real safety net = Claude's diff review before "done," not the permission config alone.

Rule discipline: add a new rule here only after the SAME issue occurs twice.
Feature commits: STATE.md update in same commit, sourced from checking the code just written.
Source of truth: this file. Memory (/areas/slippery.md) = pointer only, not a duplicate.
Output: Cascade prompts by default. DC only if explicitly requested. No code/prompts/infra until explicit go-ahead — holds mid-conversation too.
