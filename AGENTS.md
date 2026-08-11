# Project Rules

## Core principle
**KISS: Keep It Simple, Stupid.** Build the minimum viable thing. No abstractions, no frameworks, no future-proofing for problems we don't have yet. Simplicity beats cleverness every time. Data safety is the only exception — we do not cut corners there.

## Cascade constraints
- No open-ended Cascade prompts — exact find/replace only
- One Cascade change per prompt
- git diff HEAD~1 after every edit (verify what it actually touched)
- Never deploy worker changes without a real token test
- Never write and deploy infrastructure in the same session
- No code changes directly on main — every fix goes through a review/ branch, merged only after explicit approval

## Code words (Claude)
- **DOCS** — run any time. Full docs audit: re-derive every status claim in STATE.md/FEATURES.md from git log/grep/file reads, fix any drift found. Never assert doc status from conversation memory or prior doc wording.
- **PURGE** — session-close habit, not mid-build. Repo hygiene pass: leftover debug/temp logging, scratch files, unused/orphaned code, duplicate versions, stale config (flag committed secrets, don't auto-remove). Always proposes a deletion list first — wait for confirmation before deleting anything.
- **RELOAD** — forces a fresh re-read of this file + SESSION-PROMPT.md (in slippery-dashboard/docs/), discarding any assumption carried from earlier in the conversation. Different job from DOCS (checks docs-vs-code truth) and PURGE (repo cleanup) — this one is purely "stop trusting what you think you know, go read the files again."
- Rule-adding discipline: only add a new standing rule here after the SAME issue occurs twice — first time may be a fluke, don't bloat this file on one-off lessons.
- Every feature commit must include its STATE.md update in the SAME commit, sourced from re-checking the code just written — not batched for later.

## Output mode (Claude)
- **Default output is a Cascade prompt, not direct Desktop Commander edits.** Only use DC directly when the user explicitly says to.
- **Do not code, write prompts, or touch infra until the user explicitly says to proceed.** Ambiguity or an interesting tangent is not a green light — wait for an explicit go-ahead, even mid-conversation.
