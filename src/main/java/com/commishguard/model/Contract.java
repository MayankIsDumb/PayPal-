package com.commishguard.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The single shared vocabulary between the AI parser, the PayPal hold and the verifier.
 *
 * <p>Instances arriving from an LLM or from the browser are untrusted. Call
 * {@link #normalized()} and then {@link #validate()} before using any field.</p>
 */
public record Contract(
        String title,
        BigDecimal priceUSD,
        String requiredExtension,
        String minResolutionOrEntryCheck,
        List<String> checklist) {

    public static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jar", ".zip", ".png");
    public static final BigDecimal MIN_PRICE = new BigDecimal("0.01");
    public static final BigDecimal MAX_PRICE = new BigDecimal("10000.00");

    /** Trim, coerce and de-null without judging. */
    public Contract normalized() {
        String t = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        if (t.length() > 120) {
            t = t.substring(0, 120).trim();
        }

        BigDecimal price = priceUSD == null ? BigDecimal.ZERO : priceUSD;
        price = price.setScale(2, RoundingMode.HALF_UP);

        String ext = requiredExtension == null ? "" : requiredExtension.trim().toLowerCase(Locale.ROOT);
        if (!ext.isEmpty() && !ext.startsWith(".")) {
            ext = "." + ext;
        }

        String check = minResolutionOrEntryCheck == null ? "" : minResolutionOrEntryCheck.trim();
        if (check.length() > 80) {
            check = check.substring(0, 80).trim();
        }

        List<String> items = new ArrayList<>();
        if (checklist != null) {
            for (String raw : checklist) {
                if (raw == null) {
                    continue;
                }
                String item = raw.replaceAll("\\s+", " ").trim();
                if (item.isEmpty()) {
                    continue;
                }
                if (item.length() > 200) {
                    item = item.substring(0, 200).trim();
                }
                items.add(item);
                if (items.size() == 12) {
                    break;
                }
            }
        }
        return new Contract(t, price, ext, check, List.copyOf(items));
    }

    /** @throws ContractException when the contract cannot safely drive money or verification. */
    public void validate() {
        List<String> errors = new ArrayList<>();
        if (title == null || title.length() < 3) {
            errors.add("title must be at least 3 characters");
        }
        if (priceUSD == null || priceUSD.compareTo(MIN_PRICE) < 0) {
            errors.add("priceUSD must be at least " + MIN_PRICE);
        } else if (priceUSD.compareTo(MAX_PRICE) > 0) {
            errors.add("priceUSD must not exceed " + MAX_PRICE);
        }
        if (requiredExtension == null || !ALLOWED_EXTENSIONS.contains(requiredExtension)) {
            errors.add("requiredExtension must be one of " + ALLOWED_EXTENSIONS);
        }
        if (checklist == null || checklist.isEmpty()) {
            errors.add("checklist must contain at least one acceptance criterion");
        }
        if (!errors.isEmpty()) {
            throw new ContractException(String.join("; ", errors));
        }
    }

    /** Null-safe convenience used by templates. */
    public String displayCheck() {
        return minResolutionOrEntryCheck == null || minResolutionOrEntryCheck.isBlank()
                ? "structural integrity"
                : minResolutionOrEntryCheck;
    }

    public static final class ContractException extends RuntimeException {
        public ContractException(String message) {
            super(message);
        }
    }
}
