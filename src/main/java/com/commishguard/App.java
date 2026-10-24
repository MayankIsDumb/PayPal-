package com.commishguard;

import com.commishguard.config.EnvConfig;
import com.commishguard.service.AiContractService;
import com.commishguard.service.FileVerifier;
import com.commishguard.service.LedgerService;
import com.commishguard.service.PayPalService;
import com.commishguard.web.Api;
import com.commishguard.web.GsonJsonMapper;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * CommishGuard AI — verifiable escrow for digital commissions.
 *
 * <p>Run with {@code ./gradlew run}. No credentials required: PayPal falls back to MOCK and
 * the contract parser falls back to deterministic regexes. Add a Sandbox app and an LLM key
 * to switch both integrations to live mode.</p>
 */
public final class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    private App() {
    }

    public static void main(String[] args) {
        Path root = Path.of("").toAbsolutePath();
        EnvConfig env = EnvConfig.load(root);

        LedgerService ledger = new LedgerService(env.storageDir(), env.seedDemoData());
        AiContractService ai = new AiContractService(env);
        FileVerifier verifier = new FileVerifier(env);
        PayPalService paypal = new PayPalService(env);
        Api api = new Api(env, ledger, ai, verifier, paypal);

        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.defaultContentType = "application/json; charset=utf-8";
            config.http.maxRequestSize = env.fileMaxBytes() + (5L * 1024L * 1024L);
            config.jsonMapper(new GsonJsonMapper());
        });

        api.register(app);
        app.start(env.port());

        String base = env.publicBaseUrl();
        LOG.info("CommishGuard AI on {} (PayPal {}, LLM {})", base,
                paypal.isMock() ? "MOCK" : "SANDBOX", env.llmConfigured() ? env.llmModel() : "fallback");

        System.out.println();
        System.out.println("  CommishGuard AI  " + base);
        System.out.println("  ------------------------------------------------------------");
        System.out.println("  PayPal   : " + (paypal.isMock()
                ? "MOCK (set PAYPAL_CLIENT_ID / PAYPAL_CLIENT_SECRET for Sandbox)"
                : "SANDBOX " + paypal.environmentName()));
        System.out.println("  Contracts: " + (env.llmConfigured()
                ? "LLM " + env.llmModel() + " (fallback armed)"
                : "deterministic parser"));
        System.out.println("  Ledger   : " + env.storageDir().resolve("ledger.json"));
        System.out.println("  Health   : " + base + "/api/health");
        System.out.println();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                app.stop();
            } catch (Exception ignored) {
                // best effort
            }
        }, "commishguard-shutdown"));
    }
}
