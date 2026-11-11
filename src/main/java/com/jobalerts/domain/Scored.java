package com.jobalerts.domain;

/** visaSponsorship: "yes", "no" or "unknown" (most postings don't say). */
public record Scored(Job job, int score, String reason, String visaSponsorship) {}
