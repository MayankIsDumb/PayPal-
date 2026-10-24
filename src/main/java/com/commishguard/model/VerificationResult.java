package com.commishguard.model;

import java.util.List;

/** Outcome of the deterministic artifact inspection. */
public record VerificationResult(
        boolean passed,
        double score,
        String summary,
        String fileName,
        long sizeBytes,
        String verifiedAt,
        List<Check> checks) {

    public record Check(String id, String label, boolean passed, boolean warning, String detail) {
        public static Check pass(String id, String label, String detail) {
            return new Check(id, label, true, false, detail);
        }

        public static Check fail(String id, String label, String detail) {
            return new Check(id, label, false, false, detail);
        }

        public static Check warn(String id, String label, String detail) {
            return new Check(id, label, true, true, detail);
        }
    }

    public static VerificationResult failed(String summary, String fileName) {
        return new VerificationResult(false, 0.0, summary, fileName, 0L,
                java.time.Instant.now().toString(),
                List.of(Check.fail("io", "File is readable", summary)));
    }
}
