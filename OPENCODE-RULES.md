# OpenCode-only rules
> This file is read by opencode ONLY (via opencode.json's "instructions" field).
> Claude/Cascade do not read this file — it exists specifically to give
> opencode a narrower, stricter scope than the general AGENTS.md, because
> opencode executes directly with no "propose a prompt, wait for go-ahead"
> step in between.

## Never touch these files, ever, unless the user's prompt explicitly names the exact filename

- Any STATE.md, SESSION-LOG.md, FEATURES.md, SCHEMA.md, TRANSACTIONS.md,
  SESSION-PROMPT.md, or AGENTS.md, in this repo or in the linked
  slippery-dashboard repo
- Any file under docs/

These files are maintained by Claude as part of a specific documented
process (session logging, doc-sync rules). Editing them outside that
process reintroduces documentation drift.

## Permission behavior

- Do NOT use --auto or any auto-approve flag by default. Ask before
  writing or editing any file, every time, unless the user explicitly
  says otherwise for that specific run.
- One change per prompt — don't bundle unrelated edits into a single run.

## Git discipline

- All app code changes go through a review/ branch, never directly to
  main.
- Never commit or merge without being asked to.
- Testing standard: app code must be confirmed working on a physical
  device before merging to main — same as the rest of this project.

## Scope

opencode's job here is implementing scoped, well-defined code changes.
It is not a replacement for the documentation/audit process Claude runs
at session boundaries.
