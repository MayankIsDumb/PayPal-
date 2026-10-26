package com.commishguard.service;

import com.commishguard.config.EnvConfig;
import com.commishguard.model.Commission;
import com.paypal.sdk.Environment;
import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.authentication.ClientCredentialsAuthModel;
import com.paypal.sdk.controllers.OrdersController;
import com.paypal.sdk.controllers.PaymentsController;
import com.paypal.sdk.exceptions.ApiException;
import com.paypal.sdk.http.response.ApiResponse;
import com.paypal.sdk.models.AmountWithBreakdown;
import com.paypal.sdk.models.AuthorizationsCaptureInput;
import com.paypal.sdk.models.AuthorizationsVoidInput;
import com.paypal.sdk.models.CaptureRequest;
import com.paypal.sdk.models.CapturedPayment;
import com.paypal.sdk.models.CheckoutPaymentIntent;
import com.paypal.sdk.models.LinkDescription;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderApplicationContext;
import com.paypal.sdk.models.OrderApplicationContextShippingPreference;
import com.paypal.sdk.models.OrderApplicationContextUserAction;
import com.paypal.sdk.models.OrderAuthorizeRequest;
import com.paypal.sdk.models.OrderAuthorizeResponse;
import com.paypal.sdk.models.OrderCaptureRequest;
import com.paypal.sdk.models.OrderRequest;
import com.paypal.sdk.models.OrdersAuthorizeInput;
import com.paypal.sdk.models.OrdersCaptureInput;
import com.paypal.sdk.models.OrdersCreateInput;
import com.paypal.sdk.models.OrdersGetInput;
import com.paypal.sdk.models.PurchaseUnit;
import com.paypal.sdk.models.PurchaseUnitRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * PayPal Sandbox escrow engine built on the official APIMatic-generated Java Server SDK.
 *
 * <p>Lifecycle used here is the technically correct one for escrow:</p>
 * <ol>
 *   <li>{@code intent=AUTHORIZE} order (a promise, no money moves)</li>
 *   <li>buyer approves on paypal.com, then {@code ordersAuthorize} places the hold</li>
 *   <li>deliverable passes deterministic verification</li>
 *   <li>{@code authorizationsCapture} releases the funds</li>
 * </ol>
 *
 * <p>Without credentials the service runs in MOCK mode. Mock identifiers are unmistakable
 * ({@code MOCK-...}) and every ledger row records its mode, so a demo can never be mistaken
 * for a real payment.</p>
 */
public final class PayPalService {

    private static final Logger LOG = LoggerFactory.getLogger(PayPalService.class);

    /** Standard Orders v2 approval URL; used only if PayPal omits the payer-action link. */
    private static final String SANDBOX_CHECKOUT = "https://www.sandbox.paypal.com/checkoutnow?token=";

    public enum Mode {
        LIVE, MOCK
    }

    public record EscrowOrder(String orderId, String status, String intent, String approvalUrl, String mode) {
    }

    public record EscrowAuthorization(String authorizationId, String status, String orderId, String mode) {
    }

    public record EscrowCapture(String captureId, String status, String authorizationId, String mode) {
    }

    public record OrderSnapshot(String orderId, String status, String approvalUrl, String mode) {
    }

    public static final class PayPalException extends RuntimeException {
        private final String code;

        public PayPalException(String code, String message) {
            super(message);
            this.code = code;
        }

        public PayPalException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final EnvConfig env;
    private final PaypalServerSdkClient client;
    private final OrdersController orders;
    private final PaymentsController payments;
    private final Mode mode;

    public PayPalService(EnvConfig env) {
        this.env = env;
        boolean mock = env.paypalMock() || !env.paypalConfigured();
        this.mode = mock ? Mode.MOCK : Mode.LIVE;

        if (mock) {
            this.client = null;
            this.orders = null;
            this.payments = null;
            if (env.paypalConfigured() && env.paypalMock()) {
                LOG.warn("PAYPAL_MOCK=true — real Sandbox credentials are present but ignored.");
            } else {
                LOG.warn("PayPal credentials absent — starting in MOCK escrow mode.");
            }
            return;
        }

        // Sandbox is hard-coded on purpose: this build must never move real money.
        this.client = new PaypalServerSdkClient.Builder()
                .clientCredentialsAuth(new ClientCredentialsAuthModel.Builder(
                        env.paypalClientId(), env.paypalClientSecret()).build())
                .environment(Environment.SANDBOX)
                .httpClientConfig(c -> c
                        .timeout(30)
                        .numberOfRetries(1)
                        .backOffFactor(1))
                .build();
        this.orders = client.getOrdersController();
        this.payments = client.getPaymentsController();
        LOG.info("PayPal SDK initialised in SANDBOX mode.");
    }

    public Mode mode() {
        return mode;
    }

    public boolean isMock() {
        return mode == Mode.MOCK;
    }

    // ----------------------------------------------------------------- create

    public EscrowOrder createEscrowOrder(Commission commission) {
        String amount = commission.price().setScale(2, RoundingMode.HALF_UP).toPlainString();
        if (isMock()) {
            String orderId = "MOCK-" + shortId();
            String approval = env.publicBaseUrl() + "/api/paypal/mock-checkout?order=" + orderId;
            LOG.info("[mock] AUTHORIZE order {} for {} USD ({})", orderId, amount, commission.getId());
            return new EscrowOrder(orderId, "CREATED", "AUTHORIZE", approval, "mock");
        }

        try {
            OrderRequest orderRequest = new OrderRequest.Builder()
                    .intent(CheckoutPaymentIntent.AUTHORIZE)
                    .purchaseUnits(List.of(new PurchaseUnitRequest.Builder()
                            .amount(new AmountWithBreakdown.Builder("USD", amount).build())
                            .customId(commission.getId())
                            .invoiceId(commission.getId())
                            .description(truncate("CommishGuard escrow — " + commission.getContract().title(), 127))
                            .build()))
                    .applicationContext(new OrderApplicationContext.Builder()
                            .brandName("CommishGuard AI")
                            .returnUrl(env.returnUrl())
                            .cancelUrl(env.cancelUrl())
                            .userAction(OrderApplicationContextUserAction.PAY_NOW)
                            .shippingPreference(OrderApplicationContextShippingPreference.NO_SHIPPING)
                            .build())
                    .build();

            OrdersCreateInput input = new OrdersCreateInput.Builder()
                    .body(orderRequest)
                    .paypalRequestId("cg-create-" + commission.getId())
                    .prefer("return=representation")
                    .build();

            ApiResponse<Order> response = orders.ordersCreate(input);
            Order order = response.getResult();
            if (order == null || order.getId() == null) {
                throw new PayPalException("PAYPAL_EMPTY_RESPONSE", "PayPal returned no order.");
            }
            String approval = findApprovalLink(order.getLinks());
            if (approval == null) {
                approval = SANDBOX_CHECKOUT + order.getId();
                LOG.warn("PayPal order {} returned no payer-action link; using Sandbox checkout fallback.",
                        order.getId());
            }
            LOG.info("PayPal order {} created for {} USD ({})", order.getId(), amount, commission.getId());
            return new EscrowOrder(order.getId(), status(order), "AUTHORIZE", approval, "live");
        } catch (ApiException e) {
            throw translate("create", e);
        } catch (IOException e) {
            throw new PayPalException("PAYPAL_NETWORK",
                    "Could not reach PayPal Sandbox. Check connectivity and try again.", e);
        }
    }

    // -------------------------------------------------------------- authorize

    /**
     * Called after the payer approves. Creates the authorization (the actual hold).
     * Without payer approval PayPal returns an error; the row stays QUOTED and no
     * authorization id is persisted, so it cannot masquerade as funded.
     */
    public EscrowAuthorization authorizeEscrowOrder(String orderId, String commissionId) {
        if (isMock()) {
            return new EscrowAuthorization("MOCK-AUTH-" + shortId(), "CREATED", orderId, "mock");
        }
        try {
            OrdersAuthorizeInput input = new OrdersAuthorizeInput.Builder()
                    .id(orderId)
                    .paypalRequestId("cg-auth-" + commissionId)
                    .prefer("return=representation")
                    .body(new OrderAuthorizeRequest.Builder().build())
                    .build();

            ApiResponse<OrderAuthorizeResponse> response = orders.ordersAuthorize(input);
            OrderAuthorizeResponse result = response.getResult();
            String authorizationId = extractAuthorizationId(result);
            if (authorizationId == null) {
                throw new PayPalException("PAYPAL_NO_AUTHORIZATION",
                        "PayPal approved the order but returned no authorization id.");
            }
            LOG.info("PayPal authorization {} created for order {}", authorizationId, orderId);
            return new EscrowAuthorization(authorizationId, "CREATED", orderId, "live");
        } catch (ApiException e) {
            throw translate("authorize", e);
        } catch (IOException e) {
            throw new PayPalException("PAYPAL_NETWORK",
                    "Could not reach PayPal Sandbox while authorizing the hold.", e);
        }
    }

    // ---------------------------------------------------------------- capture

    /**
     * Releases the held funds. Prefers capturing the authorization created at approval time;
     * falls back to order-level capture for flows where PayPal performed authorization inline.
     */
    public EscrowCapture captureEscrowOrder(Commission commission) {
        String expected = isMock() ? "mock" : "live";
        if (!commission.isMode(expected)) {
            String actual = commission.getMode() == null || commission.getMode().isBlank()
                    ? "unknown" : commission.getMode();
            throw new PayPalException("PAYPAL_MODE_MISMATCH",
                    "This commission belongs to " + actual + " mode and cannot be captured by the "
                            + expected + " PayPal client.");
        }
        if (isMock()) {
            String mockAuthId = commission.getAuthorizationId();
            if (mockAuthId == null || mockAuthId.isBlank()) {
                throw new PayPalException("PAYPAL_NOT_APPROVED",
                        "Buyer approval is required before capture; no mock authorization is held.");
            }
            return new EscrowCapture("MOCK-CAP-" + shortId(), "COMPLETED",
                    mockAuthId, "mock");
        }
        String authId = commission.getAuthorizationId();
        if (authId != null && !authId.isBlank()) {
            try {
                AuthorizationsCaptureInput input = new AuthorizationsCaptureInput.Builder()
                        .authorizationId(authId)
                        .paypalRequestId("cg-capture-" + commission.getId())
                        .prefer("return=representation")
                        .body(captureRequest())
                        .build();
                ApiResponse<CapturedPayment> response = payments.authorizationsCapture(input);
                CapturedPayment captured = response.getResult();
                if (captured == null || captured.getId() == null) {
                    throw new PayPalException("PAYPAL_EMPTY_RESPONSE", "PayPal returned no capture.");
                }
                LOG.info("PayPal capture {} completed for authorization {}", captured.getId(), authId);
                return new EscrowCapture(captured.getId(), String.valueOf(captured.getStatus()),
                        authId, "live");
            } catch (ApiException e) {
                throw translate("capture", e);
            } catch (IOException e) {
                throw new PayPalException("PAYPAL_NETWORK",
                        "Could not reach PayPal Sandbox while capturing.", e);
            }
        }

        // Fallback: order-level capture.
        try {
            OrdersCaptureInput input = new OrdersCaptureInput.Builder()
                    .id(commission.getPaypalOrderId())
                    .paypalRequestId("cg-capture-" + commission.getId())
                    .prefer("return=representation")
                    .body(new OrderCaptureRequest.Builder().build())
                    .build();
            ApiResponse<Order> response = orders.ordersCapture(input);
            Order order = response.getResult();
            String captureId = extractCaptureId(order);
            if (captureId == null) {
                throw new PayPalException("PAYPAL_NO_CAPTURE",
                        "PayPal captured the order but returned no capture id.");
            }
            LOG.info("PayPal order-level capture {} completed for order {}", captureId,
                    commission.getPaypalOrderId());
            return new EscrowCapture(captureId, status(order), null, "live");
        } catch (ApiException e) {
            throw translate("capture", e);
        } catch (IOException e) {
            throw new PayPalException("PAYPAL_NETWORK",
                    "Could not reach PayPal Sandbox while capturing.", e);
        }
    }

    // ------------------------------------------------------------------ order

    public OrderSnapshot getOrder(String orderId) {
        if (isMock()) {
            return new OrderSnapshot(orderId, "CREATED", null, "mock");
        }
        try {
            ApiResponse<Order> response = orders.ordersGet(
                    new OrdersGetInput.Builder().id(orderId).build());
            Order order = response.getResult();
            if (order == null) {
                throw new PayPalException("PAYPAL_EMPTY_RESPONSE", "PayPal returned no order.");
            }
            return new OrderSnapshot(order.getId(), status(order), findApprovalLink(order.getLinks()), "live");
        } catch (ApiException e) {
            throw translate("get", e);
        } catch (IOException e) {
            throw new PayPalException("PAYPAL_NETWORK", "Could not reach PayPal Sandbox.", e);
        }
    }

    /** Best-effort void of a held authorization. */
    public void voidAuthorization(String authorizationId) {
        if (isMock() || authorizationId == null || authorizationId.isBlank()) {
            return;
        }
        try {
            payments.authorizationsVoid(new AuthorizationsVoidInput.Builder()
                    .authorizationId(authorizationId)
                    .paypalRequestId("cg-void-" + authorizationId)
                    .build());
            LOG.info("Voided PayPal authorization {}", authorizationId);
        } catch (ApiException | IOException e) {
            LOG.warn("Could not void authorization {}: {}", authorizationId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ utils

    /**
     * One final capture for the full authorized amount. The SDK's {@code CaptureRequest}
     * builder defaults {@code final_capture} to false, which would leave the authorization
     * open; escrow must close it.
     */
    static CaptureRequest captureRequest() {
        return new CaptureRequest.Builder().finalCapture(true).build();
    }

    // Package-private so the SDK response mapping can be regression-tested without network.
    static String extractAuthorizationId(OrderAuthorizeResponse response) {
        if (response == null || response.getPurchaseUnits() == null) {
            return null;
        }
        for (PurchaseUnit unit : response.getPurchaseUnits()) {
            if (unit.getPayments() == null || unit.getPayments().getAuthorizations() == null) {
                continue;
            }
            for (var auth : unit.getPayments().getAuthorizations()) {
                if (auth.getId() != null) {
                    return auth.getId();
                }
            }
        }
        return null;
    }

    static String extractCaptureId(Order order) {
        if (order == null || order.getPurchaseUnits() == null) {
            return null;
        }
        for (PurchaseUnit unit : order.getPurchaseUnits()) {
            if (unit.getPayments() == null || unit.getPayments().getCaptures() == null) {
                continue;
            }
            for (var capture : unit.getPayments().getCaptures()) {
                if (capture.getId() != null) {
                    return capture.getId();
                }
            }
        }
        return null;
    }

    private static String findApprovalLink(List<LinkDescription> links) {
        if (links == null || links.isEmpty()) {
            return null;
        }
        for (String wanted : List.of("payer-action", "approve")) {
            for (LinkDescription link : links) {
                if (link.getRel() != null && link.getRel().equalsIgnoreCase(wanted)
                        && link.getHref() != null) {
                    return link.getHref();
                }
            }
        }
        for (LinkDescription link : links) {
            String href = link.getHref();
            if (href != null && (href.contains("checkoutnow") || href.contains("token="))) {
                return href;
            }
        }
        return null;
    }

    private static String status(Order order) {
        return order.getStatus() == null ? "UNKNOWN" : order.getStatus().toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private static PayPalException translate(String stage, ApiException e) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        String upper = raw.toUpperCase(Locale.ROOT);
        String code;
        String message;
        if (upper.contains("401") || upper.contains("AUTHENTICATION_FAILURE")
                || upper.contains("INVALID_CLIENT")) {
            code = "PAYPAL_AUTH_FAILED";
            message = "PayPal rejected the Sandbox credentials. Check PAYPAL_CLIENT_ID/SECRET.";
        } else if (upper.contains("ORDER_ALREADY_CAPTURED")
                || upper.contains("AUTHORIZATION_ALREADY_CAPTURED")) {
            code = "PAYPAL_ALREADY_CAPTURED";
            message = "This PayPal order or authorization has already been captured.";
        } else if (upper.contains("ORDER_NOT_APPROVED") || upper.contains("PAYER_ACTION_REQUIRED")) {
            code = "PAYPAL_NOT_APPROVED";
            message = "The buyer has not approved the PayPal order yet.";
        } else if (upper.contains("422")) {
            code = "PAYPAL_REJECTED";
            message = "PayPal rejected the " + stage + " request: " + trim(raw);
        } else {
            code = "PAYPAL_ERROR";
            message = "PayPal " + stage + " failed: " + trim(raw);
        }
        LOG.warn("PayPal {} failed [{}]: {}", stage, code, trim(raw));
        return new PayPalException(code, message, e);
    }

    private static String trim(String raw) {
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    /** Exposed for diagnostics: confirms the SDK got the right environment. */
    public String environmentName() {
        if (client == null) {
            return "MOCK";
        }
        return client.getEnvironment() == Environment.SANDBOX ? "SANDBOX" : "UNEXPECTED";
    }
}
