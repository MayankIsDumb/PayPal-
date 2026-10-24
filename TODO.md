# CommishGuard AI — Build Ledger

> **Single source of truth for execution state.** Read this file before every task.
> Update the checkbox, the timestamp, and `## Current Session State` after every task.
> Deadline: **November 12, 2026** — Build What's Next with PayPal and AI Hackathon.

---

## Current Session State

**Last updated:** 2026-10-24 (live authorize/capture handoff verified + final-capture fix)

**Status:** All five phases complete and verified. A reproducible `generateDemoAssets` task now writes two real JARs to `demo-assets/`. The full UI flow was re-run with those exact files: sample chat → validated contract → `AUTHORIZE` order → mock approval → `broken-submission.jar` blocked (4/12 checks) → `verified-motionblur-1.21.jar` verified (12/12) → capture `COMPLETED`, with no browser console errors. The live handoff was verified against SDK 0.6.1: `/api/paypal/return` calls `ordersAuthorize` (`/v2/checkout/orders/{id}/authorize`), extracts `purchase_units[].payments.authorizations[].id`, and capture uses `/v2/payments/authorizations/{id}/capture`. A real SDK default bug was fixed: `CaptureRequest` defaults `final_capture=false`, so the capture body now explicitly sends `final_capture=true` to close the hold. The return and mock-approval endpoints are idempotent on refresh. `AI_API_KEY` is now the preferred parser key (`LLM_API_KEY` remains an alias). A temporary `.env` live wiring check on port 7072 returned `paypalMode=live`, `paypalEnvironment=SANDBOX`, `llmMode=ai` and rendered the muted `Sandbox Live` pill with the model badge; the same switch was confirmed via process environment variables on port 7071. README now embeds all four screenshots, a Mermaid architecture diagram, and a 3-step judge setup for mock and live modes. `./gradlew build` is green; the main server is back in mock/fallback mode on 7070 with 3 seeded rows.

**Architecture in one line:** Javalin 6 REST server (Java 21) that parses deal chat into a strict `Contract` JSON, opens a PayPal Sandbox `AUTHORIZE` order, deterministically verifies uploaded `.jar`/`.zip`/`.png` deliverables in-process, then captures the authorization to release escrow funds.

**Next 3 actions:**
1. Record the 3-minute demo video using the script and shot list in `SUBMISSION.md`.
2. Create the public repository and replace the `<add ... URL>` placeholders in `SUBMISSION.md`.
3. Add real Sandbox credentials and run `paypalSmoke` plus one real approval → capture to record the live path.

**Active bugs / risks:**
- No real Sandbox credentials are available here, so the real PayPal redirect/approval/capture path is implemented and wiring-tested but not executed against Sandbox in this session. Mock mode is clearly labelled.
- AG Grid CSS/JS load from jsDelivr; the dashboard needs network access on first load.
- Automated screenshots use headless Chrome; the desktop Review pane can be occluded by the user's active tab.

**Recent decisions:**
- Demo payloads are generated, not hand-edited: `broken-submission.jar` is a valid archive with no classes and no `fabric.mod.json`; `verified-motionblur-1.21.jar` carries real Fabric metadata (`afkzmotionblur` / `1.2.0`) plus two header-valid `CAFEBABE` class skeletons.
- `AI_API_KEY` is the documented key; `LLM_API_KEY` remains accepted as an alias. The internal no-key warning is suppressed because the `Deterministic` tag already communicates the mode.
- Live mode is selected automatically when both PayPal credentials are present and `PAYPAL_MOCK` is unset. The top bar changes to a subtle outlined `Sandbox Live` pill; the parser badge shows the model.
- The mock checkout page was restyled to match the editorial studio (flat warm off-black surface, serif headline, sage action) so mock-mode recordings stay visually consistent.
- The chat panel collapses after a successful parse, with **Edit deal chat** to restore it; a funded `?funded=<id>` reload re-hydrates the contract and arms upload.
- Ledger persists to `storage/ledger.json`; seeds are labelled `SAMPLE` and reset cleanly for first paint.

---

## Phase 0 — Agentic Workspace ✅

- [x] Create `TODO.md` with phase-by-phase execution plan
- [x] Create `ARCHITECTURE.md` with system design, JSON contracts, failure modes
- [x] Create `.opencode/command/next.md`
- [x] Create `.opencode/command/verify.md`
- [x] Create `.opencode/command/paypal-test.md`
- [x] Create `.opencode/command/compact-state.md`
- [x] Create `.opencode/agent/paypal-auditor.md`
- [x] Create `.opencode/agent/hackathon-judge.md`
- [x] Initialize Gradle wrapper (Gradle 9.8.1, Java 21 toolchain)

---

## Phase 1 — Project & Sponsor Setup

**Goal:** A buildable, licensed, documented Java 21 project that pulls the official PayPal SDK and hosts a static web app.

- [x] `settings.gradle` with `rootProject.name = 'commishguard-ai'`
- [x] `build.gradle`: Java 21 toolchain, Javalin `6.3.0`, Gson `2.11.0`, `com.paypal.sdk:paypal-server-sdk:0.6.1`
- [x] Add SLF4J + `slf4j-simple` so SDK/Javalin logging is quiet and structured
- [x] Add JUnit 5 + `useJUnitPlatform()` test wiring
- [x] `gradle.properties` with quiet console defaults
- [x] Gradle wrapper committed (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`)
- [x] `application` plugin with `mainClass = com.commishguard.App`
- [x] Fat/run tasks: `run` for dev, `shadowJar`-free `installDist` for submission packaging
- [x] `.env.example` documenting every env var (PayPal, LLM, server, limits)
- [x] `.gitignore` (`.env`, `storage/`, `build/`, `.gradle/`, IDE files)
- [x] Root `LICENSE` — MIT, hackathon compliance
- [x] `README.md` — one-command setup, env table, demo script, architecture links
- [x] README screenshot gallery: `studio.png`, `contract.png`, `blocked.png`, `evidence.png` + Mermaid flow
- [x] README 3-step judge setup covering zero-config mock and live Sandbox `.env` modes
- [x] `EnvConfig.java` — typed env loader, `.env` file support, no hardcoded secrets
- [x] `App.java` skeleton with `GET /api/health`
- [x] Verify: `./gradlew compileJava --console=plain` passes

---

## Phase 2 — PayPal Sandbox Escrow Engine (`PayPalService.java`)

**Goal:** Real Sandbox `AUTHORIZE` → payer approval → `capture` lifecycle, with idempotency and mock fallback.

- [x] Inspect resolved SDK 0.6.1 API (`javap`) and document exact builder signatures
- [x] `PayPalService` constructs `PaypalServerSdkClient` with `ClientCredentialsAuthModel`
- [x] Force `Environment.SANDBOX` (never production, assert on startup)
- [x] `createEscrowOrder(Contract)` → `CheckoutPaymentIntent.AUTHORIZE`
- [x] Amount from contract, `USD`, 2-decimal normalization, `custom_id` = commission id
- [x] `application_context`: return/cancel URLs, brand name, `NO_SHIPPING`, `PAY_NOW`
- [x] `PayPal-Request-Id` idempotency header on create
- [x] `authorizeEscrowOrder(orderId)` → `OrdersAuthorizeInput`; `captureEscrowOrder(Commission)` → `authorizationsCapture`, maps `COMPLETED` / `DECLINED` and returns a typed error for already-captured payments
- [x] Return URL calls `ordersAuthorize`, extracts `purchase_units[0].payments.authorizations[0].id`, and persists it
- [x] Capture calls `/v2/payments/authorizations/{authorization_id}/capture` with that persisted id
- [x] Capture request sends `final_capture=true` (the SDK default is false and would leave the hold open)
- [x] Return endpoint is idempotent; a browser refresh reuses the hold instead of authorizing twice
- [x] `authorize` semantics: funds held, capture only after verifier passes
- [x] Extract approval link (`payer-action` / `approve` rel) for the UI
- [x] Typed `PayPalResult` / `PayPalException` mapping (never leak secret or raw stack to UI)
- [x] `MOCK` mode when credentials absent: deterministic fake order id + local approval URL
- [x] Auto-select live `Environment.SANDBOX` when both credentials are present (unless `PAYPAL_MOCK=true`)
- [x] Top-bar `Sandbox Live` pill (outlined, muted) replaces `PayPal mock` in live mode
- [x] `GET /api/paypal/return` + `GET /api/paypal/cancel` browser landing routes
- [x] Unit test: amount normalization + mock create/capture round trip
- [x] Unit test: SDK response mapping for authorization id and order-level capture id
- [x] Unit test: credentials select live Sandbox; absent credentials stay mock
- [x] Live-mode smoke script wired to `.opencode/command/paypal-test.md`

---

## Phase 3 — AI Contract Parser (`AiContractService.java`)

**Goal:** Messy chat → strict, validated `Contract` JSON, with a deterministic fallback.

- [x] `Contract` model: `title`, `priceUSD`, `requiredExtension`, `minResolutionOrEntryCheck`, `checklist`
- [x] `DealParseRequest` / `Contract` DTOs with Gson field names
- [x] Java 21 `HttpClient` (timeout, no redirects) for LLM chat-completions call
- [x] Provider-agnostic: `LLM_BASE_URL`, `LLM_MODEL`, `AI_API_KEY` (preferred; `LLM_API_KEY` alias)
- [x] Unit test: `AI_API_KEY` alias enables the live parser
- [x] System prompt enforcing **JSON only**, no markdown fences, schema locked
- [x] Temperature 0.1, `response_format: json_object` where supported
- [x] Tolerant extraction: strip ```json fences, isolate first `{`…last `}`
- [x] Validation layer: price > 0 and <= 10000, extension whitelist, checklist non-empty, title length clamp
- [x] Deterministic regex fallback parser (`$120`, `120 USD`, `.jar`, `4k`, `1920x1080`)
- [x] Prompt-injection guard: chat text is data, never instructions; output re-validated locally
- [x] `POST /api/contracts/parse` returns `{contract, source: "ai"|"fallback", warnings}`
- [x] Never persist raw chat longer than needed; no secrets in logs
- [x] Unit tests: fallback parsing of messy chats, price/extension edge cases, prompt-injection resistance, fenced-JSON extraction

---

## Phase 4 — Deterministic Deliverable Verifier (`FileVerifier.java`)

**Goal:** Prove a deliverable is real, non-empty, and matches the contract — in pure Java, no trust in the uploader.

- [x] `FileVerifier.verify(Path, Contract)` → `VerificationResult` with per-check pass/fail + reasons
- [x] Extension gate: uploaded suffix must equal `contract.requiredExtension`
- [x] Size gate: reject 0 bytes, reject > configured max
- [x] Magic-byte sniffing (never trust `Content-Type` or filename alone)
- [x] `ZipInputStream` walk for `.jar`/`.zip`: entry count, compressed/uncompressed totals
- [x] Zip-bomb defense: max entries (5,000), max uncompressed (250 MB), compression-ratio cap (200:1)
- [x] Zip-slip defense: reject `..`, absolute paths, backslash traversal
- [x] Require ≥1 compiled `.class` entry whose first 4 bytes are `CAFEBABE`
- [x] `.jar` deep check: require `fabric.mod.json` when contract demands it; parse `id` + `version`
- [x] `.jar` deep check: `META-INF/MANIFEST.MF` presence (warn, not fail)
- [x] Duplicate-entry detection; reject entries that collide after normalization
- [x] `.png` check: signature `89 50 4E 47`, `ImageIO.read` non-null, width/height ≥ contract minimum
- [x] `.png` anti-fake: reject single-color / near-zero-variance images (blank canvas)
- [x] Resolution parsing: `4k` → 3840×2160, `1080p` → 1920×1080, `1920x1080`, `min 2000px`
- [x] Stream files to a quarantine dir with a fresh UUID name; never trust original filename
- [x] Return machine-readable reasons for the AG Grid verification drawer
- [x] Unit tests: empty file, text-masquerading-as-png, valid png, valid jar, truncated zip, zip-slip entry, single-color png
- [x] Reproducible `demo-assets/` via `./gradlew generateDemoAssets`: blocked JAR + verified fabric JAR
- [x] End-to-end re-test with generated payloads: 4/12 checks fail → blocked; 12/12 pass → capture `COMPLETED`

---

## Phase 5 — Web Studio UI + AG Grid Dashboard (`index.html` & `App.java`)

**Goal:** Split-screen studio that a judge can understand in 10 seconds, with a live AG Grid ledger.

- [x] `App.java` Javalin wiring: static files, JSON routes, multipart upload, error mapper
- [x] `POST /api/contracts/parse`
- [x] `POST /api/commissions` — create hold for a parsed contract
- [x] `POST /api/commissions/{id}/deliverable` — multipart upload → verify → conditional capture
- [x] `GET /api/commissions` — ledger feed for AG Grid
- [x] `POST /api/commissions/{id}/release` — manual capture escape hatch (verified only)
- [x] `GET /api/commissions/{id}` — full record including the verification breakdown
- [x] `LedgerService` with atomic JSON persistence (`storage/ledger.json`, temp-file + move)
- [x] Commission state machine: `QUOTED → HELD → VERIFIED → RELEASED` / `FAILED` / `CANCELED`
- [x] `index.html` split screen: left = chat + parse + dropzone, right = AG Grid ledger
- [x] AG Grid Community (CDN, pinned version) with custom status cell renderers
- [x] Quiet editorial dark UI — flat surfaces, hairline dividers, restrained sage accent, responsive to laptop width
- [x] Live polling (4s) + optimistic row updates, no full-page reloads
- [x] Verification drawer/modal showing every check with pass/fail + reason
- [x] PayPal approval link surfaced as a clear "Fund Escrow" button
- [x] Demo mode banner in mock mode (auto-detected or `PAYPAL_MOCK=true`) — honesty with judges
- [x] `GET /api/health` includes integration status (paypal mode, environment, llm mode, version)
- [x] Seed script with 3 realistic commissions for first-paint demo
- [x] README screenshots (`docs/studio.png`, `docs/contract.png`, `docs/blocked.png`, `docs/evidence.png`)
- [x] Mock checkout restyled to the editorial palette (flat surface, serif heading, sage action)
- [x] Live-mode wiring check with fake credentials: health + top-bar pill + LLM badge verified
- [x] End-to-end manual test: parse → hold → upload broken JAR → block → upload verified JAR → capture

---

## Submission Checklist (Definition of Done)

- [x] `LICENSE` (MIT) at repo root
- [x] `README.md` has: what it is, why it matters, Mermaid architecture diagram, screenshot gallery, 3-step setup (mock/live), env table, demo script, sponsor tech callouts
- [x] `demo-assets/` includes two reproducible JAR payloads and usage notes
- [x] No secrets committed — `.env` ignored, `.env.example` only
- [x] PayPal Server SDK (APIMatic) used, not raw REST hand-rolling, for order lifecycle
- [x] `CheckoutPaymentIntent.AUTHORIZE` + capture visible in code and UI
- [x] AG Grid visibly integrated in the primary dashboard
- [x] AI contract parsing is load-bearing in the flow (not decorative)
- [x] `./gradlew build --console=plain` green
- [x] Fresh-clone test: copy repo to temp dir, `./gradlew run` with no `.env`, app boots in mock mode
- [x] 3-minute video script + shot list (`SUBMISSION.md`)
- [x] Devpost submission fields drafted (`SUBMISSION.md`)

---

## Parked / Post-Hackathon

- [ ] PayPal webhooks (`CHECKOUT.ORDER.APPROVED`) instead of polling return URL
- [ ] Multi-file deliverable bundles + per-item checklist
- [ ] Creator reputation scoring from ledger history
- [ ] Stripe/wise rail behind the same `EscrowProvider` interface
- [ ] Postgres instead of JSON ledger
