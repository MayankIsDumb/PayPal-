package com.commishguard.service;

import com.commishguard.model.Commission;
import com.commishguard.model.CommissionStatus;
import com.commishguard.model.Contract;
import com.commishguard.model.VerificationResult;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceServiceTest {

    @Test
    void generatesValidPdfInvoiceForReleasedCommission() throws Exception {
        Contract contract = new Contract(
                "Minecraft Skyblock Plugin v1.2",
                new BigDecimal("45.00"),
                ".jar",
                "fabric.mod.json present",
                List.of("Tested on 1.20.4", "Zero memory leaks", "Commands registered")
        );

        Commission c = new Commission("test-comm-001", contract, "Hey can you build this plugin?", "mock", false);
        c.setStatus(CommissionStatus.RELEASED);
        c.setPaypalOrderId("ORDER-TEST-1234");
        c.setAuthorizationId("AUTH-TEST-5678");
        c.setCaptureId("CAPTURE-TEST-9999");
        c.setCaptureStatus("COMPLETED");
        c.setPaypalStatus("COMPLETED");

        VerificationResult vr = new VerificationResult(
                true,
                1.0,
                "All verification checks passed cleanly.",
                "skyblock-v1.2.jar",
                2048576L,
                Instant.now().toString(),
                List.of(
                        VerificationResult.Check.pass("ext", "Extension matches .jar", "File has .jar extension"),
                        VerificationResult.Check.pass("magic", "Java bytecode signature", "Found CAFEBABE magic bytes"),
                        VerificationResult.Check.pass("meta", "fabric.mod.json entry", "Found fabric.mod.json")
                )
        );
        c.setVerification(vr);
        c.addAudit("paypal", "escrow.authorized", "Authorized $45.00 USD");
        c.addAudit("verifier", "verification.passed", "Verification score 100%");
        c.addAudit("paypal", "escrow.captured", "Captured funds $45.00 USD");
        c.addAudit("paypal", "escrow.authorized", "mock approval \u2192 authorization AUTH-TEST-5678");

        byte[] pdfBytes = InvoiceService.generate(c);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 500, "PDF should have substantial content");

        // Check PDF header signature '%PDF-'
        String header = new String(pdfBytes, 0, Math.min(pdfBytes.length, 10), StandardCharsets.US_ASCII);
        assertTrue(header.startsWith("%PDF-"), "Generated document must start with %PDF- header");

        String text = extractText(pdfBytes);
        assertTrue(text.contains("CommishGuard AI"));
        assertTrue(text.contains("TOTAL PAID"));
        assertTrue(text.contains("$45.00"), "Invoice must show the full captured amount");
        assertTrue(text.contains("PASSED"));
        assertTrue(text.contains("mock approval -> authorization AUTH-TEST-5678"),
                "Audit punctuation must be normalised for the PDF font");
        assertFalse(text.contains("\uFFFD"), "Invoice must not contain replacement glyphs");
    }

    @Test
    void generatesPdfEvenWithMinimalDetails() {
        Contract contract = new Contract(
                "Minimal Commission",
                new BigDecimal("10.00"),
                ".png",
                null,
                null
        );
        Commission c = new Commission("test-comm-min", contract, null, "mock", false);
        c.setStatus(CommissionStatus.RELEASED);

        byte[] pdfBytes = InvoiceService.generate(c);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 500);
        String header = new String(pdfBytes, 0, Math.min(pdfBytes.length, 10), StandardCharsets.US_ASCII);
        assertTrue(header.startsWith("%PDF-"));
    }

    private static String extractText(byte[] pdfBytes) throws Exception {
        try (PdfReader reader = new PdfReader(pdfBytes)) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            return text.toString();
        }
    }
}
