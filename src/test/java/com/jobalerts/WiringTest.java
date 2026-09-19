package com.jobalerts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Proves the beans wire and companies.yml binds — the parts a unit test can't reach. */
@SpringBootTest
class WiringTest {

    @Autowired Boards boards;
    @Autowired Props props;
    @Autowired DailyJob dailyJob;

    @Test
    void contextLoadsAndCompaniesBind() {
        assertNotNull(dailyJob);
        assertFalse(boards.companies().isEmpty(), "companies.yml should bind");
        assertNotNull(props.locationRegex());
    }
}
