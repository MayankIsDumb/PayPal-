package com.commishguard.service;

import com.commishguard.config.EnvConfig;
import com.commishguard.model.Contract;
import com.commishguard.model.VerificationResult;
import com.commishguard.model.VerificationResult.Check;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Deterministic deliverable inspection. No AI, no network, no execution of uploaded code.
 *
 * <p>All checks are fail-closed: an exception during inspection yields a failed result, never a
 * pass. The uploaded filename is never used as a path — callers store the bytes under a fresh
 * UUID name and pass the original only as display metadata.</p>
 */
public final class FileVerifier {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final int FABRIC_MAX_BYTES = 1024 * 1024;

    private final EnvConfig env;

    public FileVerifier(EnvConfig env) {
        this.env = env;
    }

    public VerificationResult verify(Path file, String originalName, Contract contract) {
        List<Check> checks = new ArrayList<>();
        String name = originalName == null ? "deliverable" : originalName;
        long size = -1;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            checks.add(Check.fail("io", "File is readable", "could not stat file: " + e.getMessage()));
        }

        String ext = contract.requiredExtension();
        checks.add(extensionCheck(name, ext));

        if (size == 0) {
            checks.add(Check.fail("size", "Non-empty payload", "file is 0 bytes"));
        } else if (size > env.fileMaxBytes()) {
            checks.add(Check.fail("size", "Within size limit",
                    "file is " + human(size) + ", limit is " + human(env.fileMaxBytes())));
        } else if (size > 0) {
            checks.add(Check.pass("size", "Within size limit", human(size)));
        }

        try {
            if (".png".equals(ext)) {
                verifyPng(file, contract, checks);
            } else if (".jar".equals(ext) || ".zip".equals(ext)) {
                verifyArchive(file, ext, contract, checks);
            } else {
                checks.add(Check.fail("type", "Supported file type", "unsupported contract extension " + ext));
            }
        } catch (Exception e) {
            checks.add(Check.fail("inspect", "File can be inspected",
                    "inspection failed: " + safe(e.getMessage())));
        }

        List<Check> graded = checks.stream().filter(c -> !c.warning()).toList();
        long passed = graded.stream().filter(Check::passed).count();
        boolean ok = !graded.isEmpty() && passed == graded.size();
        double score = graded.isEmpty() ? 0.0 : Math.round((passed * 100.0 / graded.size())) / 100.0;
        String summary = ok
                ? "All " + graded.size() + " checks passed"
                : (graded.size() - passed) + " of " + graded.size() + " checks failed";

        return new VerificationResult(ok, score, summary, name, Math.max(size, 0),
                Instant.now().toString(), List.copyOf(checks));
    }

    // ------------------------------------------------------------------ exts

    private static Check extensionCheck(String originalName, String requiredExt) {
        String lower = originalName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(requiredExt)) {
            return Check.pass("ext", "File type is " + requiredExt, "received " + originalName);
        }
        return Check.fail("ext", "File type is " + requiredExt,
                "received " + originalName + " (extension mismatch)");
    }

    // ------------------------------------------------------------------ PNG

    private void verifyPng(Path file, Contract contract, List<Check> checks) throws IOException {
        byte[] head = readFileHead(file, 8);
        boolean magic = head.length == 8 && java.util.Arrays.equals(head, PNG_MAGIC);
        checks.add(new Check("magic", "PNG signature",
                magic, false, magic ? "89 50 4E 47 0D 0A 1A 0A" : "not a PNG at the byte level"));
        if (!magic) {
            return;
        }

        int width;
        int height;
        try (ImageInputStream iis = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                checks.add(Check.fail("decode", "Image decodes", "no ImageIO reader accepted the file"));
                return;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        }

        if (width <= 0 || height <= 0 || width > 20_000 || height > 20_000) {
            checks.add(Check.fail("decode", "Image dimensions are sane",
                    "reported " + width + "x" + height));
            return;
        }
        checks.add(Check.pass("decode", "Image decodes", width + "x" + height));

        Dimension min = parseMinDimensions(contract.minResolutionOrEntryCheck());
        if (min == null) {
            int floor = env.verifyMinRenderPixels();
            checks.add(Check.warn("resolution", "Minimum resolution",
                    "contract check '" + contract.displayCheck() + "' is not a resolution; "
                            + "enforcing floor " + floor + "px"));
            if (width < floor || height < floor) {
                checks.add(Check.fail("resolution-floor", "At least " + floor + "px each side",
                        "got " + width + "x" + height));
            } else {
                checks.add(Check.pass("resolution-floor", "At least " + floor + "px each side",
                        "got " + width + "x" + height));
            }
        } else if (width >= min.width() && height >= min.height()) {
            checks.add(Check.pass("resolution", "At least " + min.label(),
                    "got " + width + "x" + height));
        } else {
            checks.add(Check.fail("resolution", "At least " + min.label(),
                    "got " + width + "x" + height + " (below contract minimum)"));
        }

        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) {
            checks.add(Check.fail("raster", "Raster can be read", "ImageIO returned null"));
            return;
        }
        uniformityCheck(img, checks);
    }

    /**
     * Catches the cheapest fake: a solid-colour canvas exported at the right resolution.
     * Samples a grid so a 4K image is not scanned pixel by pixel.
     */
    private static void uniformityCheck(BufferedImage img, List<Check> checks) {
        int w = img.getWidth();
        int h = img.getHeight();
        int step = Math.max(1, Math.min(w, h) / 200);
        Set<Integer> colors = new HashSet<>();
        int minLuma = 255;
        int maxLuma = 0;
        long opaque = 0;
        long samples = 0;

        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int rgb = img.getRGB(x, y);
                int alpha = (rgb >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                opaque++;
                if (colors.size() < 4096) {
                    colors.add(rgb & 0xFFFFFF);
                }
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int luma = (int) (0.2126 * r + 0.7152 * g + 0.0722 * b);
                minLuma = Math.min(minLuma, luma);
                maxLuma = Math.max(maxLuma, luma);
                samples++;
            }
        }

        if (samples == 0 || opaque == 0) {
            checks.add(Check.fail("content", "Contains visible content", "image is fully transparent"));
            return;
        }
        if (colors.size() <= 3 || (maxLuma - minLuma) < 4) {
            checks.add(Check.fail("content", "Contains real detail",
                    "looks like a blank/single-colour canvas (" + colors.size()
                            + " distinct sampled colour(s), luma range " + (maxLuma - minLuma) + ")"));
            return;
        }
        checks.add(Check.pass("content", "Contains real detail",
                colors.size() + "+ distinct colours sampled, luma range " + (maxLuma - minLuma)));
    }

    record Dimension(int width, int height, String label) {
    }

    static Dimension parseMinDimensions(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String s = token.toLowerCase(Locale.ROOT).replace("×", "x").trim();
        if (s.contains("4k") || s.contains("2160p") || s.contains("uhd")) {
            return new Dimension(3840, 2160, "3840x2160 (4K)");
        }
        if (s.contains("1440p") || s.contains("qhd")) {
            return new Dimension(2560, 1440, "2560x1440 (1440p)");
        }
        if (s.contains("1080p") || s.contains("full hd") || s.contains("fhd")) {
            return new Dimension(1920, 1080, "1920x1080 (1080p)");
        }
        if (s.contains("720p")) {
            return new Dimension(1280, 720, "1280x720 (720p)");
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{3,5})\\s*x\\s*(\\d{3,5})").matcher(s);
        if (m.find()) {
            int w = Integer.parseInt(m.group(1));
            int h = Integer.parseInt(m.group(2));
            return new Dimension(w, h, w + "x" + h);
        }
        m = java.util.regex.Pattern.compile("(\\d{3,5})\\s*px").matcher(s);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            return new Dimension(n, n, n + "px");
        }
        return null;
    }

    // --------------------------------------------------------------- archives

    private void verifyArchive(Path file, String ext, Contract contract, List<Check> checks) throws IOException {
        byte[] magic = readFileHead(file, 4);
        boolean zipMagic = magic.length == 4 && magic[0] == 'P' && magic[1] == 'K'
                && ((magic[2] == 3 && magic[3] == 4) || (magic[2] == 5 && magic[3] == 6));
        checks.add(new Check("magic", "ZIP/JAR signature", zipMagic, false,
                zipMagic ? "PK\\x03\\x04" : "missing PK header (renamed file?)"));
        if (!zipMagic) {
            return;
        }

        long fileSize = Files.size(file);
        int maxEntries = env.verifyMaxEntries();
        long maxInflated = env.verifyMaxUncompressedBytes();
        int maxRatio = env.verifyMaxRatio();

        int entries = 0;
        int classCount = 0;
        boolean classMagic = false;
        boolean fabricFound = false;
        String fabricDetail = null;
        boolean manifest = false;
        boolean traversal = false;
        boolean duplicate = false;
        boolean tooManyEntries = false;
        boolean inflatedTooFar = false;
        long inflated = 0;
        Set<String> seen = new HashSet<>();
        String requiredEntry = normalizeRequiredEntry(contract.minResolutionOrEntryCheck());
        boolean requiredFound = false;

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            byte[] buf = new byte[8192];
            ZipEntry entry;
            walk:
            while ((entry = zis.getNextEntry()) != null) {
                entries++;
                if (entries > maxEntries) {
                    tooManyEntries = true;
                    break;
                }
                String raw = entry.getName();
                String entryName = raw.replace('\\', '/');
                if (entryName.startsWith("/") || entryName.startsWith("..")
                        || entryName.contains("/../") || entryName.matches("^[A-Za-z]:.*")) {
                    traversal = true;
                }
                if (!seen.add(entryName.toLowerCase(Locale.ROOT))) {
                    duplicate = true;
                }
                if (!entry.isDirectory() && requiredEntry != null
                        && entryName.equalsIgnoreCase(requiredEntry)) {
                    requiredFound = true;
                }

                if (!entry.isDirectory() && entryName.endsWith(".class")) {
                    byte[] head = readEntryBytes(zis, 4);
                    classCount++;
                    if (head.length == 4 && (head[0] & 0xFF) == 0xCA && (head[1] & 0xFF) == 0xFE
                            && (head[2] & 0xFF) == 0xBA && (head[3] & 0xFF) == 0xBE) {
                        classMagic = true;
                    }
                }

                if (!entry.isDirectory() && entryName.equalsIgnoreCase("fabric.mod.json")) {
                    fabricFound = true;
                    try {
                        byte[] bytes = readUpTo(zis, FABRIC_MAX_BYTES);
                        fabricDetail = validateFabricMetadata(new String(bytes, StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        fabricDetail = "invalid: " + safe(e.getMessage());
                    }
                }

                if (!entry.isDirectory() && entryName.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    manifest = true;
                }

                int read;
                while ((read = zis.read(buf)) != -1) {
                    inflated += read;
                    if (inflated > maxInflated) {
                        inflatedTooFar = true;
                        break walk;
                    }
                }
                zis.closeEntry();
            }
        }

        checks.add(new Check("entries", "Archive has entries",
                entries > 0, false, entries + " entries"));
        checks.add(new Check("entry-limit", "Entry count within limit",
                !tooManyEntries, false, tooManyEntries
                        ? "more than " + maxEntries + " entries"
                        : entries + " / " + maxEntries));
        checks.add(new Check("safety", "No path traversal",
                !traversal, false, traversal ? "entry escapes the archive root" : "all paths relative"));
        checks.add(new Check("duplicates", "No duplicate entries",
                !duplicate, false, duplicate ? "case-insensitive name collision" : "names unique"));
        long ratio = fileSize > 0 ? inflated / Math.max(fileSize, 1) : 0;
        checks.add(new Check("inflate", "No zip-bomb expansion",
                !inflatedTooFar && ratio <= maxRatio, false,
                inflatedTooFar
                        ? "inflated past " + human(maxInflated)
                        : human(inflated) + " inflated, ratio " + ratio + ":1 (limit " + maxRatio + ":1)"));

        if (".jar".equals(ext)) {
            checks.add(new Check("classes", "Contains compiled .class files",
                    classCount > 0, false, classCount + " class entries"));
            checks.add(new Check("class-magic", "Class files are real bytecode",
                    classMagic, false, classMagic ? "CAFEBABE verified" : "no valid class header found"));
        } else {
            checks.add(new Check("classes", "Contains compiled .class files",
                    classCount > 0, classCount == 0,
                    classCount > 0 ? classCount + " class entries" : "no .class entries (warning for .zip)"));
            if (classCount > 0) {
                checks.add(new Check("class-magic", "Class files are real bytecode",
                        classMagic, false, classMagic ? "CAFEBABE verified" : "no valid class header found"));
            }
        }

        checks.add(new Check("manifest", "META-INF/MANIFEST.MF present",
                manifest, true, manifest ? "present" : "not present (common for Fabric jars)"));

        if (requiredEntry != null) {
            checks.add(new Check("required-entry", "Contains " + requiredEntry,
                    requiredFound, false, requiredFound ? "found at archive root" : "missing from archive"));
        }
        if (fabricFound) {
            boolean valid = fabricDetail != null && !fabricDetail.startsWith("invalid");
            checks.add(new Check("fabric", "fabric.mod.json is valid", valid, false,
                    valid ? fabricDetail : String.valueOf(fabricDetail)));
        } else if ("fabric.mod.json".equalsIgnoreCase(requiredEntry)) {
            checks.add(Check.fail("fabric", "fabric.mod.json is valid", "file not present"));
        }
    }

    private static String normalizeRequiredEntry(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("any") || t.equalsIgnoreCase("none")
                || t.equalsIgnoreCase("structural")) {
            return null;
        }
        if (t.equalsIgnoreCase("fabric.mod.json")) {
            return "fabric.mod.json";
        }
        if (t.endsWith(".json") || t.endsWith(".class") || t.contains("/")) {
            return t.replace('\\', '/');
        }
        return null;
    }

    private static String validateFabricMetadata(String raw) {
        try {
            JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
            String id = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsString() : "";
            String version = o.has("version") && !o.get("version").isJsonNull()
                    ? o.get("version").getAsString() : "";
            String schema = o.has("schemaVersion") ? o.get("schemaVersion").getAsString() : "?";
            if (id.isBlank() || version.isBlank()) {
                return "invalid: missing id or version";
            }
            if (!id.matches("[a-z0-9_.-]{1,64}")) {
                return "invalid: id must be lowercase [a-z0-9_.-]";
            }
            return "id=" + id + ", version=" + version + ", schema=" + schema;
        } catch (Exception e) {
            return "invalid: not a JSON object";
        }
    }

    // ----------------------------------------------------------------- utils

    private static byte[] readFileHead(Path file, int n) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(n);
        }
    }

    /** Reads from an open archive entry without closing the enclosing ZipInputStream. */
    private static byte[] readEntryBytes(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(buf, off, n - off);
            if (read == -1) {
                break;
            }
            off += read;
        }
        return off == n ? buf : java.util.Arrays.copyOf(buf, off);
    }

    private static byte[] readUpTo(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int read;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > max) {
                throw new IOException("entry exceeds " + human(max));
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String safe(String s) {
        if (s == null) {
            return "unknown error";
        }
        return s.length() > 160 ? s.substring(0, 160) : s;
    }
}
