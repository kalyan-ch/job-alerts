package com.jobalerts;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Binds the root-level `companies:` list from companies.yml (imported in application.yml). */
@ConfigurationProperties
public record Boards(List<Company> companies) {
    public record Company(String board, String slug) {}
}
