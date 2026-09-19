package com.jobalerts.notify;

import com.jobalerts.domain.Scored;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static com.jobalerts.Fixtures.job;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordTest {

    @Test
    void escapesDiscordMarkdown() {
        assertEquals("Sr. Engineer (SF) C\\*\\*", Discord.esc("Sr. Engineer (SF) C**"));
    }

    @Test
    void messageCarriesTitleCompanyScoreAndLink() {
        String out = Discord.format(new Scored(job("Staff Engineer", "SF", "d"), 91, "", false));
        assertEquals("**Staff Engineer** — co · 91\nhttps://example.com/apply\n\n", out);
    }

    @Test
    void splitsMessagesOnJobBoundaries() {
        var many = IntStream.range(0, 12)
                .mapToObj(i -> new Scored(job("Staff Engineer " + "x".repeat(200) + i, "SF", "d"), 90, "", false))
                .toList();
        var chunks = Discord.chunks(many);
        assertTrue(chunks.size() > 1, "should split past the 2000-char cap");
        chunks.forEach(c -> assertTrue(c.length() <= 2000, "each chunk within cap"));
    }
}
