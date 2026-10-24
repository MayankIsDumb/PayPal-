package com.commishguard.service;

import com.commishguard.config.EnvConfig;
import com.commishguard.model.Commission;
import com.commishguard.model.Contract;
import com.paypal.sdk.models.AuthorizationStatus;
import com.paypal.sdk.models.AuthorizationWithAdditionalData;
import com.paypal.sdk.models.Capture;
import com.paypal.sdk.models.CaptureStatus;
import com.paypal.sdk.models.Order;
import com.paypal.sdk.models.OrderAuthorizeResponse;
import com.paypal.sdk.models.PaymentCollection;
import com.paypal.sdk.models.PurchaseUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayPalServiceTest {

    @TempDir
    Path tmp;

    private static Commission commission() {
        Contract c = new Contract("Vex Armor", new BigDecimal("240.00"), ".jar",
                "fabric.mod.json", List.of("loads on 1.21")).normalized();
        return new Commission("CG-TEST0001", c, "budget $240", "mock", false);
    }

    @Test
    void mockModeCreatesAnAuthorizeOrderWithAnApprovalLink() {
        PayPalService paypal = new PayPalService(
                EnvConfig.of(tmp, Map.of("PAYPAL_MOCK", "true")));

        assertTrue(paypal.isMock());
        PayPalService.EscrowOrder order = paypal.createEscrowOrder(commission());

        assertTrue(order.orderId().startsWith("MOCK-"));
        assertEquals("AUTHORIZE", order.intent());
        assertEquals("CREATED", order.status());
        assertNotNull(order.approvalUrl());
        assertTrue(order.approvalUrl().contains("/api/paypal/mock-checkout"));
        assertEquals("mock", order.mode());
    }

    @Test
    void mockCaptureCompletes() {
        PayPalService paypal = new PayPalService(
                EnvConfig.of(tmp, Map.of("PAYPAL_MOCK", "true")));
        Commission c = commission();
        c.setAuthorizationId("MOCK-AUTH-1");

        PayPalService.EscrowCapture capture = paypal.captureEscrowOrder(c);

        assertTrue(capture.captureId().startsWith("MOCK-CAP-"));
        assertEquals("COMPLETED", capture.status());
        assertEquals("mock", capture.mode());
    }

    @Test
    void missingCredentialsForceMockEvenWithoutTheFlag() {
        PayPalService paypal = new PayPalService(EnvConfig.of(tmp, Map.of()));
        assertTrue(paypal.isMock());
        assertEquals("MOCK", paypal.environmentName());
    }

    @Test
    void extractsTheAuthorizationIdFromThePayPalAuthorizeResponseShape() {
        AuthorizationWithAdditionalData authorization = new AuthorizationWithAdditionalData.Builder()
                .id("AUTH-42")
                .status(AuthorizationStatus.CREATED)
                .build();
        PurchaseUnit unit = new PurchaseUnit.Builder()
                .payments(new PaymentCollection.Builder()
                        .authorizations(List.of(authorization))
                        .build())
                .build();
        OrderAuthorizeResponse response = new OrderAuthorizeResponse.Builder()
                .purchaseUnits(List.of(unit))
                .build();

        assertEquals("AUTH-42", PayPalService.extractAuthorizationId(response));
        assertNull(PayPalService.extractAuthorizationId(null));
        assertNull(PayPalService.extractAuthorizationId(
                new OrderAuthorizeResponse.Builder().build()));
    }

    @Test
    void captureRequestClosesTheAuthorizationWithAFinalCapture() {
        assertEquals(Boolean.TRUE, PayPalService.captureRequest().getFinalCapture());
    }

    @Test
    void extractsTheCaptureIdFromTheOrderLevelFallbackShape() {
        Capture capture = new Capture.Builder()
                .id("CAP-42")
                .status(CaptureStatus.COMPLETED)
                .build();
        PurchaseUnit unit = new PurchaseUnit.Builder()
                .payments(new PaymentCollection.Builder()
                        .captures(List.of(capture))
                        .build())
                .build();
        Order order = new Order.Builder()
                .purchaseUnits(List.of(unit))
                .build();

        assertEquals("CAP-42", PayPalService.extractCaptureId(order));
        assertNull(PayPalService.extractCaptureId(null));
    }

    @Test
    void credentialsSelectLiveSandboxWithoutTheMockFlag() {
        PayPalService paypal = new PayPalService(EnvConfig.of(tmp, Map.of(
                "PAYPAL_CLIENT_ID", "sandbox-client-id",
                "PAYPAL_CLIENT_SECRET", "sandbox-client-secret")));

        assertFalse(paypal.isMock());
        assertEquals("LIVE", paypal.mode().name());
        assertEquals("SANDBOX", paypal.environmentName());
    }
}
