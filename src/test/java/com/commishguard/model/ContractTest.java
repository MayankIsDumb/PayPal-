package com.commishguard.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContractTest {

    @Test
    void normalizesAndRoundsMoney() {
        Contract c = new Contract("  Vex   Armor  ", new BigDecimal("240.005"),
                "JAR", " fabric.mod.json ", List.of(" loads ", " ")).normalized();

        assertEquals("Vex Armor", c.title());
        assertEquals(new BigDecimal("240.01"), c.priceUSD());
        assertEquals(".jar", c.requiredExtension());
        assertEquals("fabric.mod.json", c.minResolutionOrEntryCheck());
        assertEquals(List.of("loads"), c.checklist());
        c.validate();
    }

    @Test
    void rejectsZeroPrice() {
        Contract c = new Contract("A render", BigDecimal.ZERO, ".png", "1920x1080",
                List.of("looks right")).normalized();
        assertThrows(Contract.ContractException.class, c::validate);
    }

    @Test
    void rejectsUnknownExtension() {
        Contract c = new Contract("Suspicious", new BigDecimal("10.00"), ".exe", "any",
                List.of("x")).normalized();
        assertThrows(Contract.ContractException.class, c::validate);
    }

    @Test
    void rejectsPriceAboveCap() {
        Contract c = new Contract("Whale", new BigDecimal("10000.01"), ".zip", "any",
                List.of("x")).normalized();
        assertThrows(Contract.ContractException.class, c::validate);
    }
}
