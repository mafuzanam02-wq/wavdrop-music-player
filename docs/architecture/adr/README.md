# Architecture Decision Records

This directory is the durable history of WavDrop's significant architecture decisions: what was decided, why,
which alternatives were rejected or deferred, and what evidence would justify revisiting the decision.

## Conventions

- An ADR records a durable architecture decision and its reasoning.
- An accepted ADR is a historical record. It is not silently rewritten when the architecture changes.
- A later decision that replaces an ADR does so through a new ADR that supersedes it. The old ADR keeps its
  text and only has its status line updated to point at the superseding ADR.
- ADRs are not a competing source of truth for the current state. `../../ARCHITECTURE.md` describes the
  current architecture; an ADR explains why a durable choice was made. See `../../DOCUMENTATION_POLICY.md`.
- File naming: `ADR-NNN-short-title.md`.

## Status values

- Proposed
- Accepted
- Superseded
- Rejected

## Index

| ADR | Title | Status |
|---|---|---|
| [ADR-001](ADR-001-wdbk-backup-container.md) | WDBK Backup Container and Compression Architecture | Accepted - implementation pending |
