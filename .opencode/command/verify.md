---
description: Compile Java code, test endpoints, and verify hackathon rules
agent: build
---

Run a release-gate audit of **CommishGuard AI**. Report only evidence you actually collected.

## 1. Build
```
./gradlew clean build --console=plain | tail -n 40
```
Record pass/fail. If it fails, show the first real compiler/test error, not the whole log.

## 2. Repo state
```
git status --short
git diff --stat
```
Flag: untracked `.env`, `storage/`, `build/`, or anything that looks like a credential.

## 3. Secret scan
Search tracked files for:
- `PAYPAL_CLIENT_SECRET=` with a non-empty value
- `sk-`, `AIza`, `ghp_`, `xoxb-` token prefixes
- long base64/hex blobs in `.java`, `.json`, `.md`
Confirm `.env` is git-ignored and only `.env.example` is committed.

## 4. Compliance
- `LICENSE` exists at repo root and is the MIT text with a copyright line.
- `README.md` exists and contains: what it is, setup, env table, run command, PayPal flow, AG Grid callout, demo script.
- `build.gradle` declares Java 21, Javalin `6.3.0`, Gson `2.11.0`, `com.paypal.sdk:paypal-server-sdk:0.6.1`.
- `PayPalService` uses `Environment.SANDBOX` and `CheckoutPaymentIntent.AUTHORIZE`; no production URL is hardcoded.
- `index.html` actually loads AG Grid and creates a grid; not a static table.
- AI parsing is called by a real route (`/api/contracts/parse`), not dead code.

## 5. Smoke test
Start the app in the background (`./gradlew run --console=plain --quiet`), wait for port 7070, then:
```
curl -s http://localhost:7070/api/health
curl -s -X POST http://localhost:7070/api/contracts/parse -H "Content-Type: application/json" -d "{\"chat\":\"Need a 4k render for $150 in .png by Friday\"}"
```
Stop the server afterward. Confirm both return JSON and health reports paypal mode.

## 6. Verdict
Output a table: `Check | Result | Evidence`. End with `SHIP` or `BLOCK` plus the shortest list of fixes. Do not edit files in this command — report only.
