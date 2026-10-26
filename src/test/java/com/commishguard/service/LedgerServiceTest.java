package com.commishguard.service;

import com.commishguard.model.Commission;
import com.commishguard.model.CommissionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerServiceTest {

    @TempDir
    Path tmp;

    @Test
    void seededHeldSampleCarriesARealAuthorization() {
        LedgerService ledger = new LedgerService(tmp, true);

        Commission sample = ledger.all().stream()
                .filter(c -> "CG-7F2A91C4".equals(c.getId()))
                .findFirst()
                .orElseThrow();

        assertEquals(CommissionStatus.HELD, sample.getStatus());
        assertEquals("APPROVED", sample.getPaypalStatus());
        assertNotNull(sample.getAuthorizationId());
        assertTrue(sample.getAuthorizationId().startsWith("SAMPLE-AUTH-"));
    }
}
