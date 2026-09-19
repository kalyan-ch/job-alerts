package com.jobalerts.ats;

import com.jobalerts.domain.Job;

import java.util.List;

/** One ATS provider. Add a board by adding an implementation — nothing else changes. */
public interface Board {
    /** Matches the `board:` value in companies.yml. */
    String name();

    List<Job> fetch(String slug);
}
