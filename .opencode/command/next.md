---
description: Execute the next unchecked task in TODO.md, verify compilation, and check it off
agent: build
---

You are executing one unit of work for **CommishGuard AI**.

1. Read `TODO.md` from disk. Do not rely on conversation history.
2. Pick the **first** line matching `- [ ]` (top-to-bottom, phases in order). If `$ARGUMENTS` is non-empty, treat it as a hint to narrow the selection, but do not skip ahead unless the hinted item is already the first unchecked one.
3. State the task in one line, then read only the files needed to implement it.
4. Implement the exact code described by that task. Match existing structure, naming, and style. No placeholder TODOs, no stubs that pretend to work.
5. Verify with:
   ```
   ./gradlew compileJava --console=plain --quiet
   ```
   Then, if the task touches behavior, also run:
   ```
   ./gradlew test --console=plain --quiet
   ```
   Pipe long output through `| tail -n 40` so context stays small.
6. If compilation/tests pass:
   - Change that item to `- [x]` in `TODO.md`.
   - Update the `**Last updated:**` timestamp and `## Current Session State` (status, next 3 actions, active bugs).
   - If a native `todowrite`/task tool is available in this session, sync it to the current phase's remaining items.
7. If it fails:
   - Do **not** check the box.
   - Add a `**BLOCKED:** <task> — <reason>` line under `## Current Session State`.
   - Fix forward if the failure is caused by your change; otherwise stop and report the exact compiler error (trimmed).
8. Finish with a 5-line report: task, files changed, verification command + result, next unchecked item.

Constraints:
- Never commit `.env` or secrets.
- Never mark multiple boxes at once.
- Keep shell output quiet (`--quiet`, `--console=plain`, `tail -n 40`).
