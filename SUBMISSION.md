# Submission Kit — CommishGuard AI

Everything needed to publish the project and record the 3-minute demo.

---

## Devpost fields

**Project name:** CommishGuard AI

**Tagline:** Escrow that checks the work before it pays.

**Track:** Build What's Next with PayPal and AI

**Built with:** Java 21, Javalin 6, PayPal Server SDK 0.6.1 (Sandbox Orders v2), AG Grid Community 32.3.3, Gson 2.11, Gradle 9.8.1, OpenAI-compatible LLM API, HTML/CSS/JavaScript

**Links**

- Repository: https://github.com/MayankIsDumb/PayPal-
- Demo video: `<add YouTube/Vimeo URL>`
- Live demo: `<optional — localhost demo is the primary path>`

### Inspiration

Digital commissions are a trust gap. Clients send money to strangers and hope the file is real. Creators deliver work and hope the invoice gets paid. Disputes are settled with screenshots, and "it's a 4K PNG" is a claim, not a verifiable fact. We wanted the deal itself to be executable: agree on terms, hold the money, let code check the deliverable, then release.

### What it does

CommishGuard AI turns a messy Discord or email negotiation into a strict contract. It opens a PayPal Sandbox order with `intent=AUTHORIZE`; after the client approves, `ordersAuthorize` places a hold rather than taking the money. When the creator uploads a `.jar`, `.zip`, or `.png`, deterministic Java code inspects the bytes, archive structure, and image dimensions. A passing file triggers capture and pays the creator; a failing file keeps the money held and shows both sides exactly which checks failed. Every state change is written to an auditable ledger shown in an AG Grid dashboard.

### How we built it

- **Contract extraction:** Java 21 `HttpClient` calls an OpenAI-compatible endpoint with a JSON-only system prompt. The server re-validates price, extension, resolution/entry requirements, and checklist size. With no API key, a deterministic regex parser keeps the demo fully functional.
- **Escrow:** The official PayPal Server SDK creates the `AUTHORIZE` order, sends the buyer through approval, calls `ordersAuthorize` to place the hold, and calls `authorizationsCapture` only after verification passes. Idempotency keys and a `MOCK-` fallback make the flow safe to demo.
- **Verification:** `ZipInputStream` checks compiled `CAFEBABE` classes, `fabric.mod.json`, manifest presence, duplicate entries, traversal paths, and decompression limits. `ImageIO` checks PNG signatures, decodability, resolution, and blank/single-color canvases. Uploads are quarantined and never executed.
- **UI:** A single-page studio with a deal desk, drag-and-drop deliverable, evidence drawer, and an AG Grid ledger with custom status and verification renderers. The ledger persists atomically to JSON.

### Challenges

Making the demo honest was harder than making it work. We needed a real `AUTHORIZE` → authorize → capture lifecycle while still allowing judges to run the project with zero credentials. Mock mode therefore mirrors the real state machine instead of faking a single response. The verifier also had to be strict without being brittle: a valid JAR can have many shapes, so we check invariants (real bytecode, required entries, safe paths) rather than a specific project layout.

### Accomplishments

- End-to-end UI flow verified: sample chat → parsed contract → Sandbox order → mock approval → bad JAR blocked → good JAR verified → payment captured.
- A verifier that rejects text-masquerading-as-PNG, blank canvases, zip-slip entries, zip bombs, and JARs without compiled classes.
- A first-paint dashboard that is useful before the judge touches anything, with sample rows clearly labelled.
- No secrets in the repo and no real money in any path.

### What we learned

LLMs are good at reading intent and bad at being an authority. The winning pattern was to let the model propose a contract and let Java validate and enforce it. The same applies to payments: the browser can request, but only the server can decide to capture.

### What's next

- PayPal webhooks instead of polling return URLs.
- Multi-file bundles with per-item acceptance criteria.
- Creator reputation from ledger history.
- Postgres for multi-tenant use.
- Additional payment rails behind the same escrow interface.

---

## 3-minute demo script

**Target length:** 2:50. **Resolution:** 1440×900 or 1920×1080. **Audio:** live narration, no music required.

| Time | Shot | Narration |
|---|---|---|
| 0:00–0:15 | Studio first paint, cursor over the sample rows | "Freelance commissions run on trust. The client pays first, or the creator delivers first. CommishGuard makes the deal executable." |
| 0:15–0:35 | Click **Sample**, then **Parse contract** | "Here's a real-shaped Discord thread: a Fabric armor mod, $240, `.jar`, `fabric.mod.json`, by Friday. The parser turns it into a contract the server can validate." |
| 0:35–0:55 | Contract card; point at price, extension, criteria | "The AI proposes. Java disposes: price range, extension whitelist, and required entries are re-checked server-side. No key? A deterministic parser keeps the demo alive." |
| 0:55–1:20 | Click **Hold funds**; drawer opens; click **Fund escrow** | "PayPal gets an order with `intent=AUTHORIZE`. The buyer approves, and `ordersAuthorize` places a hold. The money is not captured yet — that's the whole point." |
| 1:20–1:45 | Approve in mock checkout; return; close drawer | "In mock mode this is a local approval page. With Sandbox credentials the same code drives the real PayPal checkout. The row moves to Funds held." |
| 1:45–2:15 | Drag `broken-submission.jar`; drawer shows failed checks | "Now the creator uploads a JAR with no compiled classes and no `fabric.mod.json`. The verifier blocks it. The drawer shows every check, not a vague error. The money stays held." |
| 2:15–2:40 | Drag `verified-motionblur-1.21.jar`; row becomes Paid out | "The real JAR has compiled bytecode, a valid manifest, and Fabric metadata with id `afkzmotionblur` version `1.2.0`. Every check passes, so the server captures the authorization. The creator gets paid." |
| 2:40–2:55 | Open the evidence drawer; scroll the audit trail | "Order, authorization, capture, file checks, timestamps — one auditable trail. AI for ambiguity, code for truth, PayPal for the money." |

### Shot list

1. Full studio at rest, three sample rows visible.
2. Sample chat text with the price and file requirements legible.
3. Parse transition; contract card centered.
4. Mouse on the `AUTHORIZE` tag; then the Hold funds button.
5. Mock checkout page; click **Approve hold**.
6. Ledger row changing from Quote to Funds held.
7. Broken upload; close-up of the dusty-red failed checks.
8. Verified upload; close-up of "payment captured" and Paid out.
9. Drawer audit trail, slow scroll.
10. End card: repo URL + "Sandbox only. No real money moves."

---

## Judge quick test (no credentials)

```bash
./gradlew run --console=plain
```

1. Open `http://localhost:7070`.
2. **Sample → Parse contract → Hold funds → Fund escrow → Approve hold.**
3. Upload `demo-assets/broken-submission.jar`, then `demo-assets/verified-motionblur-1.21.jar`.
4. Confirm the first is blocked and the second is released. Use `demo/aetheris-1920.png` and `demo/fake.png` for the image path.

The top bar will show a quiet **PayPal Mock** label and the **Deterministic parser** tag. That is expected and clearly labelled. Regenerate the JARs with `./gradlew generateDemoAssets`.

## Live Sandbox test

Add Sandbox credentials and an AI key to `.env`:

```bash
PAYPAL_CLIENT_ID=...
PAYPAL_CLIENT_SECRET=...
AI_API_KEY=...
```

Restart. The top bar changes to a muted **Sandbox Live** pill and the parser badge shows the model name. Fund a commission, approve on PayPal, and upload a valid deliverable. The server will call `ordersAuthorize` and then `authorizationsCapture`. `./gradlew paypalSmoke` prints an approval link without touching the browser.

## Honesty notes for the submission

- No real money moves in any configuration shipped in this repo. Sandbox is forced.
- The demo video uses mock mode unless Sandbox credentials are shown on screen.
- The three seeded ledger rows are labelled `SAMPLE` and are not transactions.
- The LLM is optional; the deterministic parser is not a fallback shown only in slides — it powers the same validated contract object.
