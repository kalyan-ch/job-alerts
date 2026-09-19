package com.jobalerts.notify;

import com.jobalerts.config.Props;
import com.jobalerts.domain.Scored;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class Discord {
    private static final Logger log = LoggerFactory.getLogger(Discord.class);
    private static final int LIMIT = 2000;   // Discord hard-caps message content

    private final RestClient http = RestClient.create();
    private final Props p;

    Discord(Props p) { this.p = p; }

    public void send(List<Scored> top) {
        if (top.isEmpty()) return;   // silence is signal
        for (String chunk : chunks(top)) {
            http.post().uri(p.discordWebhook())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("content", chunk, "flags", 4))   // 4 = suppress link embeds
                    .retrieve().toBodilessEntity();
        }
        log.info("sent {} jobs", top.size());
    }

    /** Split on job boundaries so a posting never straddles two messages. */
    public static List<String> chunks(List<Scored> top) {
        var out = new ArrayList<String>();
        var sb = new StringBuilder();
        for (var s : top) {
            String block = format(s);
            if (sb.length() + block.length() > LIMIT) { out.add(sb.toString()); sb.setLength(0); }
            sb.append(block);
        }
        if (!sb.isEmpty()) out.add(sb.toString());
        return out;
    }

    public static String format(Scored s) {
        return "**" + esc(s.job().title()) + "** — " + esc(s.job().company()) + " · " + s.score() + "\n"
                + s.job().url() + "\n\n";
    }

    /** Discord markdown: escape only what actually formats. */
    public static String esc(String s) {
        return s.replaceAll("([*_~`|\\\\])", "\\\\$1");
    }
}
