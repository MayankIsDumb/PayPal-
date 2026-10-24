---
description: Test PayPal Sandbox OAuth2 and Order Authorize/Capture flow
agent: build
---

Verify the PayPal Sandbox integration end to end, using real credentials if present and clearly-labeled mock mode if not.

## Preflight
1. Read `.env` (never print the secret). Confirm `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET` are present and non-empty.
2. Confirm `PayPalService` builds `PaypalServerSdkClient` with `Environment.SANDBOX` and `ClientCredentialsAuthModel`.
3. Actually inspect the resolved SDK jar before trusting method names:
   ```
   ./gradlew dependencies --configuration runtimeClasspath --console=plain | grep paypal
   ```
   If a method signature is uncertain, `javap` the class from the Gradle cache instead of guessing.

## Run
```
./gradlew test --tests "*PayPalSmokeTest*" --console=plain --info | tail -n 60
```
If no smoke test exists yet, create `src/test/java/com/commishguard/PayPalSmokeTest.java` that:
- is skipped (`Assumptions.assumeTrue`) when credentials are absent,
- calls `createEscrowOrder` for `USD 1.00`, prints the Sandbox **order id** and **approval link**,
- asserts the order status is `CREATED`/`PAYER_ACTION_REQUIRED` and the intent is `AUTHORIZE`,
- does **not** attempt capture unless `PAYPAL_SMOKE_CAPTURE=true` (capture needs a buyer approval).

## Expected output (paste exact values, truncated)
```
mode:            live | mock
order id:        <id>
status:          CREATED
intent:          AUTHORIZE
approval link:   https://www.sandbox.paypal.com/checkoutnow?token=<id>
```

## Failure playbook
- `401 invalid_client` → credentials are from different apps or the app is not Sandbox. Stop; do not retry.
- `404` on orders → base URL/environment wrong; assert `Environment.SANDBOX`.
- `422 UNPROCESSABLE_ENTITY` → inspect `details[].issue`; usually amount format or currency.
- Timeout → check proxy/VPN; retry once with the same `PayPal-Request-Id`.

## Rules
- Sandbox only. If any code path can hit `api.paypal.com` (production), fail the audit immediately.
- Never echo the client secret, access token, or full auth header.
- If credentials are absent, run mock mode and say so explicitly — a green mock run is not proof of live auth.
