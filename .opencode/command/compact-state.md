---
description: Save current session progress to TODO.md so I can safely reset the 200K context window
agent: build
---

Perform a **context checkpoint**. The goal: a brand-new session with zero chat history can resume in under a minute.

1. Read `TODO.md`, `ARCHITECTURE.md`, and `git status --short` (quiet).
2. Rewrite **only** the `## Current Session State` section at the top of `TODO.md`, preserving everything below it. Use exactly this shape:

```markdown
## Current Session State

**Last updated:** <YYYY-MM-DD HH:mm> (<phase>)

**Status:** <one sentence: what works right now>

**Architecture in one line:** <current, accurate>

**Next 3 actions:**
1. <concrete, file-level action>
2. <concrete, file-level action>
3. <concrete, file-level action>

**Active bugs / risks:**
- <file:line — symptom — suspected cause> (or "None")

**Recent decisions:**
- <decision + why, max 5 bullets>

**Do not redo:**
- <things already verified working, so the next agent doesn't waste tokens>
```

3. Check off any `[ ]` items that are demonstrably complete (code exists **and** compiles). Do not check speculative work.
4. Add newly discovered work as new `- [ ]` items under the correct phase.
5. Report a **≤ 150 word** handoff summary in chat, ending with the exact next command to run.

Rules:
- No secrets, tokens, or client ids in `TODO.md`.
- Be specific: file paths, class names, method names — not "continue work".
- Keep the whole section under 40 lines so it stays cheap to reload.
