package com.jobalerts.ats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HttpTest {

    @Test
    void stripsGreenhouseHtml() {
        assertEquals("Hello R&D world", Http.plain("&lt;p&gt;Hello  R&amp;D\nworld&lt;/p&gt;"));
    }

    @Test
    void badTimestampYieldsNullNotThrow() {
        assertNull(Http.instant(""));
        assertNull(Http.instant("not a date"));
    }
}
