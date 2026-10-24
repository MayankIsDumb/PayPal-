package com.commishguard.service;

import com.commishguard.config.EnvConfig;
import com.commishguard.model.Contract;
import com.commishguard.model.VerificationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileVerifierTest {

    @TempDir
    Path tmp;

    private final FileVerifier verifier = new FileVerifier(
            EnvConfig.of(Path.of("."), Map.of("FILE_MAX_MB", "5")));

    private static Contract contract(String ext, String check) {
        return new Contract("Test deliverable", new BigDecimal("10.00"), ext, check,
                List.of("passes structural checks")).normalized();
    }

    private static byte[] classBytes() {
        return new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52, 1, 2, 3};
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private Path jar(boolean withFabric, boolean realClass) throws IOException {
        Path path = tmp.resolve("deliverable.jar");
        try (OutputStream out = Files.newOutputStream(path);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            if (withFabric) {
                put(zip, "fabric.mod.json",
                        "{\"schemaVersion\":1,\"id\":\"vexarmor\",\"version\":\"1.0.0\"}"
                                .getBytes(StandardCharsets.UTF_8));
            }
            put(zip, "com/vex/Armor.class",
                    realClass ? classBytes() : "definitely not bytecode".getBytes(StandardCharsets.UTF_8));
            put(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
        }
        return path;
    }

    private Path png(int w, int h, boolean gradient) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = gradient
                        ? new Color((x * 255) / w, (y * 255) / h, (x + y) % 255).getRGB()
                        : new Color(32, 32, 32).getRGB();
                img.setRGB(x, y, rgb);
            }
        }
        Path path = tmp.resolve("render.png");
        ImageIO.write(img, "png", path.toFile());
        return path;
    }

    @Test
    void validFabricJarPasses() throws IOException {
        VerificationResult r = verifier.verify(jar(true, true), "mod.jar",
                contract(".jar", "fabric.mod.json"));
        assertTrue(r.passed(), () -> r.checks().toString());
        assertTrue(r.checks().stream().anyMatch(c -> c.id().equals("class-magic") && c.passed()));
        assertTrue(r.checks().stream().anyMatch(c -> c.id().equals("fabric") && c.passed()));
    }

    @Test
    void jarWithoutFabricMetadataFails() throws IOException {
        VerificationResult r = verifier.verify(jar(false, true), "mod.jar",
                contract(".jar", "fabric.mod.json"));
        assertFalse(r.passed());
        assertTrue(r.checks().stream().anyMatch(c -> c.id().equals("fabric") && !c.passed()));
    }

    @Test
    void classFileWithoutCafebabeFails() throws IOException {
        VerificationResult r = verifier.verify(jar(true, false), "mod.jar",
                contract(".jar", "fabric.mod.json"));
        assertFalse(r.passed());
        assertTrue(r.checks().stream().anyMatch(c -> c.id().equals("class-magic") && !c.passed()));
    }

    @Test
    void zipSlipEntryFails() throws IOException {
        Path path = tmp.resolve("evil.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            put(zip, "../escape.class", classBytes());
        }
        VerificationResult r = verifier.verify(path, "evil.zip", contract(".zip", "any"));
        assertFalse(r.passed());
        assertTrue(r.checks().stream().anyMatch(c -> c.id().equals("safety") && !c.passed()));
    }

    @Test
    void validPngWithDetailPasses() throws IOException {
        VerificationResult r = verifier.verify(png(800, 600, true), "render.png",
                contract(".png", "800x600"));
        assertTrue(r.passed(), () -> r.checks().toString());
    }

    @Test
    void solidColourPngFailsAsFake() throws IOException {
        VerificationResult r = verifier.verify(png(800, 600, false), "render.png",
                contract(".png", "800x600"));
        assertFalse(r.passed());
        assertTrue(r.checks().stream().anyMatch(c -> c.id().equals("content") && !c.passed()));
    }

    @Test
    void lowResolutionPngFails() throws IOException {
        VerificationResult r = verifier.verify(png(320, 240, true), "render.png",
                contract(".png", "1920x1080"));
        assertFalse(r.passed());
        assertTrue(r.checks().stream().anyMatch(c -> c.id().equals("resolution") && !c.passed()));
    }

    @Test
    void textMasqueradingAsPngFails() throws IOException {
        Path fake = tmp.resolve("fake.png");
        Files.writeString(fake, "this is not a render");
        VerificationResult r = verifier.verify(fake, "fake.png", contract(".png", "800x600"));
        assertFalse(r.passed());
        assertTrue(r.checks().stream().anyMatch(c -> c.id().equals("magic") && !c.passed()));
    }

    @Test
    void wrongExtensionFails() throws IOException {
        VerificationResult r = verifier.verify(jar(true, true), "mod.zip",
                contract(".jar", "fabric.mod.json"));
        assertFalse(r.passed());
        assertTrue(r.checks().stream().anyMatch(c -> c.id().equals("ext") && !c.passed()));
    }

    @Test
    void emptyFileFails() throws IOException {
        Path empty = tmp.resolve("empty.png");
        Files.createFile(empty);
        VerificationResult r = verifier.verify(empty, "empty.png", contract(".png", "1080p"));
        assertFalse(r.passed());
        assertTrue(r.checks().stream().anyMatch(c -> c.id().equals("size") && !c.passed()));
    }

    @Test
    void truncatedArchiveFailsWithoutThrowing() throws IOException {
        Path broken = tmp.resolve("broken.jar");
        Files.write(broken, new byte[]{0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0});
        VerificationResult r = verifier.verify(broken, "broken.jar",
                contract(".jar", "fabric.mod.json"));
        assertFalse(r.passed());
    }

    @Test
    void resolutionTokensAreParsed() {
        var fourK = FileVerifier.parseMinDimensions("4k");
        assertNotNull(fourK);
        assertEquals(3840, fourK.width());
        assertEquals(2160, fourK.height());
        assertEquals(1920, FileVerifier.parseMinDimensions("1080p").width());
        assertEquals(2560, FileVerifier.parseMinDimensions("2560x1440").width());
        assertEquals(2000, FileVerifier.parseMinDimensions("min 2000px").width());
    }
}
