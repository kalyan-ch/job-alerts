package com.jobalerts.score;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScorerTest {

    @Test
    void parsesJsonOutOfChattyLocalModelOutput() {
        var node = Scorer.parse("Sure! Here you go:\n```json\n{\"results\":[{\"id\":\"a\",\"score\":88}]}\n```");
        assertEquals(88, node.path("results").path(0).path("score").asInt());
    }

    @Test
    void garbageYieldsEmptyNotThrow() {
        assertTrue(Scorer.parse("no json here").path("results").isMissingNode());
        assertTrue(Scorer.parse("").path("results").isMissingNode());
    }

    @Test
    void spotsNoSponsorshipWording() {
        assertTrue(Scorer.noVisa("For US based roles only, please note the Company may not be able to employ candidates "
                + "for this role who have United States work authorization related to certain U.S. visa categories, "
                + "or support future H-1B sponsorship at this time."));
        assertTrue(Scorer.noVisa("We are unable to sponsor visas for this role."));
        assertTrue(Scorer.noVisa("Applicants must be a U.S. citizen."));
        assertTrue(Scorer.noVisa("This position requires access to ITAR-controlled data; applicants must be a U.S. person."));
        assertTrue(Scorer.noVisa("Must be able to work without visa sponsorship."));
        assertFalse(Scorer.noVisa("We sponsor H-1B visas. We do not discriminate."));
        assertFalse(Scorer.noVisa("Not a fit? Apply anyway. Visa sponsorship available."));
    }
}
