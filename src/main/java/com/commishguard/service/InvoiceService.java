package com.commishguard.service;

import com.commishguard.model.AuditEvent;
import com.commishguard.model.Commission;
import com.commishguard.model.Contract;
import com.commishguard.model.VerificationResult;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generates a professional PDF invoice for a completed (RELEASED) commission.
 *
 * <p>Uses OpenPDF to render a clean, branded invoice with all escrow details,
 * verification evidence, and PayPal transaction references.</p>
 */
public final class InvoiceService {

    private static final Logger LOG = LoggerFactory.getLogger(InvoiceService.class);

    // Brand colours
    private static final Color BRAND_DARK = new Color(10, 11, 11);
    private static final Color ACCENT_GOLD = new Color(213, 185, 142);
    private static final Color TEXT_PRIMARY = new Color(40, 42, 40);
    private static final Color TEXT_MUTED = new Color(88, 92, 86);
    private static final Color LINE_COLOR = new Color(220, 222, 218);
    private static final Color CELL_BG_ALT = new Color(248, 249, 247);
    private static final Color SUCCESS_GREEN = new Color(45, 140, 80);
    private static final Color TABLE_HEADER_BG = new Color(14, 16, 15);

    // Fonts
    private static final Font HEADING_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, TEXT_MUTED);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10, TEXT_PRIMARY);
    private static final Font BODY_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, TEXT_PRIMARY);
    private static final Font SMALL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8, TEXT_MUTED);
    private static final Font MONO_FONT = FontFactory.getFont(FontFactory.COURIER, 9, TEXT_PRIMARY);
    private static final Font PRICE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, TEXT_PRIMARY);
    private static final Font LABEL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 9, TEXT_MUTED);
    private static final Font VALUE_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10, TEXT_PRIMARY);
    private static final Font HEADER_CELL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
    private static final Font STATUS_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, SUCCESS_GREEN);
    private static final Font WATERMARK_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, ACCENT_GOLD);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter
            .ofPattern("MMM dd, yyyy - HH:mm z")
            .withZone(ZoneId.systemDefault());

    private InvoiceService() {
    }

    /**
     * Generate a PDF invoice for the given commission.
     *
     * @param commission a commission in RELEASED state
     * @return the PDF bytes
     * @throws IllegalStateException if the commission is not released or has no contract
     */
    public static byte[] generate(Commission commission) {
        if (commission.getContract() == null) {
            throw new IllegalStateException("Commission has no contract.");
        }

        Contract ct = commission.getContract();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            Document doc = new Document(PageSize.A4, 50, 50, 45, 45);
            PdfWriter writer = PdfWriter.getInstance(doc, baos);
            doc.open();

            // ── Header: brand + invoice title ──
            addHeader(doc, commission);

            doc.add(spacer(18));

            // ── Status + dates strip ──
            addStatusStrip(doc, commission);

            doc.add(spacer(16));
            doc.add(separator());
            doc.add(spacer(16));

            // ── Commission details grid ──
            addDetailsGrid(doc, commission, ct);

            doc.add(spacer(18));

            // ── Line items table ──
            addLineItems(doc, ct);

            doc.add(spacer(14));

            // ── Totals ──
            addTotals(doc, ct);

            doc.add(spacer(18));
            doc.add(separator());
            doc.add(spacer(14));

            // ── PayPal transaction details ──
            addPayPalSection(doc, commission);

            // ── Verification evidence ──
            if (commission.getVerification() != null) {
                doc.newPage();
                addVerificationSection(doc, commission.getVerification());
                doc.add(spacer(10));
            }

            // ── Audit trail (latest 6 events) ──
            addAuditTrail(doc, commission);

            doc.add(spacer(20));
            doc.add(separator());
            doc.add(spacer(10));

            // ── Footer ──
            addFooter(doc, commission);

            doc.close();
            writer.close();
        } catch (DocumentException e) {
            LOG.error("Failed to generate invoice PDF for {}", commission.getId(), e);
            throw new IllegalStateException("PDF generation failed: " + e.getMessage(), e);
        }

        LOG.info("Generated invoice PDF for {} ({} bytes)", commission.getId(), baos.size());
        return baos.toByteArray();
    }

    // ────────────────────────────────────────────────────────────── sections

    private static void addHeader(Document doc, Commission c) throws DocumentException {
        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        header.setWidths(new float[]{60, 40});

        // Left: brand
        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.NO_BORDER);
        left.setPaddingBottom(8);
        Paragraph brand = new Paragraph();
        brand.add(new Chunk("CommishGuard AI", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, BRAND_DARK)));
        brand.add(new Chunk("\n"));
        brand.add(new Chunk("Verifiable Escrow for Creators", FontFactory.getFont(FontFactory.HELVETICA, 9, TEXT_MUTED)));
        left.addElement(brand);
        header.addCell(left);

        // Right: INVOICE label
        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.NO_BORDER);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        right.setPaddingBottom(8);
        Paragraph inv = new Paragraph();
        inv.setAlignment(Element.ALIGN_RIGHT);
        inv.add(new Chunk("INVOICE", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 24, new Color(200, 203, 196))));
        inv.add(new Chunk("\n"));
        inv.add(new Chunk(pdfSafe(c.getId()), FontFactory.getFont(FontFactory.COURIER, 11, TEXT_MUTED)));
        right.addElement(inv);
        header.addCell(right);

        doc.add(header);
    }

    private static void addStatusStrip(Document doc, Commission c) throws DocumentException {
        PdfPTable strip = new PdfPTable(3);
        strip.setWidthPercentage(100);
        strip.setWidths(new float[]{34, 33, 33});

        // Status
        PdfPCell statusCell = new PdfPCell();
        statusCell.setBorder(Rectangle.NO_BORDER);
        statusCell.setBackgroundColor(new Color(240, 248, 240));
        statusCell.setPadding(10);
        statusCell.setBorderWidthBottom(2);
        statusCell.setBorderColorBottom(SUCCESS_GREEN);
        Paragraph sp = new Paragraph();
        sp.add(new Chunk("STATUS\n", HEADING_FONT));
        String statusText = "RELEASED".equals(c.getStatus().name()) ? "PAID" : c.getStatus().name();
        sp.add(new Chunk(statusText, STATUS_FONT));
        statusCell.addElement(sp);
        strip.addCell(statusCell);

        // Issue date
        strip.addCell(labelValueCell("ISSUED", formatDate(c.getCreatedAt())));

        // Paid date
        strip.addCell(labelValueCell("PAID", formatDate(c.getUpdatedAt())));

        doc.add(strip);
    }

    private static void addDetailsGrid(Document doc, Commission c, Contract ct) throws DocumentException {
        PdfPTable grid = new PdfPTable(2);
        grid.setWidthPercentage(100);
        grid.setWidths(new float[]{50, 50});

        grid.addCell(detailCell("Commission ID", c.getId()));
        grid.addCell(detailCell("Mode", c.getMode() != null ? c.getMode().toUpperCase() : "-"));
        grid.addCell(detailCell("Deliverable Type", ct.requiredExtension()));
        grid.addCell(detailCell("Verification", ct.displayCheck()));

        if (c.getDeliverableName() != null) {
            grid.addCell(detailCell("File Delivered", c.getDeliverableName()));
            grid.addCell(detailCell("File Size", formatBytes(c.getDeliverableSize())));
        }

        doc.add(grid);
    }

    private static void addLineItems(Document doc, Contract ct) throws DocumentException {
        Paragraph heading = new Paragraph("LINE ITEMS", HEADING_FONT);
        heading.setSpacingAfter(8);
        doc.add(heading);

        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{55, 25, 20});

        // Header row
        table.addCell(tableHeaderCell("DESCRIPTION"));
        table.addCell(tableHeaderCell("CRITERIA"));
        table.addCell(tableHeaderCellRight("AMOUNT"));

        // Main item
        PdfPCell descCell = new PdfPCell(new Phrase(pdfSafe(ct.title()), BODY_BOLD));
        descCell.setPadding(10);
        descCell.setBorder(Rectangle.BOTTOM);
        descCell.setBorderColorBottom(LINE_COLOR);
        descCell.setBackgroundColor(Color.WHITE);
        table.addCell(descCell);

        List<String> checks = ct.checklist() != null ? ct.checklist() : List.of();
        String criteriaText = checks.isEmpty() ? "-" : checks.size() + " acceptance criteria";
        PdfPCell critCell = new PdfPCell(new Phrase(criteriaText, BODY_FONT));
        critCell.setPadding(10);
        critCell.setBorder(Rectangle.BOTTOM);
        critCell.setBorderColorBottom(LINE_COLOR);
        critCell.setBackgroundColor(Color.WHITE);
        table.addCell(critCell);

        PdfPCell amtCell = new PdfPCell(new Phrase("$" + ct.priceUSD().toPlainString(), BODY_BOLD));
        amtCell.setPadding(10);
        amtCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        amtCell.setBorder(Rectangle.BOTTOM);
        amtCell.setBorderColorBottom(LINE_COLOR);
        amtCell.setBackgroundColor(Color.WHITE);
        table.addCell(amtCell);

        // Acceptance criteria rows
        for (int i = 0; i < checks.size(); i++) {
            Color bg = (i % 2 == 0) ? CELL_BG_ALT : Color.WHITE;

            PdfPCell cDesc = new PdfPCell(new Phrase("  - " + pdfSafe(checks.get(i)), SMALL_FONT));
            cDesc.setPadding(6);
            cDesc.setPaddingLeft(20);
            cDesc.setBorder(Rectangle.BOTTOM);
            cDesc.setBorderColorBottom(LINE_COLOR);
            cDesc.setBackgroundColor(bg);
            cDesc.setColspan(2);
            table.addCell(cDesc);

            PdfPCell cAmt = new PdfPCell(new Phrase("", SMALL_FONT));
            cAmt.setPadding(6);
            cAmt.setBorder(Rectangle.BOTTOM);
            cAmt.setBorderColorBottom(LINE_COLOR);
            cAmt.setBackgroundColor(bg);
            table.addCell(cAmt);
        }

        doc.add(table);
    }

    private static void addTotals(Document doc, Contract ct) throws DocumentException {
        PdfPTable totals = new PdfPTable(2);
        totals.setWidthPercentage(55);
        totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totals.setWidths(new float[]{45, 55});

        // Subtotal
        totals.addCell(totalLabelCell("Subtotal"));
        totals.addCell(totalValueCell("$" + ct.priceUSD().toPlainString()));

        // Separator
        PdfPCell sep = new PdfPCell();
        sep.setColspan(2);
        sep.setBorder(Rectangle.BOTTOM);
        sep.setBorderColorBottom(BRAND_DARK);
        sep.setBorderWidthBottom(1.5f);
        sep.setFixedHeight(6);
        totals.addCell(sep);

        // Total
        PdfPCell totalLabel = new PdfPCell(new Phrase("TOTAL PAID", BODY_BOLD));
        totalLabel.setBorder(Rectangle.NO_BORDER);
        totalLabel.setPadding(8);
        totalLabel.setVerticalAlignment(Element.ALIGN_MIDDLE);
        totals.addCell(totalLabel);

        Phrase amount = new Phrase();
        amount.add(new Chunk("$" + ct.priceUSD().toPlainString(), PRICE_FONT));
        amount.add(new Chunk(" USD", FontFactory.getFont(FontFactory.HELVETICA, 8, TEXT_MUTED)));
        PdfPCell totalValue = new PdfPCell(amount);
        totalValue.setBorder(Rectangle.NO_BORDER);
        totalValue.setPadding(8);
        totalValue.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totalValue.setVerticalAlignment(Element.ALIGN_MIDDLE);
        totals.addCell(totalValue);

        doc.add(totals);
    }

    private static void addPayPalSection(Document doc, Commission c) throws DocumentException {
        Paragraph heading = new Paragraph("PAYPAL TRANSACTION", HEADING_FONT);
        heading.setSpacingAfter(8);
        doc.add(heading);

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{35, 65});

        kvRow(table, "Order ID", c.getPaypalOrderId());
        kvRow(table, "Authorization ID", c.getAuthorizationId());
        kvRow(table, "Capture ID", c.getCaptureId());
        kvRow(table, "Capture Status", c.getCaptureStatus());
        kvRow(table, "PayPal Status", c.getPaypalStatus());
        String surface = c.getMode() != null && c.getMode().toLowerCase().contains("live")
                ? "SANDBOX (Live)" : "SANDBOX (Demo)";
        kvRow(table, "Environment", surface);

        doc.add(table);
    }

    private static void addVerificationSection(Document doc, VerificationResult v) throws DocumentException {
        Paragraph heading = new Paragraph("VERIFICATION EVIDENCE", HEADING_FONT);
        heading.setSpacingAfter(8);
        doc.add(heading);

        PdfPTable info = new PdfPTable(2);
        info.setWidthPercentage(100);
        info.setWidths(new float[]{35, 65});
        kvRow(info, "Result", v.passed() ? "PASSED" : "FAILED");
        kvRow(info, "Score", String.format("%.0f%%", v.score() * 100));
        kvRow(info, "File", v.fileName());
        kvRow(info, "Size", formatBytes(v.sizeBytes()));
        kvRow(info, "Verified At", formatDate(v.verifiedAt()));
        kvRow(info, "Summary", v.summary());
        doc.add(info);

        // Individual checks
        if (v.checks() != null && !v.checks().isEmpty()) {
            doc.add(spacer(5));
            PdfPTable checks = new PdfPTable(3);
            checks.setWidthPercentage(100);
            checks.setWidths(new float[]{8, 30, 62});

            checks.addCell(tableHeaderCell(""));
            checks.addCell(tableHeaderCell("CHECK"));
            checks.addCell(tableHeaderCell("DETAIL"));

            for (VerificationResult.Check chk : v.checks()) {
                Color bg = chk.passed() ? new Color(240, 248, 240) : new Color(252, 240, 240);
                String icon = chk.warning() ? "!" : (chk.passed() ? "OK" : "X");
                Color iconColor = chk.warning() ? ACCENT_GOLD : (chk.passed() ? SUCCESS_GREEN : new Color(201, 144, 144));

                PdfPCell iconCell = new PdfPCell(new Phrase(icon,
                        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, iconColor)));
                iconCell.setPadding(4);
                iconCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                iconCell.setBorder(Rectangle.BOTTOM);
                iconCell.setBorderColorBottom(LINE_COLOR);
                iconCell.setBackgroundColor(bg);
                checks.addCell(iconCell);

                PdfPCell labelCell = new PdfPCell(new Phrase(pdfSafe(chk.label()), BODY_FONT));
                labelCell.setPadding(4);
                labelCell.setBorder(Rectangle.BOTTOM);
                labelCell.setBorderColorBottom(LINE_COLOR);
                labelCell.setBackgroundColor(bg);
                checks.addCell(labelCell);

                PdfPCell detailCell = new PdfPCell(new Phrase(pdfSafe(chk.detail()),
                        FontFactory.getFont(FontFactory.COURIER, 8, TEXT_MUTED)));
                detailCell.setPadding(4);
                detailCell.setBorder(Rectangle.BOTTOM);
                detailCell.setBorderColorBottom(LINE_COLOR);
                detailCell.setBackgroundColor(bg);
                checks.addCell(detailCell);
            }
            doc.add(checks);
        }
    }

    private static void addAuditTrail(Document doc, Commission c) throws DocumentException {
        List<AuditEvent> events = c.getAudit();
        if (events == null || events.isEmpty()) {
            return;
        }

        doc.add(spacer(4));
        Paragraph heading = new Paragraph("EVIDENCE TRAIL", HEADING_FONT);
        heading.setSpacingAfter(8);
        doc.add(heading);

        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{20, 25, 55});

        table.addCell(tableHeaderCell("TIME"));
        table.addCell(tableHeaderCell("ACTION"));
        table.addCell(tableHeaderCell("DETAIL"));

        // The full trail lives in the app; the invoice keeps the latest six events.
        List<AuditEvent> toShow = events.size() > 6 ? events.subList(events.size() - 6, events.size()) : events;
        int idx = 0;
        for (AuditEvent ev : toShow) {
            Color bg = (idx++ % 2 == 0) ? Color.WHITE : CELL_BG_ALT;

            PdfPCell timeCell = new PdfPCell(new Phrase(formatDate(ev.at()), SMALL_FONT));
            timeCell.setPadding(4);
            timeCell.setBorder(Rectangle.BOTTOM);
            timeCell.setBorderColorBottom(LINE_COLOR);
            timeCell.setBackgroundColor(bg);
            table.addCell(timeCell);

            PdfPCell actCell = new PdfPCell(new Phrase(pdfSafe(ev.action()),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, TEXT_PRIMARY)));
            actCell.setPadding(4);
            actCell.setBorder(Rectangle.BOTTOM);
            actCell.setBorderColorBottom(LINE_COLOR);
            actCell.setBackgroundColor(bg);
            table.addCell(actCell);

            String detail = ev.detail() != null && ev.detail().length() > 80
                    ? ev.detail().substring(0, 80) + "..."
                    : (ev.detail() != null ? ev.detail() : "");
            PdfPCell detCell = new PdfPCell(new Phrase(pdfSafe(detail), SMALL_FONT));
            detCell.setPadding(4);
            detCell.setBorder(Rectangle.BOTTOM);
            detCell.setBorderColorBottom(LINE_COLOR);
            detCell.setBackgroundColor(bg);
            table.addCell(detCell);
        }

        doc.add(table);
    }

    private static void addFooter(Document doc, Commission c) throws DocumentException {
        Paragraph footer = new Paragraph();
        footer.setAlignment(Element.ALIGN_CENTER);
        footer.add(new Chunk("CommishGuard AI", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, TEXT_MUTED)));
        footer.add(new Chunk(" - Verifiable Escrow for Digital Commissions\n", SMALL_FONT));
        footer.add(new Chunk("This invoice was generated automatically upon payment capture.\n", SMALL_FONT));
        footer.add(new Chunk("All verification checks are deterministic and auditable.\n\n", SMALL_FONT));
        footer.add(new Chunk("Invoice " + pdfSafe(c.getId()) + " - Generated " + DATE_FMT.format(Instant.now()), SMALL_FONT));

        if (c.getMode() != null && !c.getMode().toLowerCase().contains("live")) {
            footer.add(new Chunk("\n\n"));
            footer.add(new Chunk("SANDBOX / DEMO - No real money was moved.", WATERMARK_FONT));
        }
        doc.add(footer);
    }

    // ────────────────────────────────────────────────────────────── helpers

    private static PdfPCell labelValueCell(String label, String value) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setBackgroundColor(CELL_BG_ALT);
        cell.setPadding(10);
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + "\n", HEADING_FONT));
        p.add(new Chunk(pdfSafe(value), VALUE_FONT));
        cell.addElement(p);
        return cell;
    }

    private static PdfPCell detailCell(String label, String value) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColorBottom(LINE_COLOR);
        cell.setPadding(8);
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + "\n", LABEL_FONT));
        p.add(new Chunk(pdfSafe(value), VALUE_FONT));
        cell.addElement(p);
        return cell;
    }

    private static PdfPCell tableHeaderCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, HEADER_CELL_FONT));
        cell.setBackgroundColor(TABLE_HEADER_BG);
        cell.setPadding(8);
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    private static PdfPCell tableHeaderCellRight(String text) {
        PdfPCell cell = tableHeaderCell(text);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }

    private static void kvRow(PdfPTable table, String label, String value) {
        PdfPCell k = new PdfPCell(new Phrase(label, LABEL_FONT));
        k.setPadding(5);
        k.setBorder(Rectangle.BOTTOM);
        k.setBorderColorBottom(LINE_COLOR);
        k.setBackgroundColor(CELL_BG_ALT);

        PdfPCell v = new PdfPCell(new Phrase(pdfSafe(value), MONO_FONT));
        v.setPadding(5);
        v.setBorder(Rectangle.BOTTOM);
        v.setBorderColorBottom(LINE_COLOR);

        table.addCell(k);
        table.addCell(v);
    }

    private static PdfPCell totalLabelCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, LABEL_FONT));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(5);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private static PdfPCell totalValueCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, VALUE_FONT));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(5);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private static Paragraph spacer(float height) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(height);
        p.setLeading(0);
        return p;
    }

    private static LineSeparator separator() {
        LineSeparator sep = new LineSeparator();
        sep.setLineColor(LINE_COLOR);
        sep.setLineWidth(0.5f);
        return sep;
    }

    private static String formatDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return "-";
        }
        try {
            Instant instant = Instant.parse(iso);
            return DATE_FMT.format(instant);
        } catch (Exception e) {
            return pdfSafe(iso);
        }
    }

    /**
     * Base-14 PDF fonts only cover the WinAnsi character set. Normalise the
     * punctuation we use and replace anything outside that set so dynamic text
     * (contracts, audit events, PayPal ids) can never produce tofu glyphs.
     */
    private static String pdfSafe(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String mapped = value
                .replace("\u2014", "-")
                .replace("\u2013", "-")
                .replace("\u2012", "-")
                .replace("\u2011", "-")
                .replace("\u00b7", "-")
                .replace("\u2022", "-")
                .replace("\u2192", "->")
                .replace("\u2190", "<-")
                .replace("\u2713", "OK")
                .replace("\u2714", "OK")
                .replace("\u2715", "X")
                .replace("\u2716", "X")
                .replace("\u2717", "X")
                .replace("\u26a0", "!")
                .replace("\u2212", "-")
                .replace("\u2026", "...")
                .replace("\u20ac", "EUR")
                .replace("\u2018", "'")
                .replace("\u2019", "'")
                .replace("\u201c", "\"")
                .replace("\u201d", "\"")
                .replace("\u00a0", " ");

        StringBuilder out = new StringBuilder(mapped.length());
        for (int i = 0; i < mapped.length(); i++) {
            char ch = mapped.charAt(i);
            if (ch == '\n') {
                out.append(ch);
            } else if (ch == '\t') {
                out.append(' ');
            } else if (ch >= 32 && ch <= 255) {
                out.append(ch);
            } else if (ch != '\r') {
                out.append('?');
            }
        }
        return out.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
