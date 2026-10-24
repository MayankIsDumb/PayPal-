package com.commishguard.service;

import com.commishguard.model.Commission;
import com.commishguard.model.CommissionStatus;
import com.commishguard.model.Contract;
import com.commishguard.util.Json;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable, single-node commission store.
 *
 * <p>Deliberately boring: a JSON file with atomic replace. A hackathon demo must survive a
 * restart, and judges must be able to inspect the evidence without a database.</p>
 */
public final class LedgerService {

    private static final Logger LOG = LoggerFactory.getLogger(LedgerService.class);

    private final Path file;
    private final Map<String, Commission> rows = new LinkedHashMap<>();
    private final Object lock = new Object();

    public LedgerService(Path storageDir, boolean seedDemo) {
        this.file = storageDir.resolve("ledger.json");
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create storage directory " + storageDir, e);
        }
        load();
        if (seedDemo && rows.isEmpty()) {
            seed();
        }
    }

    public Commission create(Contract contract, String chat, String mode, boolean demo) {
        String id = nextId();
        Commission c = new Commission(id, contract, excerpt(chat), mode, demo);
        c.addAudit("system", "commission.created",
                "Contract parsed for " + contract.priceUSD().toPlainString() + " USD");
        synchronized (lock) {
            rows.put(id, c);
            persistLocked();
        }
        return c;
    }

    public Optional<Commission> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            return Optional.ofNullable(rows.get(id));
        }
    }

    public Optional<Commission> findByOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Optional.empty();
        }
        synchronized (lock) {
            return rows.values().stream()
                    .filter(c -> orderId.equals(c.getPaypalOrderId()))
                    .findFirst();
        }
    }

    public List<Commission> all() {
        synchronized (lock) {
            List<Commission> copy = new ArrayList<>(rows.values());
            copy.sort(Comparator.comparing(Commission::getCreatedAt).reversed());
            return copy;
        }
    }

    public int count() {
        synchronized (lock) {
            return rows.size();
        }
    }

    public void save(Commission c) {
        synchronized (lock) {
            c.touch();
            rows.put(c.getId(), c);
            persistLocked();
        }
    }

    private String nextId() {
        synchronized (lock) {
            for (int i = 0; i < 20; i++) {
                String candidate = "CG-" + UUID.randomUUID().toString()
                        .replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
                if (!rows.containsKey(candidate)) {
                    return candidate;
                }
            }
            throw new IllegalStateException("Could not allocate a commission id");
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return;
            }
            List<Commission> loaded = Json.GSON.fromJson(raw, new TypeToken<List<Commission>>() {
            }.getType());
            if (loaded != null) {
                for (Commission c : loaded) {
                    if (c != null && c.getId() != null) {
                        rows.put(c.getId(), c);
                    }
                }
            }
            LOG.info("Ledger loaded: {} commission(s)", rows.size());
        } catch (Exception e) {
            LOG.error("Ledger at {} is unreadable; starting empty. Cause: {}", file, e.getMessage());
        }
    }

    private void persistLocked() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.writePretty(new ArrayList<>(rows.values())), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.error("Failed to persist ledger: {}", e.getMessage());
        }
    }

    private static String excerpt(String chat) {
        if (chat == null) {
            return "";
        }
        String clean = chat.replaceAll("\\s+", " ").trim();
        return clean.length() <= 280 ? clean : clean.substring(0, 280) + "…";
    }

    // ---------------------------------------------------------------------
    // Sample data: makes first paint legible. Clearly labelled SAMPLE in the UI.
    // ---------------------------------------------------------------------

    private void seed() {
        Commission held = new Commission("CG-7F2A91C4",
                new Contract("Vex Armor — Fabric 1.21 mod", new java.math.BigDecimal("240.00"),
                        ".jar", "fabric.mod.json",
                        List.of("Mod loads on Fabric 1.21.x",
                                "fabric.mod.json has a valid id and version",
                                "Contains compiled classes, not just assets")),
                "Client: budget $240 for a custom armor mod, must work on Fabric 1.21. Need the .jar by Friday.",
                "mock", true);
        held.setStatus(CommissionStatus.HELD);
        held.setPaypalOrderId("SAMPLE-ORDER-7F2A91C4");
        held.setPaypalStatus("CREATED");
        held.setApprovalUrl("/api/paypal/mock-checkout?order=SAMPLE-ORDER-7F2A91C4");
        held.setDemo(true);
        held.addAudit("seed", "commission.created", "Sample row — not a real PayPal order");

        Commission quoted = new Commission("CG-3B8D02EE",
                new Contract("Aetheris — 4K key art", new java.math.BigDecimal("180.00"),
                        ".png", "3840x2160",
                        List.of("PNG is at least 3840x2160",
                                "Image is not a blank or single-colour canvas",
                                "File opens without decode errors")),
                "Need a 4k render of Aetheris for the store page, $180, PNG please.",
                "sample", true);
        quoted.setStatus(CommissionStatus.QUOTED);
        quoted.addAudit("seed", "commission.created", "Sample row — waiting to be funded");

        Commission zip = new Commission("CG-9C41A7D0",
                new Contract("Kinetic type package", new java.math.BigDecimal("95.00"),
                        ".zip", "any",
                        List.of("Archive contains at least one file",
                                "No path traversal entries",
                                "Source files included")),
                "Short motion-graphics pack, $95, deliver as a zip with source files.",
                "sample", true);
        zip.setStatus(CommissionStatus.QUOTED);
        zip.addAudit("seed", "commission.created", "Sample row — waiting to be funded");

        // Preserve the newest-first ordering the UI expects.
        rows.put(zip.getId(), zip);
        rows.put(quoted.getId(), quoted);
        rows.put(held.getId(), held);
        persistLocked();
        LOG.info("Seeded {} sample commission(s)", rows.size());
    }
}
