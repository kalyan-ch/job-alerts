package com.jobalerts.ats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkdayBoardTest {

    @Test
    void rejectsSlugThatIsntTenantWdSiteSearch() {
        var board = new WorkdayBoard();
        assertThrows(IllegalArgumentException.class, () -> board.fetch("nvidia"));
        assertThrows(IllegalArgumentException.class, () -> board.fetch("nvidia/wd5/Site"));
    }
}
