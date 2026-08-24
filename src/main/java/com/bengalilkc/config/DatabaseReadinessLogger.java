package com.bengalilkc.config;

import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails fast with an actionable message if Postgres is unreachable after startup retries.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class DatabaseReadinessLogger implements ApplicationRunner {

    private final DataSource dataSource;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        String url = environment.getProperty("spring.datasource.url", "(unknown)");
        try (var connection = dataSource.getConnection()) {
            log.info("PostgreSQL ready — connected to {} (catalog={})",
                    url, connection.getCatalog());
        } catch (Exception ex) {
            throw new IllegalStateException(
                    """
                    Cannot connect to PostgreSQL at %s.

                    Local fix:
                      1) Start Docker Desktop and wait until it is running
                      2) Run:  docker compose up -d db
                      3) Wait until healthy:  docker compose ps
                      4) Re-run the Spring Boot application (profile=local)

                    The app auto-creates the database (POSTGRES_DB) and Flyway
                    migrates all tables on startup. Port defaults to localhost:5433.
                    """.formatted(url),
                    ex);
        }
    }
}
