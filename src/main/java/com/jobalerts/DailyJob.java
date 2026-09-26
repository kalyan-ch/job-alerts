package com.jobalerts;

import com.jobalerts.ats.AtsClient;
import com.jobalerts.config.Props;
import com.jobalerts.domain.Scored;
import com.jobalerts.filter.Prefilter;
import com.jobalerts.notify.Discord;
import com.jobalerts.score.Scorer;
import com.jobalerts.store.ScoreCache;
import com.jobalerts.store.SeenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class DailyJob implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DailyJob.class);

    private final AtsClient ats;
    private final Prefilter prefilter;
    private final Scorer scorer;
    private final SeenStore seen;
    private final ScoreCache cache;
    private final Discord discord;
    private final Props p;
    private final ApplicationContext ctx;

    DailyJob(AtsClient ats, Prefilter prefilter, Scorer scorer, SeenStore seen, ScoreCache cache,
             Discord discord, Props p, ApplicationContext ctx) {
        this.ats = ats; this.prefilter = prefilter; this.scorer = scorer;
        this.seen = seen; this.cache = cache; this.discord = discord; this.p = p; this.ctx = ctx;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (args.containsOption("run-once")) runOnce();
        if (args.containsOption("from-cache")) fromCache();
        // One-shot modes exit; with no flag the scheduler keeps the JVM up for the 7am run.
        if (args.containsOption("run-once") || args.containsOption("from-cache"))
            System.exit(SpringApplication.exit(ctx));
    }

    /** Re-threshold and resend from scores.jsonl: no fetch, no LLM. For tuning min-score. */
    public void fromCache() {
        var cached = cache.all();
        var top = top(cached);
        log.info("{} cached scores, {} at or above score {}", cached.size(), top.size(), p.minScore());
        discord.send(top);
    }

    private List<Scored> top(List<Scored> scored) {
        return scored.stream()
                .filter(s -> s.score() >= p.minScore())
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(p.maxJobs())
                .toList();
    }

    @Scheduled(cron = "0 0 7 * * *", zone = "America/Los_Angeles")
    public void runOnce() throws Exception {
        long t = System.currentTimeMillis();
        log.info("run started");
        var fresh = ats.fetchAll().stream().filter(seen::isNew).toList();
        var kept = prefilter.apply(fresh);
        log.info("{} fresh, {} past prefilter", fresh.size(), kept.size());

        var top = top(scorer.score(kept));
        log.info("{} at or above score {}", top.size(), p.minScore());

        discord.send(top);
        seen.record(kept);   // don't re-score a job we already judged, even if it scored low
        log.info("run done in {}s", (System.currentTimeMillis() - t) / 1000);
    }
}
