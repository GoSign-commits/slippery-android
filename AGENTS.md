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
