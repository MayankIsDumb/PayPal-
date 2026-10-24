package com.commishguard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Typed, fail-fast view of the process environment.
 *
 * <p>Precedence: real environment variables &gt; {@code .env} file &gt; defaults.
 * The {@code .env} file is optional and never required to run the app; without PayPal
 * credentials the escrow engine starts in MOCK mode so the demo still works.</p>
 */
public final class EnvConfig {

    private static final Logger LOG = LoggerFactory.getLogger(EnvConfig.class);

    private final Map<String, String> values;
    private final Path root;

    private EnvConfig(Path root, Map<String, String> values) {
        this.root = root;
        this.values = values;
    }

    public static EnvConfig load(Path root) {
        Map<String, String> map = new HashMap<>();
        Path dotenv = root.resolve(".env");
        if (Files.isRegularFile(dotenv)) {
            try {
                for (String line : Files.readAllLines(dotenv, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    if (trimmed.startsWith("export ")) {
                        trimmed = trimmed.substring(7).trim();
                    }
                    int eq = trimmed.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = trimmed.substring(0, eq).trim();
                    String value = trimmed.substring(eq + 1).trim();
                    if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    map.put(key, value);
                }
                LOG.info("Loaded {} setting(s) from .env", map.size());
            } catch (IOException e) {
                LOG.warn("Could not read .env: {}", e.getMessage());
            }
        }
        // Environment always wins over the file.
        map.putAll(System.getenv());
        return new EnvConfig(root, map);
    }

    /** Deterministic factory for tests and embedded use. Does not read .env or the process env. */
    public static EnvConfig of(Path root, Map<String, String> values) {
        return new EnvConfig(root, new HashMap<>(values));
    }

    public String get(String key, String fallback) {
        String v = values.get(key);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            LOG.warn("Invalid integer for {}, using {}", key, fallback);
            return fallback;
        }
    }

    public long getLong(String key, long fallback) {
        try {
            return Long.parseLong(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            LOG.warn("Invalid long for {}, using {}", key, fallback);
            return fallback;
        }
    }

    public boolean getBool(String key, boolean fallback) {
        String v = get(key, null);
        if (v == null) {
            return fallback;
        }
        return switch (v.toLowerCase()) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> fallback;
        };
    }

    public Path root() {
        return root;
    }

    // ---- Server ----------------------------------------------------------

    public int port() {
        return getInt("PORT", 7070);
    }

    public String publicBaseUrl() {
        String explicit = get("PUBLIC_BASE_URL", null);
        if (explicit != null) {
            return stripTrailingSlash(explicit);
        }
        String ret = returnUrl();
        int idx = ret.indexOf("/api/");
        return idx > 0 ? stripTrailingSlash(ret.substring(0, idx)) : "http://localhost:" + port();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // ---- PayPal ----------------------------------------------------------

    public String paypalClientId() {
        return get("PAYPAL_CLIENT_ID", "");
    }

    public String paypalClientSecret() {
        return get("PAYPAL_CLIENT_SECRET", "");
    }

    public boolean paypalConfigured() {
        return !paypalClientId().isBlank() && !paypalClientSecret().isBlank();
    }

    /** Mock when explicitly requested, or automatically when credentials are absent. */
    public boolean paypalMock() {
        return getBool("PAYPAL_MOCK", !paypalConfigured());
    }

    public String returnUrl() {
        return get("PAYPAL_RETURN_URL", "http://localhost:" + port() + "/api/paypal/return");
    }

    public String cancelUrl() {
        return get("PAYPAL_CANCEL_URL", "http://localhost:" + port() + "/api/paypal/cancel");
    }

    // ---- LLM -------------------------------------------------------------

    public String llmApiKey() {
        // AI_API_KEY is the preferred name; LLM_API_KEY remains supported.
        String key = get("AI_API_KEY", "");
        return key.isBlank() ? get("LLM_API_KEY", "") : key;
    }

    public boolean llmConfigured() {
        return !llmApiKey().isBlank();
    }

    public String llmBaseUrl() {
        return stripTrailingSlash(get("LLM_BASE_URL", "https://api.openai.com/v1"));
    }

    public String llmModel() {
        return get("LLM_MODEL", "gpt-4o-mini");
    }

    public int llmTimeoutSeconds() {
        return Math.max(3, getInt("LLM_TIMEOUT_SECONDS", 20));
    }

    // ---- Storage & limits ------------------------------------------------

    public Path storageDir() {
        return root.resolve(get("STORAGE_DIR", "storage"));
    }

    public long fileMaxBytes() {
        return Math.max(1, getInt("FILE_MAX_MB", 50)) * 1024L * 1024L;
    }

    public int verifyMaxEntries() {
        return Math.max(10, getInt("VERIFY_MAX_ENTRIES", 5000));
    }

    public long verifyMaxUncompressedBytes() {
        return Math.max(1, getInt("VERIFY_MAX_UNCOMPRESSED_MB", 250)) * 1024L * 1024L;
    }

    public int verifyMaxRatio() {
        return Math.max(10, getInt("VERIFY_MAX_RATIO", 200));
    }

    public int verifyMinRenderPixels() {
        return Math.max(16, getInt("VERIFY_MIN_RENDER_PIXELS", 400));
    }

    public boolean seedDemoData() {
        return getBool("SEED_DEMO", true);
    }
}
