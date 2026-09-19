package com.jobalerts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties("jobalerts")
public record Props(
        Path seenFile,
        Path resumeFile,
        int minScore,
        long payFloor,
        int maxJobs,
        String locationRegex,
        String rejectRegex,
        String visaRegex,
        List<String> titleAllow,
        List<String> titleDeny,
        String llmUrl,
        String model,
        String discordWebhook) {}
