package com.jobalerts;

import com.jobalerts.ats.Board;
import com.jobalerts.config.Boards;
import com.jobalerts.config.Props;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the beans wire and companies.yml binds — the parts a unit test can't reach. */
@SpringBootTest
class WiringTest {

    @Autowired Boards boards;
    @Autowired Props props;
    @Autowired DailyJob dailyJob;
    @Autowired List<Board> boardImpls;

    @Test
    void contextLoadsAndCompaniesBind() {
        assertNotNull(dailyJob);
        assertFalse(boards.companies().isEmpty(), "companies.yml should bind");
        assertNotNull(props.locationRegex());
    }

    @Test
    void everyBoardInCompaniesYmlHasAnImplementation() {
        var known = boardImpls.stream().map(Board::name).toList();
        boards.companies().forEach(c ->
                assertTrue(known.contains(c.board()),
                        "no Board impl for '" + c.board() + "' (" + c.slug() + ")"));
    }
}
