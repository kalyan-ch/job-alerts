package com.jobalerts;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Throwaway Postgres for @SpringBootTest; same image as compose.yml. */
@TestConfiguration(proxyBeanMethods = false)
public class TestDb {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() { return new PostgreSQLContainer("postgres:16.4-alpine"); }
}
