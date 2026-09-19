package com.jobalerts.filter;

import com.jobalerts.config.Props;
import com.jobalerts.domain.Job;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cheap rules that run before the LLM so token spend stays flat. */
@Component
public class Prefilter {
    private static final Pattern PAY = Pattern.compile("\\$\\s?(\\d{2,3}),?(\\d{3})");

    private final Props p;
    private final Pattern location, reject, visa;

    public Prefilter(Props p) {
        this.p = p;
        this.location = ci(p.locationRegex());
        this.reject = ci(p.rejectRegex());
        this.visa = ci(p.visaRegex());
    }

    private static Pattern ci(String re) { return Pattern.compile(re, Pattern.CASE_INSENSITIVE); }

    public List<Job> apply(List<Job> jobs) { return jobs.stream().filter(this::keep).toList(); }

    public boolean keep(Job j) {
        String text = j.title() + "\n" + j.location() + "\n" + j.descriptionText();
        if (!titleOk(j.title())) return false;
        if (reject.matcher(text).find()) return false;
        if (!location.matcher(j.location() + "\n" + j.descriptionText()).find()) return false;
        if (visa.matcher(text).find()) return false;
        return payOk(j.descriptionText());
    }

    private boolean titleOk(String title) {
        String t = title.toLowerCase();
        if (p.titleDeny().stream().anyMatch(t::contains)) return false;
        return p.titleAllow().isEmpty() || p.titleAllow().stream().anyMatch(t::contains);
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
