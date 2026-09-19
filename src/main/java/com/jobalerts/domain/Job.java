package com.jobalerts.domain;

import java.time.Instant;

public record Job(String id, String company, String title, String location, String url,
                  String descriptionText, Instant postedAt) {}
