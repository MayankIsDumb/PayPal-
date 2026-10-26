package com.commishguard.web;

import com.commishguard.config.EnvConfig;
import com.commishguard.model.Commission;
import com.commishguard.model.CommissionStatus;
import com.commishguard.model.Contract;
import com.commishguard.model.VerificationResult;
import com.commishguard.service.AiContractService;
import com.commishguard.service.FileVerifier;
import com.commishguard.service.InvoiceService;
import com.commishguard.service.LedgerService;
import com.commishguard.service.PayPalService;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** All HTTP surface for the studio. */
public final class Api {

    private static final Logger LOG = LoggerFactory.getLogger(Api.class);
    private static final String VERSION = "0.1.0";

    private final EnvConfig env;
    private final LedgerService ledger;
    private final AiContractService ai;
    private final FileVerifier verifier;
    private final PayPalService paypal;
    private final RateLimiter parseLimiter = new RateLimiter(30, 60_000);
    private final RateLimiter uploadLimiter = new RateLimiter(15, 60_000);
    private final String indexHtml;
    private final Instant startedAt = Instant.now();

    public Api(EnvConfig env, LedgerService ledger, AiContractService ai,
               FileVerifier verifier, PayPalService paypal) {
        this.env = env;
        this.ledger = ledger;
        this.ai = ai;
        this.verifier = verifier;
        this.paypal = paypal;
        this.indexHtml = loadIndex();
    }

    public void register(Javalin app) {
        app.before(ctx -> {
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("X-Frame-Options", "DENY");
            ctx.header("Referrer-Policy", "no-referrer");
        });

        app.get("/", this::index);
        app.get("/index.html", this::index);

        app.get("/api/health", this::health);

        app.post("/api/contracts/parse", this::parseContract);

        app.get("/api/commissions", this::listCommissions);
        app.post("/api/commissions", this::createCommission);
        app.get("/api/commissions/{id}", this::getCommission);
        app.post("/api/commissions/{id}/deliverable", this::uploadDeliverable);
        app.post("/api/commissions/{id}/release", this::release);
        app.get("/api/commissions/{id}/invoice", this::downloadInvoice);
        app.post("/api/commissions/{id}/cancel", this::cancel);

        app.get("/api/paypal/return", this::paypalReturn);
        app.get("/api/paypal/cancel", this::paypalCancel);
        app.get("/api/paypal/mock-checkout", this::mockCheckout);
        app.get("/api/paypal/mock-approve", this::mockApprove);

        app.exception(ApiError.class,
                (e, ctx) -> error(ctx, e.status, e.code, e.getMessage()));
        app.exception(Contract.ContractException.class,
                (e, ctx) -> error(ctx, 400, "CONTRACT_INVALID", e.getMessage()));
        app.exception(PayPalService.PayPalException.class,
                (e, ctx) -> error(ctx, 502, e.code(), e.getMessage()));
        app.exception(HttpResponseException.class,
                (e, ctx) -> error(ctx, e.getStatus(), "HTTP_" + e.getStatus(), e.getMessage()));
        app.exception(IllegalArgumentException.class,
                (e, ctx) -> error(ctx, 400, "BAD_REQUEST", e.getMessage()));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("Unhandled error on {} {}", ctx.method(), ctx.path(), e);
            error(ctx, 500, "INTERNAL", "Something went wrong. Check the server log.");
        });
    }

    // ------------------------------------------------------------------ pages

    private void index(Context ctx) {
        ctx.header("Content-Type", "text/html; charset=utf-8");
        ctx.result(indexHtml);
    }

    private void health(Context ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("version", VERSION);
        body.put("paypalMode", paypal.isMock() ? "mock" : "live");
        body.put("paypalEnvironment", paypal.environmentName());
        body.put("llmMode", env.llmConfigured() ? "ai" : "fallback");
        body.put("llmModel", env.llmConfigured() ? env.llmModel() : "deterministic");
        body.put("commissions", ledger.count());
        body.put("uptimeSeconds", Instant.now().getEpochSecond() - startedAt.getEpochSecond());
        ctx.json(body);
    }

    // ------------------------------------------------------------------- parse

    public record ParseRequest(String chat) {
    }

    public record ParseResponse(Contract contract, String source, List<String> warnings) {
    }

    private void parseContract(Context ctx) {
        requireAllowed(parseLimiter, "parse:" + ctx.ip());
        ParseRequest req = ctx.bodyAsClass(ParseRequest.class);
        if (req == null) {
            throw new ApiError(400, "EMPTY_BODY", "Send {\"chat\": \"...\"}.");
        }
        AiContractService.ParseOutcome outcome = ai.parse(req.chat());
        ctx.json(new ParseResponse(outcome.contract(), outcome.source(), outcome.warnings()));
    }

    // ------------------------------------------------------------- commissions

    public record CreateCommissionRequest(String chat, Contract contract) {
    }

    private void createCommission(Context ctx) {
        CreateCommissionRequest req = ctx.bodyAsClass(CreateCommissionRequest.class);
        if (req == null || req.contract() == null) {
            throw new ApiError(400, "NO_CONTRACT", "Parse a chat into a contract first.");
        }
        Contract contract = req.contract().normalized();
        contract.validate();

        Commission c = ledger.create(contract, req.chat(),
                paypal.isMock() ? "mock" : "live", false);
        c.addAudit("user", "contract.confirmed",
                "Escrow requested for " + contract.priceUSD().toPlainString() + " USD");
        ledger.save(c);

        try {
            PayPalService.EscrowOrder order = paypal.createEscrowOrder(c);
            c.setPaypalOrderId(order.orderId());
            c.setPaypalStatus(order.status());
            c.setApprovalUrl(order.approvalUrl());
            c.setStatus(CommissionStatus.HELD);
            c.addAudit("paypal", "escrow.ordered",
                    order.mode() + " order " + order.orderId() + " intent=" + order.intent()
                            + " status=" + order.status());
            ledger.save(c);
            ctx.status(201).json(c);
        } catch (PayPalService.PayPalException e) {
            c.setStatus(CommissionStatus.FAILED);
            c.addAudit("paypal", "escrow.failed", e.code() + ": " + e.getMessage());
            ledger.save(c);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            body.put("code", e.code());
            body.put("commissionId", c.getId());
            ctx.status(502).json(body);
        }
    }

    private void listCommissions(Context ctx) {
        List<Commission> rows = ledger.all();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", rows.size());
        stats.put("held", rows.stream().filter(r -> r.getStatus() == CommissionStatus.HELD).count());
        stats.put("verified", rows.stream().filter(r -> r.getStatus() == CommissionStatus.VERIFIED).count());
        stats.put("released", rows.stream().filter(r -> r.getStatus() == CommissionStatus.RELEASED).count());
        stats.put("failed", rows.stream().filter(r -> r.getStatus() == CommissionStatus.FAILED).count());
        stats.put("heldValue", sum(rows, true));
        stats.put("releasedValue", sum(rows, false));
        stats.put("paypalMode", paypal.isMock() ? "mock" : "live");
        ctx.json(Map.of("commissions", rows, "stats", stats));
    }

    private static BigDecimal sum(List<Commission> rows, boolean held) {
        BigDecimal total = BigDecimal.ZERO;
        for (Commission c : rows) {
            boolean include = held
                    ? (c.getStatus() == CommissionStatus.HELD || c.getStatus() == CommissionStatus.VERIFIED)
                    : c.getStatus() == CommissionStatus.RELEASED;
            if (include && c.getContract() != null) {
                total = total.add(c.getContract().priceUSD());
            }
        }
        return total;
    }

    private void getCommission(Context ctx) {
        ctx.json(requireCommission(ctx.pathParam("id")));
    }

    // ------------------------------------------------------------------ upload

    private void uploadDeliverable(Context ctx) {
        requireAllowed(uploadLimiter, "upload:" + ctx.ip());
        Commission c = requireCommission(ctx.pathParam("id"));

        if (c.getStatus() == CommissionStatus.RELEASED) {
            throw new ApiError(409, "ALREADY_RELEASED", "This commission is already paid out.");
        }
        if (c.getStatus() == CommissionStatus.CANCELED) {
            throw new ApiError(409, "CANCELED", "This commission was canceled.");
        }
        if (c.getStatus() == CommissionStatus.QUOTED) {
            throw new ApiError(409, "NOT_FUNDED", "Fund the escrow before uploading a deliverable.");
        }

        List<UploadedFile> files = ctx.uploadedFiles("file");
        if (files == null || files.isEmpty()) {
            throw new ApiError(400, "NO_FILE", "Attach the deliverable as multipart field 'file'.");
        }
        UploadedFile upload = files.get(0);
        if (upload.size() > env.fileMaxBytes()) {
            throw new ApiError(413, "FILE_TOO_LARGE",
                    "File exceeds the " + (env.fileMaxBytes() / (1024 * 1024)) + " MB limit.");
        }

        String original = sanitizeName(upload.filename());
        Path dir = env.storageDir().resolve("uploads").resolve(c.getId());
        String ext = c.getContract().requiredExtension();
        Path target = dir.resolve("deliverable-" + UUID.randomUUID().toString().replace("-", "")
                + ext);

        long size;
        try {
            Files.createDirectories(dir);
            try (InputStream in = upload.content()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            size = Files.size(target);
        } catch (IOException e) {
            LOG.error("Upload failed for {}", c.getId(), e);
            throw new ApiError(500, "UPLOAD_FAILED", "Could not store the uploaded file.");
        }

        if (size == 0) {
            throw new ApiError(400, "EMPTY_FILE", "The uploaded file is empty.");
        }

        c.setDeliverableName(original);
        c.setDeliverableSize(size);
        c.addAudit("creator", "deliverable.received", original + " (" + size + " bytes)");

        VerificationResult result = verifier.verify(target, original, c.getContract());
        c.setVerification(result);

        String captureError = null;
        if (!result.passed()) {
            c.setStatus(CommissionStatus.FAILED);
            c.addAudit("verifier", "deliverable.rejected", result.summary());
            ledger.save(c);
            ctx.json(Map.of("commission", c, "verification", result));
            return;
        }

        c.setStatus(CommissionStatus.VERIFIED);
        c.addAudit("verifier", "deliverable.verified", result.summary());

        boolean funded = paypal.isMock()
                || (c.getAuthorizationId() != null && !c.getAuthorizationId().isBlank());
        if (funded) {
            captureError = tryAutoCapture(c);
        } else {
            c.addAudit("paypal", "capture.deferred",
                    "Buyer has not approved the PayPal order yet; funds are not held.");
        }

        ledger.save(c);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commission", c);
        body.put("verification", result);
        if (captureError != null) {
            body.put("captureError", captureError);
        }
        ctx.json(body);
    }

    private void release(Context ctx) {
        Commission c = requireCommission(ctx.pathParam("id"));
        if (c.getStatus() == CommissionStatus.RELEASED) {
            ctx.json(c);
            return;
        }
        if (c.getStatus() != CommissionStatus.VERIFIED
                || c.getVerification() == null || !c.getVerification().passed()) {
            throw new ApiError(409, "NOT_VERIFIED",
                    "Only a commission with a passing verification can be released.");
        }
        boolean funded = paypal.isMock()
                || (c.getAuthorizationId() != null && !c.getAuthorizationId().isBlank());
        if (!funded) {
            throw new ApiError(409, "NOT_FUNDED",
                    "The buyer has not approved the PayPal order yet, so there is nothing to capture.");
        }
        String captureError = tryAutoCapture(c);
        ledger.save(c);
        if (captureError != null) {
            throw new ApiError(502, "CAPTURE_FAILED", captureError);
        }
        ctx.json(c);
    }

    private void cancel(Context ctx) {
        Commission c = requireCommission(ctx.pathParam("id"));
        if (c.getStatus() == CommissionStatus.RELEASED) {
            throw new ApiError(409, "ALREADY_RELEASED", "Captured payments cannot be canceled here.");
        }
        paypal.voidAuthorization(c.getAuthorizationId());
        c.setStatus(CommissionStatus.CANCELED);
        c.addAudit("user", "commission.canceled", "Hold released where possible.");
        ledger.save(c);
        ctx.json(c);
    }

    // ------------------------------------------------------------------ invoice

    private void downloadInvoice(Context ctx) {
        Commission c = requireCommission(ctx.pathParam("id"));
        if (c.getStatus() != CommissionStatus.RELEASED) {
            throw new ApiError(409, "NOT_RELEASED",
                    "Invoices are only available for commissions that have been paid out.");
        }
        byte[] pdf = InvoiceService.generate(c);
        String filename = "CommishGuard-Invoice-" + c.getId() + ".pdf";
        ctx.header("Content-Type", "application/pdf");
        ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        ctx.header("Content-Length", String.valueOf(pdf.length));
        ctx.result(pdf);
    }

    // ------------------------------------------------------------------ paypal

    private void paypalReturn(Context ctx) {
        String token = ctx.queryParam("token");
        if (token == null || token.isBlank()) {
            ctx.redirect("/?paypal=missing");
            return;
        }
        Optional<Commission> found = ledger.findByOrderId(token);
        if (found.isEmpty()) {
            ctx.redirect("/?paypal=unknown");
            return;
        }
        Commission c = found.get();

        // PayPal may hit the return URL more than once (refresh, back button, retry).
        // Never authorize the same order twice; resume from the persisted hold instead.
        if (c.getAuthorizationId() != null && !c.getAuthorizationId().isBlank()) {
            if (c.getStatus() == CommissionStatus.VERIFIED && c.getVerification() != null
                    && c.getVerification().passed()) {
                tryAutoCapture(c);
            }
            ledger.save(c);
            ctx.redirect("/?funded=" + c.getId());
            return;
        }

        if (paypal.isMock()) {
            markFunded(c, "MOCK-AUTH-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT), "mock return");
            ledger.save(c);
            ctx.redirect("/?funded=" + c.getId());
            return;
        }

        try {
            PayPalService.EscrowAuthorization auth = paypal.authorizeEscrowOrder(token, c.getId());
            markFunded(c, auth.authorizationId(), "authorize " + auth.authorizationId());
            if (c.getStatus() == CommissionStatus.VERIFIED && c.getVerification() != null
                    && c.getVerification().passed()) {
                tryAutoCapture(c);
            }
            ledger.save(c);
            ctx.redirect("/?funded=" + c.getId());
        } catch (PayPalService.PayPalException e) {
            c.addAudit("paypal", "authorize.failed", e.code() + ": " + e.getMessage());
            ledger.save(c);
            ctx.redirect("/?paypal=error&id=" + c.getId());
        }
    }

    private void paypalCancel(Context ctx) {
        String token = ctx.queryParam("token");
        ledger.findByOrderId(token).ifPresent(c -> {
            if (c.getStatus() != CommissionStatus.RELEASED) {
                c.setStatus(CommissionStatus.CANCELED);
                c.addAudit("buyer", "escrow.canceled", "Buyer canceled on PayPal.");
                ledger.save(c);
            }
        });
        ctx.redirect("/?paypal=canceled");
    }

    private void mockCheckout(Context ctx) {
        String order = ctx.queryParam("order");
        if (order == null || order.isBlank()) {
            throw new ApiError(400, "NO_ORDER", "Missing mock order id.");
        }
        String safe = escape(order);
        String page = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>CommishGuard — Mock Sandbox Checkout</title>
                <style>
                  :root{color-scheme:dark}
                  *{box-sizing:border-box}
                  body{margin:0;min-height:100vh;display:grid;place-items:center;padding:32px;
                    font-family:"Inter","SF Pro Text",ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif;
                    background:#0a0b0b;
                    background-image:radial-gradient(1200px 600px at 18%% -24%%,rgba(200,213,192,.035),transparent 62%%);
                    color:#e9e8e1;-webkit-font-smoothing:antialiased}
                  .card{width:min(460px,100%%);background:#0e100f;border:1px solid rgba(255,255,255,.075);
                    border-radius:14px;padding:34px}
                  .brand{display:flex;align-items:center;gap:9px;font-size:10px;letter-spacing:.16em;
                    text-transform:uppercase;color:#8b8e87}
                  .brand i{width:6px;height:6px;border-radius:50%%;background:#c8d5c0;display:block}
                  .pill{display:inline-block;font-size:9px;letter-spacing:.16em;text-transform:uppercase;
                    color:#d5b98e;border:1px solid rgba(213,185,142,.28);background:rgba(213,185,142,.045);
                    padding:5px 9px 4px;border-radius:999px;margin-top:26px}
                  h1{font-family:"Iowan Old Style","Palatino Linotype",Palatino,Georgia,serif;
                    font-size:31px;font-weight:400;letter-spacing:-.025em;line-height:1.12;margin:16px 0 10px}
                  p{color:#8b8e87;font-size:13px;line-height:1.75;margin:0 0 12px}
                  .order{display:flex;justify-content:space-between;align-items:center;gap:16px;
                    border-top:1px solid rgba(255,255,255,.075);border-bottom:1px solid rgba(255,255,255,.075);
                    padding:13px 0;margin:22px 0}
                  .order span{font-size:9px;letter-spacing:.16em;text-transform:uppercase;color:#5f635d}
                  code{font-family:ui-monospace,SFMono-Regular,"Cascadia Code",Menlo,monospace;
                    font-size:11px;color:#c8d5c0;background:#121513;border:1px solid rgba(255,255,255,.075);
                    border-radius:6px;padding:4px 7px}
                  a.btn{display:block;text-align:center;text-decoration:none;font-weight:600;font-size:11px;
                    letter-spacing:.12em;text-transform:uppercase;background:#c8d5c0;color:#0a0b0b;
                    padding:13px;border-radius:8px;margin-top:18px}
                  a.btn:hover{background:#d7e2d0}
                  a.ghost{display:block;text-align:center;text-decoration:none;color:#5f635d;
                    font-size:11px;margin-top:16px}
                  a.ghost:hover{color:#8b8e87}
                  .fine{font-size:10px;color:#5f635d;line-height:1.6;margin:20px 0 0}
                </style></head><body>
                <main class="card">
                  <div class="brand"><i></i>CommishGuard AI</div>
                  <span class="pill">Sandbox mock</span>
                  <h1>Approve this escrow hold</h1>
                  <p>No real money moves. This local page replaces the PayPal approval screen
                     because no Sandbox credentials are configured.</p>
                  <div class="order"><span>Order</span><code>%s</code></div>
                  <a class="btn" href="/api/paypal/mock-approve?order=%s">Approve hold</a>
                  <a class="ghost" href="/">Return to the studio</a>
                  <p class="fine">Authorized funds stay on hold until deterministic verification passes.
                     A failed file never triggers capture.</p>
                </main></body></html>
                """.formatted(safe, safe);
        ctx.html(page);
    }

    private void mockApprove(Context ctx) {
        String order = ctx.queryParam("order");
        ledger.findByOrderId(order).ifPresentOrElse(c -> {
            if (c.getAuthorizationId() == null || c.getAuthorizationId().isBlank()) {
                markFunded(c, "MOCK-AUTH-" + UUID.randomUUID().toString()
                        .replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT), "mock approval");
            }
            if (c.getStatus() == CommissionStatus.VERIFIED && c.getVerification() != null
                    && c.getVerification().passed()) {
                tryAutoCapture(c);
            }
            ledger.save(c);
            ctx.redirect("/?funded=" + c.getId());
        }, () -> ctx.redirect("/?paypal=unknown"));
    }

    private void markFunded(Commission c, String authorizationId, String source) {
        c.setAuthorizationId(authorizationId);
        c.setPaypalStatus("APPROVED");
        if (c.getStatus() == CommissionStatus.QUOTED) {
            c.setStatus(CommissionStatus.HELD);
        }
        c.addAudit("paypal", "escrow.authorized",
                source + " → authorization " + authorizationId);
    }

    /** @return a user-safe error message, or null when capture completed. */
    private String tryAutoCapture(Commission c) {
        try {
            PayPalService.EscrowCapture capture = paypal.captureEscrowOrder(c);
            c.setCaptureId(capture.captureId());
            c.setCaptureStatus(capture.status());
            boolean completed = "COMPLETED".equalsIgnoreCase(capture.status());
            if (completed) {
                c.setStatus(CommissionStatus.RELEASED);
                c.addAudit("paypal", "escrow.captured",
                        capture.mode() + " capture " + capture.captureId()
                                + " status=" + capture.status());
                return null;
            }
            c.addAudit("paypal", "capture.pending",
                    "capture " + capture.captureId() + " status=" + capture.status());
            return "PayPal capture is " + capture.status() + " (id " + capture.captureId() + ").";
        } catch (PayPalService.PayPalException e) {
            c.addAudit("paypal", "capture.failed", e.code() + ": " + e.getMessage());
            return e.getMessage();
        }
    }

    // ------------------------------------------------------------------ helpers

    private Commission requireCommission(String id) {
        return ledger.get(id).orElseThrow(() ->
                new ApiError(404, "COMMISSION_NOT_FOUND", "No commission with id " + id));
    }

    private static void requireAllowed(RateLimiter limiter, String key) {
        if (!limiter.allow(key)) {
            throw new ApiError(429, "RATE_LIMITED", "Too many requests. Wait a minute.");
        }
    }

    private static String sanitizeName(String name) {
        String n = name == null || name.isBlank() ? "deliverable" : name;
        n = n.replace('\\', '_').replace('/', '_').replaceAll("[\\p{Cntrl}]", "");
        if (n.length() > 160) {
            n = n.substring(n.length() - 160);
        }
        return n;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static void error(Context ctx, int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message == null || message.isBlank() ? code : message);
        body.put("code", code);
        ctx.status(status).json(body);
    }

    private String loadIndex() {
        try (InputStream in = Api.class.getResourceAsStream("/public/index.html")) {
            if (in == null) {
                return "<h1>CommishGuard</h1><p>UI asset missing from the classpath.</p>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read index.html", e);
        }
    }

    /** Machine-readable API failure with an HTTP status and stable code. */
    static final class ApiError extends RuntimeException {
        final int status;
        final String code;

        ApiError(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
