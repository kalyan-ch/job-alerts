package com.jobalerts.ats;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Trimmed copies of the live page shapes, so a parsing slip shows here and not as an empty run. */
class ScrapeTest {

    @Test
    void googleReadsPositionalRowsFromInlineBlock() {
        var html = "<script>AF_initDataCallback({key: 'ds:1', hash: '2', data:[[[\"123\",\"Software Engineer\",null,"
                + "[null,\"<li>build</li>\"],[null,\"BS\"],null,null,\"Google\",\"en-US\",[[\"Sunnyvale, CA, USA\"]],"
                + "[null,\"<p>desc</p>\"],null,[1789654101,0]]],null,1,20], sideChannel: {}});</script>";
        var data = GoogleBoard.data(html);
        var j = GoogleBoard.job(data.path(0).path(0));
        assertEquals(1, data.path(2).asInt());
        assertEquals("gg:123", j.id());
        assertEquals("Software Engineer", j.title());
        assertEquals("Sunnyvale, CA, USA", j.location());
        assertTrue(j.descriptionText().contains("desc") && j.descriptionText().contains("build"));
        assertEquals(1789654101, j.postedAt().getEpochSecond());
    }

    @Test
    void appleDecodesJsonInsideJsString() {
        var inner = "{\"loaderData\":{\"search\":{\"totalRecords\":1,\"searchResults\":[{\"id\":\"200-1\","
                + "\"postingTitle\":\"Software Engineer\",\"transformedPostingTitle\":\"software-engineer\","
                + "\"locations\":[{\"name\":\"Cupertino\"}],\"jobSummary\":\"x\",\"postDateInGMT\":\"2026-09-26T14:53:43.567Z\"}]}}}";
        var html = "window.__staticRouterHydrationData = JSON.parse(" + new JsonMapper().writeValueAsString(inner) + ");";
        var j = AppleBoard.job(AppleBoard.search(html).path("searchResults").path(0));
        assertEquals("ap:200-1", j.id());
        assertEquals("Cupertino", j.location());
        assertEquals("https://jobs.apple.com/en-us/details/200-1/software-engineer", j.url());
    }

    @Test
    void layoutChangeFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> GoogleBoard.data("<html></html>"));
        assertThrows(IllegalStateException.class, () -> AppleBoard.search("<html></html>"));
    }
}
