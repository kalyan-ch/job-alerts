package com.jobalerts.ats;

import com.jobalerts.filter.Prefilter;
import org.junit.jupiter.api.Test;

import static com.jobalerts.Fixtures.PROPS;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkdayBoardTest {

    @Test
    void rejectsSlugThatIsntTenantWdSiteSearch() {
        var board = new WorkdayBoard(new Http(), new Prefilter(PROPS));
        assertThrows(IllegalArgumentException.class, () -> board.fetch("nvidia"));
        assertThrows(IllegalArgumentException.class, () -> board.fetch("nvidia/wd5/Site"));
    }
}
