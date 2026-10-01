# Documentation Policy

Short, operational rules for keeping repository documentation correct.

## Rule: reconcile docs inside every implementation slice

Before a slice is closed (before commit), check whether it changed any of:

- architecture
- current project state
- feature status
- backlog
- technical debt
- release notes
- QA expectations
- data / import contracts
- Play Store claims
- build / version facts

Update **only the affected documents**, in the **same patch** as the implementation that made the change
necessary (so a reviewer sees code and docs together). Do not postpone ordinary synchronization into large
cleanup batches.

## Source-of-truth order

1. Code and current committed tests - current implementation state.
2. Git history - completed engineering slices.
3. Existing docs - product intent, historical reasoning, and durable decisions only.

Never update one stale document by copying another stale document. If code and docs disagree, code wins;
fix the doc.

## Canonical-source map

| Document | Job |
|---|---|
| `README.md` | public/developer entry point |
| `PROJECT_CONTEXT.md` | current repository/project state |
| `docs/ARCHITECTURE.md` | current technical architecture |
| `ENGINEERING_BACKLOG_AND_DECISIONS.md` | durable decisions + engineering backlog |
| `TECHNICAL_DEBT_REGISTER.md` | accepted technical debt |
| `PLANNED.md` | product / future work |
| `RELEASE_NOTES.md` | historical shipped/change record |
| `WHATS_NEW.md` | current user-facing release summary |
| `QA_CHECKLIST.md` | current manual / physical QA |
| `docs/WAVDROP_DATA_FORMAT_SPEC.md` | canonical data interchange contract |
| `docs/WAVDROP_BACKUP_SCHEMA_V1.md` | legacy V1 compatibility contract |
| `docs/WAVDROP_IMPORT_RULES.md` | import semantics |
| `docs/BACKUP_PRESERVATION_CONTRACT.md` | preservation semantics |
| `docs/BRANDING.md`, `docs/RELEASE_SIGNING.md` | branding facts; release-signing procedure |
| `PLAY_STORE_LISTING_DRAFT.md`, `PLAY_STORE_READINESS_CHECKLIST.md` | store claims and readiness |

**No competing sources of truth.**

Each topic has one authoritative document for its full definition. Other documents may repeat concise,
context-appropriate summaries when useful, but those summaries must stay consistent with the authoritative
source and link to it when deeper detail is needed.

Examples:

- `docs/ARCHITECTURE.md` is the authoritative technical architecture; `PROJECT_CONTEXT.md` is the concise
  current-state summary of it.
- `ENGINEERING_BACKLOG_AND_DECISIONS.md` is the authoritative remaining engineering work and durable
  decisions; `PLANNED.md` is the concise product-level future-work view.
- `docs/WAVDROP_DATA_FORMAT_SPEC.md` is the authoritative interchange format.
- `docs/BACKUP_PRESERVATION_CONTRACT.md` is the authoritative preservation semantics.

When a summary and its authority disagree, fix the summary.

## Keeping things from going stale

- If information becomes obsolete: **update it, merge it, or delete it.** Do not leave abandoned documents
  with stale "current" claims.
- Do not record volatile numbers (test totals, library versions) in long-lived docs. Say "run the complete
  JVM suite" and point to `gradle/libs.versions.toml`.
- Avoid exact commit SHAs in long-lived documentation. Use named engineering checkpoints/slices (for example
  "after CF-2C3") when useful. `PROJECT_CONTEXT.md` may record an implementation baseline, but it must
  distinguish that baseline from the repository's current HEAD when a docs-only commit follows it.
- Do not describe a planned feature as shipped. Engineering foundations that are gated or unwired (for
  example crossfade) are labelled as such in every document that mentions them, and never appear in
  user-facing copy (`WHATS_NEW.md`, store listing).
- Historical release notes keep the facts of their time; current-state documents must never present
  obsolete facts as current.
- Debt entries change status in place (ACTIVE / MONITOR / RESOLVED / SUPERSEDED); resolved items keep their
  original reasoning.
- Use plain Markdown, ASCII-safe headings and separators, and no secrets or personal machine paths.

## When you delete or rename a document

Search the whole repository for the old name and update every link in the same change.
