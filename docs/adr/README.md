# Architecture Decision Records

An ADR records one structuring decision: the context, what was decided, the alternatives rejected and the consequences.
Later work reads the ADRs instead of guessing why things are the way they are.

## Convention

- One file per decision, named `NNNN-short-title.md` (four digits, lowercase words separated by hyphens).
- Numbers are sequential and never reused. `0000-modele.md` is the template and is not a decision.
- Copy the template, fill every section, set the status. An accepted ADR is not rewritten: a later ADR supersedes it and
  the old one gets the status `Superseded by NNNN`.
- Statuses: `Proposed`, `Accepted`, `Rejected`, `Superseded by NNNN`.

## Index

| Number | Title | Status |
|---|---|---|
| 0001 | Hardening: lossless history, atomic writes, bounds, typed errors | Accepted, partly superseded by 0004 |
| 0002 | Property-based tests and the length of the canonical text | Accepted, partly superseded by 0004 |
| 0003 | Numeric representation: exact rationals and certified intervals | Accepted |
| 0004 | Display rules, canonical text and the exact engine | Accepted |
| 0006 | Interface: display modes, visible exactness, reusable components | Accepted |
| 0007 | Expression language: grammar, errors and evaluation | Accepted |
