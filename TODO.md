# CommishGuard AI — Build Ledger

> **Single source of truth for execution state.** Read this file before every task.
> Update the checkbox, the timestamp, and `## Current Session State` after every task.
> Deadline: **November 12, 2026** — Build What's Next with PayPal and AI Hackathon.

---

## Current Session State

**Last updated:** 2026-10-26 (live AI parse verified + invoice PDF + browser E2E + live Sandbox evidence + hardening pass)

**Status:** All five phases complete and verified. The live Sandbox path has now been exercised: the ledger contains two live `RELEASED` commissions with `COMPLETED` captures and persisted order/authorization/capture IDs. The generated `demo-assets/` flow still passes end-to-end: sample chat → validated contract → `AUTHORIZE` order → mock approval → `broken-submission.jar` blocked (4/12 checks) → `verified-motionblur-1.21.jar` verified (12/12) → capture `COMPLETED`. Added on-demand PDF invoices for `RELEASED` commissions: `GET /api/commissions/{id}/invoice`, a drawer button, OpenPDF 2.0.3 rendering, WinAnsi-safe text normalization, and `docs/invoice.png`. The invoice covers contract terms, every deterministic check, PayPal transaction IDs, and the latest six audit events; mock/sample receipts carry a visible `SANDBOX / DEMO` footer. The full browser flow was then re-run with Playwright against a clean mock ledger: parse → fund → mock approve → blocked JAR → verified JAR captured → invoice downloaded as `%PDF-`; the run finished with no console errors. The drawer now distinguishes `LIVE RECORD` from `MOCK RECORD`, shows approved/captured state instead of a stale approval link, and the server serves a branded favicon so no 404 appears in the console. `./gradlew build` is green (37 tests), the repo is pushed to https://github.com/MayankIsDumb/PayPal-hackathon, and the main server is restarted from the latest distribution. Health at http://localhost:7070 reports `paypalMode=live`, `paypalEnvironment=SANDBOX`, `llmMode=ai`, `llmModel=gemini-3.5-flash`, and 7 ledger rows including the two live `RELEASED` captures. A live Gemini parse of the sample Fabric chat returned `source=ai`, $240, `.jar`, `fabric.mod.json`, and 4 checklist items with no warnings; the retired `gemini-1.5-flash` model was replaced with `gemini-3.5-flash`. The final hardening pass is complete: `gradlew` is executable in the index (`100755`), mock checkout/approval routes are registered only in mock mode, cross-mode mutations return `MODE_MISMATCH`, capture requires a persisted authorization even in mock mode, and new commissions stay `QUOTED` with the UI showing **Awaiting Buyer Approval** until `ordersAuthorize` succeeds. A clean mock Playwright run verified awaiting → Funds held → blocked → captured → invoice with zero console errors; the live server returns `404` for mock routes and `409` for mock-row mutations. The only remaining submission blocker is the public demo video URL.

**Architecture in one line:** Javalin 6 REST server (Java 21) that parses deal chat into a strict `Contract` JSON, opens a PayPal Sandbox `AUTHORIZE` order, deterministically verifies uploaded `.jar`/`.zip`/`.png` deliverables in-process, then captures the authorization to release escrow funds and issue a PDF receipt.

**Next 2 actions:**
1. Record the 3-minute demo video using the script and shot list in `SUBMISSION.md`; show the live AI parse (`source=ai`) and, if possible, the Sandbox Live ledger.
2. Replace the `<add YouTube/Vimeo URL>` placeholder in `SUBMISSION.md` with the published video and finish the Devpost submission; the public repo is live at https://github.com/MayankIsDumb/PayPal-hackathon.

**Active bugs / risks:**
- Live PayPal Sandbox and live LLM extraction are both verified. The Gemini OpenAI-compatible endpoint returned `source=ai` for the sample chat; `gemini-1.5-flash` was retired, so the working model is `gemini-3.5-flash`.
- Invoice PDFs use OpenPDF's base-14 fonts. Dynamic text is normalized to WinAnsi-safe characters; non-Latin contract text is replaced with `?` rather than risking tofu glyphs.
- AG Grid CSS/JS load from jsDelivr; the dashboard needs network access on first load.
- Automated screenshots use headless Chrome; the desktop Review pane can be occluded by the user's active tab.

**Recent decisions:**
- Demo payloads are generated, not hand-edited: `broken-submission.jar` is a valid archive with no classes and no `fabric.mod.json`; `verified-motionblur-1.21.jar` carries real Fabric metadata (`afkzmotionblur` / `1.2.0`) plus two header-valid `CAFEBABE` class skeletons.
- `AI_API_KEY` is the documented key; `LLM_API_KEY` remains accepted as an alias. The internal no-key warning is suppressed because the `Deterministic` tag already communicates the mode.
- Live mode is selected automatically when both PayPal credentials are present and `PAYPAL_MOCK` is unset. The top bar changes to a subtle outlined `Sandbox Live` pill; the parser badge shows the model.
- The mock checkout page was restyled to match the editorial studio (flat warm off-black surface, serif headline, sage action) so mock-mode recordings stay visually consistent.
- The chat panel collapses after a successful parse, with **Edit deal chat** to restore it; a funded `?funded=<id>` reload re-hydrates the contract and arms upload.
- Ledger persists to `storage/ledger.json`; seeds are labelled `SAMPLE` and reset cleanly for first paint.
- Invoices are generated on demand from the persisted ledger, never from request-supplied amounts, and only for `RELEASED` rows. The PDF is a receipt, not a tax document; fees are not deducted because the capture amount is the contract amount.

---

## Phase 0 — Agentic Workspace ✅

- [x] Create `TODO.md` with phase-by-phase execution plan
- [x] Create `ARCHITECTURE.md` with system design, JSON contracts, failure modes
- [x] Local OpenCode commands/agents created (kept untracked; not part of the deliverable)
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
- [x] Live-mode smoke script wired to the local OpenCode command (untracked)

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
- [x] `GET /api/commissions/{id}/invoice` — on-demand PDF receipt for `RELEASED` rows only
- [x] `InvoiceService` — OpenPDF contract summary, checks, PayPal IDs, latest six audit events
- [x] `Download Invoice PDF` action in the drawer and immediately after capture
- [x] Invoice tests: valid `%PDF-` output, full amount, normalized audit punctuation, no replacement glyphs
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
- [x] README screenshots (`docs/studio.png`, `docs/contract.png`, `docs/blocked.png`, `docs/evidence.png`, `docs/invoice.png`)
- [x] Mock checkout restyled to the editorial palette (flat surface, serif heading, sage action)
- [x] Live-mode wiring check with fake credentials: health + top-bar pill + LLM badge verified
- [x] End-to-end manual test: parse → hold → upload broken JAR → block → upload verified JAR → capture
- [x] End-to-end invoice test: mock capture → `RELEASED` → PDF download → text extracted without font glyph loss

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
- [x] PDF receipt for completed commissions (drawer action + API)
- [x] 3-minute video script + shot list (`SUBMISSION.md`)
- [x] Devpost submission fields drafted (`SUBMISSION.md`)

---

## Parked / Post-Hackathon

- [ ] PayPal webhooks (`CHECKOUT.ORDER.APPROVED`) instead of polling return URL
- [ ] Multi-file deliverable bundles + per-item checklist
- [ ] Creator reputation scoring from ledger history
- [ ] Stripe/wise rail behind the same `EscrowProvider` interface
- [ ] Postgres instead of JSON ledger
