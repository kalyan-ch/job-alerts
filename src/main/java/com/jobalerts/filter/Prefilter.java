package com.jobalerts.filter;

import com.jobalerts.config.Props;
import com.jobalerts.domain.Job;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cheap rules that run before the LLM so token spend stays flat. */
@Component
public class Prefilter {
    private static final Pattern PAY = Pattern.compile("\\$\\s?(\\d{2,3}),?(\\d{3})");

    private final Props p;
    private final Pattern location, reject, visa, deny;

    public Prefilter(Props p) {
        this.p = p;
        this.location = ci(p.locationRegex());
        this.reject = ci(p.rejectRegex());
        this.visa = ci(p.visaRegex());
        // Whole words, so "intern" doesn't drop "Internal Tools" and "ml" doesn't drop "HTML".
        this.deny = p.titleDeny().isEmpty() ? null : ci("\\b(" + String.join("|",
                p.titleDeny().stream().map(Pattern::quote).toList()) + ")\\b");
    }

    private static Pattern ci(String re) { return Pattern.compile(re, Pattern.CASE_INSENSITIVE); }

    public List<Job> apply(List<Job> jobs) { return jobs.stream().filter(this::keep).toList(); }

    public boolean keep(Job j) {
        String text = j.title() + "\n" + j.location() + "\n" + j.descriptionText();
        if (!titleOk(j.title())) return false;
        if (!recent(j.postedAt())) return false;
        if (reject.matcher(text).find()) return false;
        if (!locationOk(j.location())) return false;
        if (visa.matcher(text).find()) return false;
        return payOk(j.descriptionText());
    }

    /** Workday lists a multi-city job as "2 Locations"; let it through, the detail call has the real ones. */
    public boolean locationOk(String loc) {
        String l = Objects.toString(loc, "");
        return l.matches("\\d+ Locations") || location.matcher(l).find();
    }

    /** For boards that pay one detail call per job: run on the list page, so a dropped job costs no request. */
    public boolean listingOk(String title, String loc) { return titleOk(title) && locationOk(loc); }

    private boolean titleOk(String title) {
        if (deny != null && deny.matcher(title).find()) return false;
        String t = title.toLowerCase();
        return p.titleAllow().isEmpty() || p.titleAllow().stream().anyMatch(t::contains);
    }

    /** No date = pass, so a board whose date parsing breaks doesn't silently drop every job. */
    private boolean recent(Instant posted) {
        return posted == null || posted.isAfter(Instant.now().minus(Duration.ofDays(p.maxAgeDays())));
    }

    /** No range found = pass; CA requires disclosure but not everyone complies. */
    public boolean payOk(String description) {
        Matcher m = PAY.matcher(description);
        long max = 0;
        boolean found = false;
        while (m.find()) {
            found = true;
            max = Math.max(max, Long.parseLong(m.group(1) + m.group(2)));
        }
        return !found || max >= p.payFloor();
    }
}
