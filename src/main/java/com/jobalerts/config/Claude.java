package com.jobalerts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The Claude scorer, run beside the local one. maxJobs caps API spend per run. */
@ConfigurationProperties("jobalerts.claude")
public record Claude(String apiKey, String model, int maxJobs) {}
