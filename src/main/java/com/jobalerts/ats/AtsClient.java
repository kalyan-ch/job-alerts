package com.jobalerts.ats;

import com.jobalerts.config.Boards;
import com.jobalerts.domain.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Fans companies.yml out across the Board implementations. */
@Component
public class AtsClient {
    private static final Logger log = LoggerFactory.getLogger(AtsClient.class);

    private final Boards config;
    private final Map<String, Board> boards;

    AtsClient(Boards config, List<Board> boards) {
        this.config = config;
        this.boards = boards.stream().collect(Collectors.toMap(Board::name, Function.identity()));
    }

    public List<Job> fetchAll() {
        var out = new ArrayList<Job>();
        for (var c : config.companies()) {
            Board board = boards.get(c.board());
            if (board == null) {
                log.warn("no Board implementation for '{}' ({})", c.board(), c.slug());
                continue;
            }
            try {
                out.addAll(board.fetch(c.slug()));
            } catch (Exception e) {
                log.warn("{}/{} fetch failed: {}", c.board(), c.slug(), e.toString());
            }
        }
        log.info("fetched {} jobs from {} boards", out.size(), config.companies().size());
        return out;
    }
}
