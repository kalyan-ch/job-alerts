package com.jobalerts.domain;

public record Scored(Job job, int score, String reason, boolean visaRisk) {}
