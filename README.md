# Slippery Android

Native Android buyer app — receipt capture, category entry, local-first draft
with explicit Submit-to-sync. Matches the Handy Andy capture pattern.

**This repo owns no docs.** All docs for this app live in
`slippery-dashboard/docs/android/` — slippery-dashboard is the owner repo,
this repo is a consumer. Read `../slippery-dashboard/docs/SESSION-PROMPT.md`
at the start of every session, same as the dashboard repo.

## Quick pointers
| Doc (in slippery-dashboard) | Purpose |
|---|---|
| `docs/android/STATE.md` | This app's current build state |
| `docs/android/REFERENCE.md` | Laws, known traps, file structure |
| `docs/android/OPERATIONS.md` | Build/git procedures |
| `docs/shared/SCHEMA.md` | DB rules (schema owned by dashboard repo) |
| `docs/shared/TRANSACTIONS.md` | Transaction/approval domain design |

## Stack
- Native Android (Kotlin), Room for local-first offline storage
- Supabase client — same project as the dashboard, per-show
- Cloudflare R2 — receipt photo upload via dashboard repo's presign worker
- iOS: deferred until an iPhone-using buyer exists (TestFlight bridge then)
