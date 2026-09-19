package com.jobalerts;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrefilterTest {

    private static final Props P = new Props(Path.of("seen.txt"), Path.of("resume.txt"), 70, 180_000, 10,
            "San Francisco|Bay Area|Remote.*US", "Remote \\(non-US\\)",
            "no sponsorship|security clearance",
            List.of("engineer"), List.of("intern", "manager"),
            "http://localhost:11434", "test-model", "");

    private final Prefilter f = new Prefilter(P);

    private static Job job(String title, String location, String description) {
        return new Job("id", "co", title, location, "url", description, null);
    }

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

    @Test
    void stripsGreenhouseHtml() {
        assertEquals("Hello R&D world", AtsClient.plain("&lt;p&gt;Hello  R&amp;D\nworld&lt;/p&gt;"));
    }

    @Test
    void escapesDiscordMarkdown() {
        assertEquals("Sr. Engineer (SF) C\\*\\*", Discord.esc("Sr. Engineer (SF) C**"));
    }

    @Test
    void parsesJsonOutOfChattyLocalModelOutput() {
        var node = Scorer.parse("Sure! Here you go:\n```json\n{\"results\":[{\"id\":\"a\",\"score\":88}]}\n```");
        assertEquals(88, node.path("results").path(0).path("score").asInt());
        assertTrue(Scorer.parse("no json here").path("results").isMissingNode(), "garbage yields empty, not a throw");
    }

    @Test
    void splitsDiscordMessagesOnJobBoundaries() {
        var many = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> new Scorer.Scored(job("Staff Engineer " + "x".repeat(200) + i, "SF", "d"), 90, "", false))
                .toList();
        var chunks = Discord.chunks(many);
        assertTrue(chunks.size() > 1, "should split past the 2000-char cap");
        chunks.forEach(c -> assertTrue(c.length() <= 2000, "each chunk within cap"));
    }
}
