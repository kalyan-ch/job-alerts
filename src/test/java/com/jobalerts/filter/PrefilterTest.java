package com.jobalerts.filter;

import org.junit.jupiter.api.Test;

import static com.jobalerts.Fixtures.PROPS;
import static com.jobalerts.Fixtures.job;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrefilterTest {

    private final Prefilter f = new Prefilter(PROPS);

    @Test
    void keepsBayAreaEngineerAbovePayFloor() {
        assertTrue(f.keep(job("Senior Backend Engineer", "San Francisco, CA", "$200,000 - $260,000")));
    }

    @Test
    void rejectsByTitleLocationVisaAndPay() {
        assertFalse(f.keep(job("Engineering Manager", "San Francisco", "$300,000")), "denied title");
        assertFalse(f.keep(job("Backend Engineer", "Austin, TX", "$300,000")), "wrong metro");
        assertFalse(f.keep(job("Backend Engineer", "San Francisco", "security clearance required")), "visa");
        assertFalse(f.keep(job("Backend Engineer", "San Francisco", "$120,000 - $150,000")), "below floor");
        assertFalse(f.keep(job("Backend Engineer", "Remote (non-US)", "Remote (non-US)")), "reject regex");
    }

    @Test
    void passesWhenNoPayDisclosed() {
        assertTrue(f.payOk("no numbers here"));
        assertTrue(f.payOk("$150,000 base plus $220,000 total"), "keeps on the max of the range");
    }
}
