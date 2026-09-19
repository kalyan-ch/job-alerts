package com.jobalerts.score;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
