package com.jobalerts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;

@Component
public class DailyJob implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DailyJob.class);

    private final AtsClient ats;
    private final Prefilter prefilter;
    private final Scorer scorer;
    private final SeenStore seen;
    private final Discord discord;
    private final Props p;

    DailyJob(AtsClient ats, Prefilter prefilter, Scorer scorer, SeenStore seen, Discord discord, Props p) {
        this.ats = ats; this.prefilter = prefilter; this.scorer = scorer;
        this.seen = seen; this.discord = discord; this.p = p;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (args.containsOption("run-once")) runOnce();
    }

    @Scheduled(cron = "0 0 7 * * *", zone = "America/Los_Angeles")
    public void runOnce() throws Exception {
        var fresh = ats.fetchAll().stream().filter(seen::isNew).toList();
        var kept = prefilter.apply(fresh);
        log.info("{} fresh, {} past prefilter", fresh.size(), kept.size());

        var top = scorer.score(kept).stream()
                .filter(s -> s.score() >= p.minScore())
                .sorted(Comparator.comparingInt(Scorer.Scored::score).reversed())
                .limit(p.maxJobs())
                .toList();

        discord.send(top);
        seen.record(kept);   // don't re-score a job we already judged, even if it scored low
    }
}
