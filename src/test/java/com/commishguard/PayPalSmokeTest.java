package com.commishguard;

import com.commishguard.config.EnvConfig;
import com.commishguard.model.Commission;
import com.commishguard.model.Contract;
import com.commishguard.service.PayPalService;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Standalone Sandbox smoke test: {@code ./gradlew paypalSmoke}.
 *
 * <p>Never performs a capture: a capture requires a real buyer approval on paypal.com.
 * Prints the order id and approval link so a human can complete the flow.</p>
 */
public final class PayPalSmokeTest {

    private PayPalSmokeTest() {
    }

    public static void main(String[] args) {
        EnvConfig env = EnvConfig.load(Path.of("").toAbsolutePath());
        if (!env.paypalConfigured()) {
            System.out.println("SKIPPED: PAYPAL_CLIENT_ID / PAYPAL_CLIENT_SECRET are not set.");
            System.out.println("Running mock mode instead to prove the wiring.\n");
        }

        PayPalService paypal = new PayPalService(env);
        Contract contract = new Contract("CommishGuard smoke test", new BigDecimal("1.00"),
                ".png", "1920x1080", List.of("smoke test only")).normalized();
        Commission c = new Commission("CG-SMOKE-" + UUID.randomUUID().toString().substring(0, 4),
                contract, "smoke", paypal.isMock() ? "mock" : "live", false);

        try {
            PayPalService.EscrowOrder order = paypal.createEscrowOrder(c);
            System.out.println("mode:            " + order.mode());
            System.out.println("environment:     " + paypal.environmentName());
            System.out.println("order id:        " + order.orderId());
            System.out.println("status:          " + order.status());
            System.out.println("intent:          " + order.intent());
            System.out.println("approval link:   " + order.approvalUrl());
            System.out.println();
            System.out.println(order.mode().equals("live")
                    ? "Open the approval link, log in with a SANDBOX personal account, then the app can authorize + capture."
                    : "Mock mode: open the local approval link and click Approve to simulate the buyer.");
        } catch (RuntimeException e) {
            System.err.println("FAILED: " + e.getMessage());
            System.exit(1);
        }
    }
}
